import json
import secrets
import time
from contextlib import contextmanager
from datetime import UTC, datetime

from .audio import validate_wav
from .config import Settings
from .errors import Problem
from .models import AnswerDraft, Context
from .storage import Database, digest


def stamp(value):
    return (
        datetime.fromtimestamp(value, UTC).isoformat().replace("+00:00", "Z")
        if value is not None
        else None
    )


def ident(prefix):
    return f"{prefix}_{secrets.token_hex(12)}"


class Service:
    def __init__(self, settings: Settings, clock=time.time):
        self.settings = settings
        self.clock = clock
        self.store = Database(settings.data_dir)
        self.feeds = {}

    @contextmanager
    def scope(self):
        with self.store.transaction() as db:
            self.expire(db)
            yield db

    def now(self):
        return {"server_time": stamp(self.clock())}

    def seed(self):
        s = self.settings
        if min(len(s.alice_token), len(s.bob_token)) < 32 or s.alice_token == s.bob_token:
            raise ValueError(
                "Provide distinct SONAR_ALICE_TOKEN / SONAR_BOB_TOKEN of at least 32 characters"
            )
        with self.scope() as db:
            for user, token, device, agent, place in [
                ("alice", s.alice_token, s.alice_device_id, s.alice_agent_id, "demo_approach"),
                ("bob", s.bob_token, s.bob_device_id, s.bob_agent_id, "zhonghuan_jinke"),
            ]:
                db.execute(
                    "INSERT INTO users VALUES (?,?,?,?) ON CONFLICT(id) DO UPDATE SET token_hash=excluded.token_hash, device_id=excluded.device_id, agent_id=excluded.agent_id",
                    (user, digest(token), device, agent),
                )
                db.execute(
                    "INSERT OR IGNORE INTO profiles VALUES (?,?,?,?,?,?,?,?,?)",
                    (
                        user,
                        place,
                        "demo_exit",
                        "simulated",
                        self.clock(),
                        1,
                        "listen",
                        "愿意回答附近路口的拥堵问题",
                        1,
                    ),
                )

    def auth(self, db, authorization):
        if not authorization or not authorization.startswith("Bearer "):
            raise Problem(401, "UNAUTHORIZED", "需要设备凭证")
        row = db.execute(
            "SELECT * FROM users WHERE token_hash=?", (digest(authorization[7:]),)
        ).fetchone()
        if not row:
            raise Problem(401, "UNAUTHORIZED", "设备凭证无效")
        return row["id"]

    def profile_row(self, db, user):
        return db.execute("SELECT * FROM profiles WHERE user_id=?", (user,)).fetchone()

    def profile(self, db, user):
        p = self.profile_row(db, user)
        return {
            "user_id": user,
            "presence": {
                "place_id": p["place_id"],
                "direction": p["direction"],
                "source": p["source"],
                "updated_at": stamp(p["updated_at"]),
            },
            "assist_enabled": bool(p["assist_enabled"]),
            "mode": p["mode"],
            "focus": p["focus"],
            "revision": p["revision"],
        }

    def me(self, db, user):
        u = db.execute("SELECT * FROM users WHERE id=?", (user,)).fetchone()
        ids = db.execute(
            "SELECT id FROM posts WHERE author_id=? AND kind='question' AND status='published' ORDER BY created_at DESC, id DESC LIMIT 10",
            (user,),
        ).fetchall()
        return {
            "profile": self.profile(db, user),
            "device_id": u["device_id"],
            "linx_agent_id": u["agent_id"],
            "recent_question_ids": [p["id"] for p in ids],
            **self.now(),
        }

    def context(self, db, user, data: Context, refresh_presence=True):
        old = self.profile(db, user)
        if old["revision"] != data.expected_revision:
            raise Problem(409, "REVISION_CONFLICT", "配置已改变，请重新读取")
        changed = (
            data.presence.model_dump()
            != {k: v for k, v in old["presence"].items() if k != "updated_at"}
            or data.assist_enabled != old["assist_enabled"]
            or data.mode != old["mode"]
            or data.focus != old["focus"]
        )
        revision = old["revision"] + int(changed)
        db.execute(
            "UPDATE profiles SET place_id=?,direction=?,source=?,updated_at=?,assist_enabled=?,mode=?,focus=?,revision=? WHERE user_id=?",
            (
                data.presence.place_id,
                data.presence.direction,
                data.presence.source,
                self.clock() if refresh_presence else self.profile_row(db, user)["updated_at"],
                data.assist_enabled,
                data.mode,
                data.focus,
                revision,
                user,
            ),
        )
        if changed:
            reason = (
                "MODE_QUIET"
                if data.mode == "quiet"
                else "ASSIST_DISABLED"
                if not data.assist_enabled
                else "CONTEXT_CHANGED"
            )
            db.execute(
                "UPDATE deliveries SET state='cancelled',reason_code=? WHERE recipient_id=? AND type='invitation' AND state IN ('pending','prompted')",
                (reason, user),
            )
            for d in db.execute(
                "SELECT id FROM deliveries WHERE recipient_id=? AND type='audio' AND state='pending'",
                (user,),
            ).fetchall():
                db.execute(
                    "UPDATE deliveries SET profile_revision=?,request_id=? WHERE id=?",
                    (revision, ident("r"), d["id"]),
                )
        return {"profile": self.profile(db, user), **self.now()}

    def eligibility(self, p, question):
        if p["mode"] == "quiet":
            return "MODE_QUIET"
        if not p["assist_enabled"]:
            return "ASSIST_DISABLED"
        if p["updated_at"] + self.settings.presence_ttl <= self.clock():
            return "PRESENCE_EXPIRED"
        if p["place_id"] != question["target_place_id"] or p["direction"] != question["direction"]:
            return "LOCATION_MISMATCH"
        return "ELIGIBLE"

    def expire(self, db):
        now = self.clock()
        db.execute(
            "UPDATE posts SET question_status='expired' WHERE question_status='waiting' AND expires_at<=?",
            (now,),
        )
        db.execute(
            "UPDATE deliveries SET state='expired',reason_code='TASK_EXPIRED' WHERE state IN ('pending','prompted') AND expires_at<=?",
            (now,),
        )
        rows = db.execute(
            "SELECT d.*,p.question_status FROM deliveries d JOIN posts p ON p.id=d.question_id WHERE d.type='invitation' AND d.state IN ('pending','prompted')"
        ).fetchall()
        for d in rows:
            q = self.post(db, d["question_id"])
            reason = self.eligibility(self.profile_row(db, d["recipient_id"]), q)
            if q["question_status"] != "waiting":
                reason = "TASK_EXPIRED" if q["question_status"] == "expired" else "TASK_CANCELLED"
            if reason != "ELIGIBLE":
                db.execute(
                    "UPDATE deliveries SET state=?,reason_code=? WHERE id=?",
                    ("expired" if reason == "TASK_EXPIRED" else "cancelled", reason, d["id"]),
                )

        # Listening to an original question is authorized by its live invitation.
        # Cancelling that invitation must also invalidate any separately queued question audio.
        db.execute(
            "UPDATE deliveries SET state='cancelled',reason_code='TASK_CANCELLED' "
            "WHERE type='audio' AND state='pending' "
            "AND post_id IN (SELECT id FROM posts WHERE kind='question') "
            "AND NOT EXISTS (SELECT 1 FROM deliveries i WHERE i.type='invitation' "
            "AND i.recipient_id=deliveries.recipient_id AND i.post_id=deliveries.post_id "
            "AND i.state IN ('pending','prompted'))"
        )

    def post(self, db, pid):
        row = db.execute("SELECT * FROM posts WHERE id=?", (pid,)).fetchone()
        if not row:
            raise Problem(404, "NOT_FOUND", "内容不存在")
        return row

    def delivery_row(self, db, user, did):
        row = db.execute(
            "SELECT * FROM deliveries WHERE id=? AND recipient_id=?", (did, user)
        ).fetchone()
        if not row:
            raise Problem(404, "NOT_FOUND", "任务不存在")
        return row

    def valid_invitation(self, db, user, did, qid):
        d = self.delivery_row(db, user, did)
        if d["type"] != "invitation" or d["question_id"] != qid:
            raise Problem(409, "INVITATION_MISMATCH", "回复与邀请不匹配")
        if d["state"] not in ("pending", "prompted"):
            raise Problem(
                410 if d["state"] == "expired" else 409, "INVITATION_INACTIVE", "邀请已结束"
            )
        return self.post(db, qid)

    def draft(self, db, user, meta, audio):
        validate_wav(audio)
        fields = meta.model_dump(mode="json")
        fingerprint = digest(
            json.dumps(fields, sort_keys=True, ensure_ascii=False).encode() + audio
        )
        old = db.execute(
            "SELECT * FROM posts WHERE author_id=? AND client_request_id=?",
            (user, str(meta.client_request_id)),
        ).fetchone()
        if old:
            if old["fingerprint"] != fingerprint:
                raise Problem(409, "IDEMPOTENCY_CONFLICT", "同一录音ID不能对应不同内容")
            return self.draft_view(old), False
        if isinstance(meta, AnswerDraft):
            q = self.valid_invitation(db, user, meta.invitation_id, meta.reply_to)
            place, direction = q["target_place_id"], q["direction"]
        else:
            place, direction = meta.target_place_id, meta.direction
        pid = ident("p")
        db.execute(
            "INSERT INTO posts (id,author_id,client_request_id,fingerprint,kind,transcript,target_place_id,direction,reply_to,invitation_id,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            (
                pid,
                user,
                str(meta.client_request_id),
                fingerprint,
                meta.kind,
                meta.transcript,
                place,
                direction,
                fields.get("reply_to"),
                fields.get("invitation_id"),
                self.clock(),
            ),
        )
        # Generated names, never user-controlled paths. File exists before transaction commits.
        path = self.store.audio_dir / f"{pid}.wav"
        try:
            path.write_bytes(audio)
        except OSError:
            path.unlink(missing_ok=True)
            raise
        return self.draft_view(self.post(db, pid)), True

    def draft_view(self, p):
        return {
            "post_id": p["id"],
            "status": p["status"],
            "transcript": p["transcript"],
            "audio_url": f"/v1/audio/{p['id']}",
            **self.now(),
        }

    def create_delivery(self, db, recipient, qid, pid, kind, ttl):
        db.execute(
            "INSERT OR IGNORE INTO deliveries (id,recipient_id,question_id,post_id,type,request_id,profile_revision,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?)",
            (
                ident("d"),
                recipient,
                qid,
                pid,
                kind,
                ident("r"),
                self.profile_row(db, recipient)["revision"],
                self.clock(),
                ttl,
            ),
        )
        return db.execute(
            "SELECT * FROM deliveries WHERE recipient_id=? AND post_id=? AND type=?",
            (recipient, pid, kind),
        ).fetchone()

    def confirm(self, db, user, pid):
        p = self.post(db, pid)
        if p["author_id"] != user:
            raise Problem(404, "NOT_FOUND", "草稿不存在")
        if p["status"] != "published":
            if p["kind"] == "question":
                waiting = db.execute(
                    "SELECT id FROM posts WHERE author_id=? AND question_status='waiting'", (user,)
                ).fetchone()
                if waiting:
                    raise Problem(409, "QUESTION_ACTIVE", "请先结束当前提问")
                db.execute(
                    "UPDATE posts SET status='published',question_status='waiting',published_at=?,expires_at=? WHERE id=?",
                    (self.clock(), self.clock() + self.settings.question_ttl, pid),
                )
                q = self.post(db, pid)
                for profile in db.execute(
                    "SELECT * FROM profiles WHERE user_id!=?", (user,)
                ).fetchall():
                    if self.eligibility(profile, q) == "ELIGIBLE":
                        self.create_delivery(
                            db,
                            profile["user_id"],
                            pid,
                            pid,
                            "invitation",
                            min(q["expires_at"], self.clock() + self.settings.invitation_ttl),
                        )
            else:
                q = self.valid_invitation(db, user, p["invitation_id"], p["reply_to"])
                if q["question_status"] != "waiting":
                    raise Problem(409, "QUESTION_CLOSED", "提问已结束")
                db.execute(
                    "UPDATE posts SET status='published',published_at=? WHERE id=?",
                    (self.clock(), pid),
                )
                db.execute("UPDATE posts SET question_status='answered' WHERE id=?", (q["id"],))
                db.execute(
                    "UPDATE deliveries SET state='cancelled',reason_code='TASK_CANCELLED' WHERE question_id=? AND type='invitation' AND state IN ('pending','prompted')",
                    (q["id"],),
                )
                db.execute(
                    "UPDATE deliveries SET state='answered',reason_code='ALREADY_HANDLED' WHERE id=?",
                    (p["invitation_id"],),
                )
                self.create_delivery(
                    db,
                    q["author_id"],
                    q["id"],
                    pid,
                    "audio",
                    self.clock() + self.settings.audio_ttl,
                )
        p = self.post(db, pid)
        q = p if p["kind"] == "question" else self.post(db, p["reply_to"])
        return {
            "post_id": pid,
            "status": p["status"],
            "question_id": q["id"],
            "question_status": q["question_status"],
            **self.now(),
        }

    def question(self, db, user, qid):
        q = self.post(db, qid)
        if q["kind"] != "question" or q["status"] != "published" or q["author_id"] != user:
            raise Problem(404, "NOT_FOUND", "提问不存在")
        answer = db.execute(
            "SELECT * FROM posts WHERE reply_to=? AND status='published'", (qid,)
        ).fetchone()
        invite = db.execute(
            "SELECT state FROM deliveries WHERE question_id=? AND type='invitation' ORDER BY created_at LIMIT 1",
            (qid,),
        ).fetchone()
        return {
            "question_id": qid,
            "status": q["question_status"],
            "expires_at": stamp(q["expires_at"]),
            "answer": self.audio_payload(answer) if answer else None,
            "invite_outcome": invite["state"] if invite else "none",
            **self.now(),
        }

    def cancel(self, db, user, qid):
        q = self.question(db, user, qid)
        if q["status"] == "answered":
            raise Problem(409, "QUESTION_ANSWERED", "已有回答，可跳过音频")
        if q["status"] == "expired":
            raise Problem(410, "QUESTION_EXPIRED", "问题已过期")
        db.execute("UPDATE posts SET question_status='cancelled' WHERE id=?", (qid,))
        db.execute(
            "UPDATE deliveries SET state='cancelled',reason_code='TASK_CANCELLED' WHERE question_id=? AND state IN ('pending','prompted')",
            (qid,),
        )
        return self.question(db, user, qid)

    def audio_payload(self, p):
        return {
            "post_id": p["id"],
            "transcript": p["transcript"],
            "author_label": f"参与者 {p['author_id']}",
            "published_at": stamp(p["published_at"]),
            "audio_url": f"/v1/audio/{p['id']}",
        }

    def delivery(self, db, user, did):
        d = self.delivery_row(db, user, did)
        p = self.post(db, d["post_id"])
        reason = d["reason_code"]
        actionable = d["state"] == "pending"
        if not actionable:
            reason = reason if reason != "ELIGIBLE" else "ALREADY_HANDLED"
        elif self.profile_row(db, user)["mode"] == "quiet":
            actionable, reason = False, "MODE_QUIET"
        payload = (
            self.audio_payload(p)
            if d["type"] == "audio"
            else {
                "transcript": p["transcript"],
                "place_id": p["target_place_id"],
                "place_name": "中环路金科路口"
                if p["target_place_id"] == "zhonghuan_jinke"
                else "模拟接近区域",
                "direction": p["direction"],
                "prompt_key": "onsite_question",
                "question_audio_url": f"/v1/audio/{p['id']}",
            }
        )
        return {
            "id": did,
            "type": d["type"],
            "question_id": d["question_id"],
            "request_id": d["request_id"],
            "profile_revision": d["profile_revision"],
            "state": d["state"],
            "expires_at": stamp(d["expires_at"]),
            "actionable": actionable,
            "reason_code": reason,
            "payload": payload,
        }

    def inbox(self, db, user):
        rows = db.execute(
            "SELECT id FROM deliveries WHERE recipient_id=? AND state='pending' ORDER BY created_at,id LIMIT 10",
            (user,),
        ).fetchall()
        items = [self.delivery(db, user, d["id"]) for d in rows]
        return {
            "items": [d for d in items if d["type"] == "audio" or d["actionable"]],
            **self.now(),
        }

    def ack(self, db, user, did, event, request_id):
        d = self.delivery_row(db, user, did)
        if d["request_id"] != request_id:
            raise Problem(409, "REQUEST_CONFLICT", "播放请求已改变")
        allowed = {
            "invitation": {"alerted": "prompted", "declined": "declined"},
            "audio": {"played": "played", "skipped": "skipped"},
        }
        target = allowed[d["type"]].get(event)
        if event != "failed" and target is None:
            raise Problem(409, "INVALID_TRANSITION", "此任务不能使用该回执")
        if target != d["state"] and event != "failed":
            if d["state"] not in ("pending", "prompted") or (
                d["state"] == "prompted" and event != "declined"
            ):
                raise Problem(409, "INVALID_TRANSITION", "任务已结束")
            db.execute("UPDATE deliveries SET state=? WHERE id=?", (target, did))
        return {"delivery": self.delivery(db, user, did), **self.now()}

    def can_read_audio(self, db, user, p):
        if p["author_id"] == user:
            return True
        if p["status"] != "published":
            return False
        if p["kind"] == "answer":
            q = self.post(db, p["reply_to"])
            return (
                q["author_id"] == user
                and p["published_at"] + self.settings.audio_ttl > self.clock()
            )
        d = db.execute(
            "SELECT id FROM deliveries WHERE recipient_id=? AND post_id=? AND type='invitation' AND state IN ('pending','prompted')",
            (user, p["id"]),
        ).fetchone()
        return bool(d)

    def get_feed(self, db, user):
        now = self.clock()
        self.feeds = {key: value for key, value in self.feeds.items() if value["expires"] > now}
        candidates = []
        rows = db.execute(
            "SELECT * FROM posts WHERE status='published' ORDER BY published_at DESC,id DESC LIMIT 100"
        ).fetchall()
        for p in rows:
            if p["author_id"] == user or not self.can_read_audio(db, user, p):
                continue
            candidates.append(self.audio_payload(p))
            if len(candidates) >= 10:
                break
        request_id = ident("feed")
        revision = self.profile_row(db, user)["revision"]
        self.feeds[request_id] = {
            "user": user,
            "revision": revision,
            "expires": now + 30,
            "posts": {c["post_id"] for c in candidates},
        }
        me = self.me(db, user)
        return {
            "request_id": request_id,
            "profile_revision": revision,
            "expires_at": stamp(now + 30),
            "profile": me["profile"],
            "tasks": [self.question(db, user, qid) for qid in me["recent_question_ids"]],
            "candidates": candidates,
        }

    def set_focus(self, db, user, expected_revision, mode=None, assist_enabled=None, focus=None):
        p = self.profile(db, user)
        data = Context(
            expected_revision=expected_revision,
            presence={k: v for k, v in p["presence"].items() if k != "updated_at"},
            mode=mode if mode is not None else p["mode"],
            assist_enabled=assist_enabled if assist_enabled is not None else p["assist_enabled"],
            focus=focus if focus is not None else p["focus"],
        )
        # A voice preference command must not refresh stale physical presence.
        return self.context(db, user, data, refresh_presence=False)

    def queue_voice(self, db, user, request_id, profile_revision, post_id):
        snapshot = self.feeds.get(request_id)
        if not snapshot or snapshot["expires"] <= self.clock():
            raise Problem(410, "FEED_EXPIRED", "请重新查询声音")
        if snapshot["user"] != user or post_id not in snapshot["posts"]:
            raise Problem(403, "CANDIDATE_FORBIDDEN", "不属于本人本轮候选")
        p = self.profile_row(db, user)
        if p["revision"] != profile_revision or snapshot["revision"] != profile_revision:
            raise Problem(409, "REVISION_CONFLICT", "个人配置已改变")
        if p["mode"] == "quiet":
            raise Problem(409, "MODE_QUIET", "当前处于安静模式")
        post = self.post(db, post_id)
        if not self.can_read_audio(db, user, post):
            raise Problem(410, "CANDIDATE_EXPIRED", "声音已不可访问")
        qid = post["reply_to"] or post["id"]
        if post["kind"] == "answer":
            expires = post["published_at"] + self.settings.audio_ttl
        else:
            invite = db.execute(
                "SELECT expires_at FROM deliveries WHERE recipient_id=? AND post_id=? AND type='invitation' AND state IN ('pending','prompted')",
                (user, post_id),
            ).fetchone()
            expires = min(post["expires_at"], invite["expires_at"])
        d = self.create_delivery(db, user, qid, post_id, "audio", expires)
        return {"delivery_id": d["id"], "state": d["state"]}
