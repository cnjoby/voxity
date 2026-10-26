"""Private request ledger and bounded collaboration using existing invitations."""

import json
import secrets
from uuid import UUID

from .errors import Problem
from .storage import digest

TERMINAL = {"cancelled", "resolved", "timed_out", "failed"}


class TaskRules:
    def task_row(self, db, user, task_id):
        row = db.execute(
            "SELECT * FROM personal_tasks WHERE id=? AND user_id=?", (task_id, user)
        ).fetchone()
        if not row:
            raise Problem(404, "NOT_FOUND", "任务不存在")
        return row

    def create_personal_task(self, db, user, request_id, text, session_id=None, expires_at=None):
        old = db.execute(
            "SELECT * FROM personal_tasks WHERE user_id=? AND request_id=?", (user, str(request_id))
        ).fetchone()
        if old:
            return old
        tid = "task_" + secrets.token_hex(12)
        db.execute(
            "INSERT INTO personal_tasks(id,user_id,request_id,session_id,kind,status,original_text,"
            "created_at,updated_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
            (
                tid,
                user,
                str(request_id),
                session_id,
                "query",
                "querying",
                text,
                self.clock(),
                self.clock(),
                expires_at or self.clock() + self.settings.exchange_turn_ttl,
            ),
        )
        return self.task_row(db, user, tid)

    def question_task(self, db, question):
        return db.execute(
            "SELECT * FROM personal_tasks WHERE question_id=? AND user_id=?",
            (question["id"], question["author_id"]),
        ).fetchone()

    def ensure_question_task(self, db, question):
        task = self.create_personal_task(
            db, question["author_id"], question["client_request_id"], question["transcript"]
        )
        if task["question_id"]:
            return task
        status = {
            "waiting": "waiting",
            "answered": "responded",
            "cancelled": "cancelled",
            "expired": "timed_out",
        }.get(question["question_status"], "querying")
        deadline = (
            self.information_expiry(question["published_at"], self.settings.audio_ttl)
            if status == "responded"
            else question["expires_at"] or self.clock()
        )
        db.execute(
            "UPDATE personal_tasks SET question_id=?,conversation_id=?,last_request_message_id=?,"
            "kind='human_question',status=?,place_id=?,expires_at=?,created_at=? WHERE id=?",
            (
                question["id"],
                question["id"],
                question["id"],
                status,
                question["target_place_id"],
                deadline,
                question["published_at"] or question["created_at"],
                task["id"],
            ),
        )
        return self.task_row(db, question["author_id"], task["id"])

    def migrate_personal_tasks(self):
        # New rows only: old posts, identities, grants and audio are untouched.
        with self.scope() as db:
            for q in db.execute(
                "SELECT * FROM posts WHERE kind='question' AND status='published'"
            ).fetchall():
                self.ensure_question_task(db, q)

    def begin_task_turn(self, db, user, session, turn_id, data, state, reuse_context=False):
        from .agent import intent

        task = None
        pending = self.pending_agent(db, session["id"])
        if pending and intent(data.transcript) is None:
            task = db.execute(
                "SELECT t.* FROM personal_tasks t JOIN personal_task_turns x "
                "ON t.id=x.task_id WHERE x.turn_id=? AND t.user_id=?",
                (pending["turn_id"], user),
            ).fetchone()
        if task is None and reuse_context and state.get("context"):
            task = db.execute(
                "SELECT * FROM personal_tasks WHERE user_id=? AND conversation_id=? "
                "AND status NOT IN ('cancelled','resolved','timed_out','failed') "
                "ORDER BY updated_at DESC LIMIT 1",
                (user, state["context"]["root_id"]),
            ).fetchone()
        if task and task["status"] in TERMINAL:
            raise Problem(410, "TASK_CLOSED", "原任务已结束")
        if task is None:
            task = self.create_personal_task(
                db,
                user,
                data.client_request_id,
                data.transcript,
                session["id"],
                min(session["expires_at"], self.clock() + self.settings.exchange_turn_ttl),
            )
        db.execute("INSERT INTO personal_task_turns VALUES (?,?)", (task["id"], turn_id))
        return task

    def task_for_turn(self, db, user, turn_id):
        return db.execute(
            "SELECT t.* FROM personal_tasks t JOIN personal_task_turns x "
            "ON t.id=x.task_id WHERE x.turn_id=? AND t.user_id=?",
            (turn_id, user),
        ).fetchone()

    def require_live_turn_task(self, db, user, turn_id):
        task = self.task_for_turn(db, user, turn_id)
        if task and task["status"] in TERMINAL:
            raise Problem(410, "TASK_CLOSED", "任务已结束，迟到结果不再执行")

    def task_on_message(self, db, post):
        if post["kind"] == "question":
            return self.ensure_question_task(db, post)
        root_id = self.exchange_root(db, post)
        if not root_id:
            return None
        root = self.post(db, root_id)
        meta, _ = self.voice_meta(db, post["id"])
        recipient = (
            root["author_id"] if post["kind"] == "answer" else (meta or {}).get("recipient_id")
        )
        task = db.execute(
            "SELECT * FROM personal_tasks WHERE user_id=? AND conversation_id=? "
            "AND last_request_message_id=? ORDER BY updated_at DESC LIMIT 1",
            (recipient, root_id, post["reply_to"]),
        ).fetchone()
        if task and task["status"] == "waiting":
            db.execute(
                "UPDATE personal_tasks SET status='responded',revision=revision+1,"
                "updated_at=?,expires_at=? WHERE id=?",
                (self.clock(), self.thread_deadline(db, root), task["id"]),
            )
        return task

    def settle_task_turn(self, db, user, turn_id, result):
        task = self.task_for_turn(db, user, turn_id)
        if not task or task["status"] in TERMINAL:
            return task["id"] if task else None
        status = {
            "clarifying": "clarifying",
            "responded": "responded",
            "chatting": "responded",
            "ignored": "cancelled",
            "deferred": "deferred",
            "published": "responded",
        }.get(result["status"], "querying")
        if result["status"] in ("deferred", "ignored") and task["status"] in (
            "waiting",
            "responded",
        ):
            status = task["status"]
        message = None
        if result.get("post_id") and not result["post_id"].startswith("chatm_"):
            message = self.post(db, result["post_id"])
        root_id = result.get("root_id") or task["conversation_id"]
        deadline = task["expires_at"]
        last_request = task["last_request_message_id"]
        if message:
            root = self.post(db, root_id) if root_id else message
            deadline = (
                self.thread_deadline(db, root)
                if root["kind"] in ("question", "scene")
                else deadline
            )
            if message["kind"] == "question" or (
                message["kind"] == "reply" and root["author_id"] == user
            ):
                status, last_request = "waiting", message["id"]
                if message["kind"] == "question":
                    deadline = message["expires_at"]
        elif status == "responded":
            deadline = self.clock() + self.settings.audio_ttl
        db.execute(
            "UPDATE personal_tasks SET status=?,revision=revision+1,reason_code=?,result_json=?,"
            "conversation_id=?,last_request_message_id=?,updated_at=?,expires_at=? WHERE id=?",
            (
                status,
                result.get("reason"),
                json.dumps(result, ensure_ascii=False),
                root_id,
                last_request,
                self.clock(),
                deadline,
                task["id"],
            ),
        )
        if result.get("target_place"):
            db.execute(
                "UPDATE personal_tasks SET place_id=? WHERE id=?",
                (result["target_place"]["id"], task["id"]),
            )
        return task["id"]

    def sync_personal_tasks(self, db):
        db.execute(
            "UPDATE personal_tasks SET status='timed_out',reason_code='DEADLINE_EXCEEDED',"
            "revision=revision+1,updated_at=? WHERE expires_at<=? "
            "AND status NOT IN ('cancelled','resolved','timed_out','failed')",
            (self.clock(), self.clock()),
        )
        db.execute(
            "UPDATE personal_tasks SET status='cancelled',reason_code='QUESTION_CANCELLED',"
            "revision=revision+1,updated_at=? WHERE question_id IN "
            "(SELECT id FROM posts WHERE question_status='cancelled') "
            "AND status NOT IN ('cancelled','resolved','timed_out','failed')",
            (self.clock(),),
        )
        db.execute(
            "UPDATE personal_tasks SET status='deferred',reason_code='CLARIFICATION_ENDED',revision=revision+1,updated_at=? "
            "WHERE status='clarifying' AND NOT EXISTS (SELECT 1 FROM personal_task_turns t "
            "JOIN agent_pending p ON p.turn_id=t.turn_id WHERE t.task_id=personal_tasks.id)",
            (self.clock(),),
        )

    def conversation_task_closed(self, db, root):
        return bool(
            db.execute(
                "SELECT 1 FROM personal_tasks WHERE user_id=? AND conversation_id=? "
                "AND status IN ('cancelled','resolved','timed_out','failed')",
                (root["author_id"], root["id"]),
            ).fetchone()
        )

    def task_delivery_gate(self, db, delivery):
        message = self.post(db, delivery["post_id"])
        if message["kind"] in ("scene", "announcement", "knowledge"):
            return None
        root = self.post(db, delivery["question_id"])
        return "PERSONAL_TASK_CLOSED" if self.conversation_task_closed(db, root) else None

    def personal_task_view(self, db, user, task_id):
        from .service import stamp

        row = self.task_row(db, user, task_id)
        result = json.loads(row["result_json"] or "{}")
        sources = []
        for source in (result.get("agent_response") or {}).get("sources", []):
            item = dict(source)
            if item.get("post_id"):
                item["currently_valid"] = self.validate_information(
                    db, user, [item["post_id"]], item.get("retrieval_purpose", "all")
                )["valid"]
            else:
                from .voices import epoch

                item["currently_valid"] = bool(
                    item.get("valid_until") and epoch(item["valid_until"]) > self.clock()
                )
            sources.append(item)
        deliveries = [
            dict(r)
            for r in db.execute(
                "SELECT id,post_id,state,played_at FROM deliveries WHERE question_id=? AND recipient_id=? "
                "AND type='audio' ORDER BY created_at",
                (row["conversation_id"], user),
            )
        ]
        return {
            "id": row["id"],
            "revision": row["revision"],
            "request_id": row["request_id"],
            "kind": row["kind"],
            "status": row["status"],
            "original_text": row["original_text"],
            "place_id": row["place_id"],
            "question_id": row["question_id"],
            "conversation_id": row["conversation_id"],
            "reason_code": row["reason_code"],
            "created_at": stamp(row["created_at"]),
            "updated_at": stamp(row["updated_at"]),
            "expires_at": stamp(row["expires_at"]),
            "sources": sources,
            "deliveries": deliveries,
            "last_result": result,
            "server_time": stamp(self.clock()),
        }

    def personal_tasks(self, db, user, limit=50):
        rows = db.execute(
            "SELECT id FROM personal_tasks WHERE user_id=? ORDER BY updated_at DESC,id LIMIT ?",
            (user, min(max(limit, 1), 100)),
        ).fetchall()
        return {"items": [self.personal_task_view(db, user, r["id"]) for r in rows], **self.now()}

    def request_result(self, db, user, request_id):
        try:
            request_id = str(UUID(str(request_id)))
        except ValueError as exc:
            raise Problem(422, "INVALID_REQUEST_ID", "请求ID必须为UUID") from exc
        turn = db.execute(
            "SELECT id FROM exchange_turns WHERE user_id=? AND client_request_id=?",
            (user, request_id),
        ).fetchone()
        if turn:
            task = self.task_for_turn(db, user, turn["id"])
            return {
                "request_id": request_id,
                "turn": self.exchange_turn_view(db, user, turn["id"]),
                "task": self.personal_task_view(db, user, task["id"]) if task else None,
                **self.now(),
            }
        post = db.execute(
            "SELECT * FROM posts WHERE author_id=? AND client_request_id=?", (user, request_id)
        ).fetchone()
        if post:
            return {"request_id": request_id, "post": self.draft_view(post), **self.now()}
        raise Problem(404, "REQUEST_NOT_FOUND", "尚未查到该请求；不会自动重新提交")

    def change_personal_task(self, db, user, task_id, expected_revision, client_request_id, action):
        try:
            client_request_id = str(UUID(str(client_request_id)))
        except ValueError as exc:
            raise Problem(422, "INVALID_REQUEST_ID", "动作请求ID必须为UUID") from exc
        task = self.task_row(db, user, task_id)
        fingerprint = digest(json.dumps([task_id, expected_revision, action]))
        previous = db.execute(
            "SELECT * FROM personal_task_commands WHERE user_id=? AND request_id=?",
            (user, str(client_request_id)),
        ).fetchone()
        if previous:
            if previous["fingerprint"] != fingerprint:
                raise Problem(409, "IDEMPOTENCY_CONFLICT", "同一任务动作ID不能改变内容")
            return self.personal_task_view(db, user, task_id)
        if action not in ("resolve", "cancel"):
            raise Problem(422, "INVALID_ACTION", "不支持此任务动作")
        if task["revision"] != expected_revision:
            raise Problem(409, "REVISION_CONFLICT", "任务已变化，请刷新")
        if task["status"] in TERMINAL:
            raise Problem(410, "TASK_CLOSED", "任务已结束")
        status = "resolved" if action == "resolve" else "cancelled"
        db.execute(
            "UPDATE personal_tasks SET status=?,reason_code='USER_ACTION',revision=revision+1,"
            "updated_at=? WHERE id=?",
            (status, self.clock(), task_id),
        )
        if task["question_id"]:
            db.execute(
                "UPDATE posts SET question_status='cancelled' WHERE id=? AND question_status='waiting'",
                (task["question_id"],),
            )
        if task["conversation_id"]:
            root = self.post(db, task["conversation_id"])
            recipient_filter = "" if root["author_id"] == user else " AND recipient_id=?"
            args = (
                (task["conversation_id"],)
                if root["author_id"] == user
                else (task["conversation_id"], user)
            )
            if root["kind"] == "scene":
                recipient_filter += " AND post_id!=?"
                args += (root["id"],)
            db.execute(
                "UPDATE deliveries SET state='cancelled',reason_code='PERSONAL_TASK_CLOSED' "
                "WHERE question_id=? AND state IN ('pending','prompted')" + recipient_filter,
                args,
            )
        db.execute(
            "DELETE FROM agent_pending WHERE turn_id IN "
            "(SELECT turn_id FROM personal_task_turns WHERE task_id=?)",
            (task_id,),
        )
        db.execute(
            "INSERT INTO personal_task_commands VALUES (?,?,?,?)",
            (user, str(client_request_id), task_id, fingerprint),
        )
        return self.personal_task_view(db, user, task_id)

    def route_question(self, db, question):
        if question["question_status"] != "waiting" or question["expires_at"] <= self.clock():
            return
        task = self.question_task(db, question)
        if task and task["status"] in TERMINAL:
            return
        old = db.execute(
            "SELECT * FROM question_routes WHERE question_id=?", (question["id"],)
        ).fetchone()
        if old and old["last_batch_at"] + self.settings.invitation_cooldown_seconds > self.clock():
            return
        used = db.execute(
            "SELECT count(*) FROM deliveries WHERE question_id=? AND type='invitation'",
            (question["id"],),
        ).fetchone()[0]
        active = db.execute(
            "SELECT count(*) FROM deliveries WHERE question_id=? AND type='invitation' "
            "AND state IN ('pending','prompted')",
            (question["id"],),
        ).fetchone()[0]
        budget = min(
            self.settings.invitation_max_recipients - used,
            self.settings.invitation_batch_size - active,
        )
        if budget <= 0:
            return
        created = 0
        for profile in db.execute(
            "SELECT * FROM profiles WHERE user_id!=? ORDER BY user_id", (question["author_id"],)
        ).fetchall():
            seen = db.execute(
                "SELECT 1 FROM deliveries WHERE question_id=? AND recipient_id=? AND type='invitation'",
                (question["id"], profile["user_id"]),
            ).fetchone()
            if seen or self.eligibility(profile, question, db) != "ELIGIBLE":
                continue
            self.create_delivery(
                db,
                profile["user_id"],
                question["id"],
                question["id"],
                "invitation",
                min(
                    question["expires_at"],
                    self.information_expiry(self.clock(), self.settings.invitation_ttl),
                ),
            )
            created += 1
            budget -= 1
            if budget <= 0:
                break
        if created:
            db.execute(
                "INSERT INTO question_routes(question_id,last_batch_at) VALUES (?,?) "
                "ON CONFLICT(question_id) DO UPDATE SET last_batch_at=excluded.last_batch_at",
                (question["id"], self.clock()),
            )
