import json
import secrets
import time
import wave
from contextlib import asynccontextmanager, contextmanager
from datetime import UTC, datetime

from .audio import validate_wav
from .config import Settings
from .decision import BambooDecision
from .errors import Problem
from .exchange import ExchangeDecider, ExchangeRules
from .geo import AMap, distance_m, sampled_epoch
from .information import InformationRules
from .message_views import MessageViews
from .models import AnswerDraft, Context, QuestionDraft, ReplyDraft, VoiceDraft
from .remote_places import RemotePlaceRules
from .storage import Database, digest
from .tasks import TaskRules
from .voice_first import VoiceIntentDecider
from .voices import VoiceRules


def stamp(value):
    return (
        datetime.fromtimestamp(value, UTC).isoformat().replace("+00:00", "Z")
        if value is not None
        else None
    )


def ident(prefix):
    return f"{prefix}_{secrets.token_hex(12)}"


class Service(
    RemotePlaceRules, ExchangeRules, VoiceRules, MessageViews, InformationRules, TaskRules
):
    def __init__(self, settings: Settings, clock=time.time):
        self.settings = settings
        self.clock = clock
        self.store = Database(settings.data_dir)
        self.feeds = {}
        self.geo = AMap(settings.amap_key)
        self.decider = BambooDecision(settings.bamboo_api_key, settings.bamboo_base_url)
        self.exchange_decider = ExchangeDecider(settings)
        self.voice_intent = VoiceIntentDecider(settings)
        self.migrate_legacy_focus()
        self.migrate_personal_tasks()
        self.register_pilot_places()

    @contextmanager
    def scope(self):
        with self.store.transaction() as db:
            self.expire(db)
            yield db

    @asynccontextmanager
    async def async_scope(self):
        async with self.store.async_transaction() as db:
            self.expire(db)
            yield db

    def now(self):
        return {"server_time": stamp(self.clock())}

    def migrate_legacy_focus(self):
        # The old Android quiet toggle wrote this status into the semantic focus.
        # One-time repair only: later explicit user preferences are never rewritten.
        with self.scope() as db:
            marker = "attention_legacy_quiet_focus_v1"
            if db.execute("SELECT 1 FROM schema_migrations WHERE id=?", (marker,)).fetchone():
                return
            for p in db.execute("SELECT * FROM profiles WHERE focus='现在安静'").fetchall():
                self.context(
                    db,
                    p["user_id"],
                    Context(
                        expected_revision=p["revision"],
                        mode=p["mode"],
                        assist_enabled=bool(p["assist_enabled"]),
                        focus="愿意回答附近路口的拥堵问题",
                    ),
                    refresh_presence=False,
                )
            db.execute("INSERT INTO schema_migrations VALUES (?,?)", (marker, self.clock()))

    def seed(self):
        s = self.settings
        if min(len(s.alice_token), len(s.bob_token)) < 32 or s.alice_token == s.bob_token:
            raise ValueError(
                "Provide distinct SONAR_ALICE_TOKEN / SONAR_BOB_TOKEN of at least 32 characters"
            )
        with self.scope() as db:
            db.execute(
                "INSERT OR IGNORE INTO places VALUES (?,?,?,?,?,?)",
                ("private:unlocated", "位置待更新", 0, 0, "area", self.clock()),
            )
            for user, token, device, agent, place in [
                ("alice", s.alice_token, s.alice_device_id, s.alice_agent_id, "private:unlocated"),
                ("bob", s.bob_token, s.bob_device_id, s.bob_agent_id, "private:unlocated"),
            ]:
                db.execute(
                    "INSERT INTO users VALUES (?,?,?,?) ON CONFLICT(id) DO UPDATE SET token_hash=excluded.token_hash, device_id=excluded.device_id, agent_id=excluded.agent_id",
                    (user, digest(token), device, agent),
                )
                db.execute(
                    "INSERT OR IGNORE INTO profiles (user_id,place_id,direction,source,updated_at,assist_enabled,mode,focus,revision) VALUES (?,?,?,?,?,?,?,?,?)",
                    (
                        user,
                        place,
                        "unknown",
                        "unavailable",
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
                **({"remote": True} if self.is_remote_place(p) else {}),
                "updated_at": stamp(p["updated_at"]),
                **(
                    {
                        "location": json.loads(p["location_json"]),
                        "place_name": self.place_label(db, p["place_id"]),
                    }
                    if p["location_json"]
                    else {}
                ),
            },
            "assist_enabled": bool(p["assist_enabled"]),
            "mode": p["mode"],
            "focus": p["focus"],
            "revision": p["revision"],
            "listening": self.listening(db, user),
        }

    def me(self, db, user):
        pinned = self.remote_place_pin(db, user)
        if pinned:
            data, resolved = self.remote_location(
                db, pinned, self.profile_row(db, user)["revision"]
            )
            self.update_location(db, user, data, resolved)
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
            "place_shortcuts": self.place_shortcuts(db),
            **self.now(),
        }

    def place_label(self, db, place_id):
        row = db.execute("SELECT label FROM places WHERE id=?", (place_id,)).fetchone()
        return row["label"] if row else "位置待更新"

    def check_fix(self, fix):
        age = self.clock() - sampled_epoch(fix)
        if age < -10 or age >= self.settings.location_ttl:
            raise Problem(422, "LOCATION_STALE", "定位已过期，请重新定位")

    def update_location(self, db, user, data, resolved):
        pinned = self.remote_place_pin(db, user)
        if pinned:
            data, resolved = self.remote_location(db, pinned, data.expected_revision)
        elif not data.location or data.location.source != "remote_place":
            db.execute(
                "UPDATE remote_place_choices SET place_id=NULL,selected_at=? WHERE user_id=?",
                (self.clock(), user),
            )
        old = self.profile_row(db, user)
        if old["revision"] != data.expected_revision:
            raise Problem(409, "REVISION_CONFLICT", "设置已更新，请重试定位")
        if data.location:
            self.check_fix(data.location)
            for place in resolved["places"]:
                db.execute(
                    "INSERT INTO places VALUES (?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET label=excluded.label",
                    (
                        place["id"],
                        place["label"],
                        place["latitude"],
                        place["longitude"],
                        place["kind"],
                        self.clock(),
                    ),
                )
            fix = data.location
            place_id = resolved["places"][0]["id"]
            source = fix.source
            location_json = fix.model_dump_json()
            latitude, longitude = resolved["latitude"], resolved["longitude"]
            sampled, accuracy = sampled_epoch(fix), fix.accuracy_m
        else:
            place_id, source = "unavailable", "unavailable"
            location_json = latitude = longitude = sampled = accuracy = None
        changed = old["place_id"] != place_id or old["source"] != source
        revision = old["revision"] + int(changed)
        db.execute(
            "UPDATE profiles SET place_id=?,direction='unknown',source=?,updated_at=?,revision=?,location_json=?,latitude=?,longitude=?,sampled_at=?,accuracy_m=? WHERE user_id=?",
            (
                place_id,
                source,
                self.clock(),
                revision,
                location_json,
                latitude,
                longitude,
                sampled,
                accuracy,
                user,
            ),
        )
        # Same immutable delivery content; location is revalidated before any playback.
        if changed:
            # Session location is the caller's conversational "here", never a question's
            # destination. Following GPS or a remote-place change must not retain the old here.
            here = place_id if data.location else "private:unlocated"
            db.execute(
                "INSERT OR IGNORE INTO places VALUES (?,?,?,?,?,?)",
                ("private:unlocated", "位置待更新", 0, 0, "area", self.clock()),
            )
            sessions = db.execute(
                "SELECT s.* FROM exchange_sessions s JOIN voice_sessions v ON v.session_id=s.id "
                "WHERE s.user_id=? AND s.state!='ended'",
                (user,),
            ).fetchall()
            for session in sessions:
                db.execute(
                    "UPDATE exchange_sessions SET place_id=?,context_post_id=NULL,revision=revision+1 WHERE id=?",
                    (here, session["id"]),
                )
                policy = self.voice_policy(db, session)
                policy.update(heard_post_id=None, heard_at=0)
                self.save_voice_policy(db, session["id"], policy)
            db.execute(
                "UPDATE deliveries SET profile_revision=? WHERE recipient_id=? AND state IN ('pending','prompted')",
                (revision, user),
            )
        self.expire(db)
        self.invite_nearby(db, user)
        return {
            "profile": self.profile(db, user),
            "places": resolved["places"] if resolved else [],
            **self.now(),
        }

    def invite_nearby(self, db, user):
        for q in db.execute(
            "SELECT * FROM posts WHERE question_status='waiting' AND author_id!=? AND target_latitude IS NOT NULL",
            (user,),
        ).fetchall():
            self.route_question(db, q)

    def context(self, db, user, data: Context, refresh_presence=True):
        old = self.profile(db, user)
        if old["revision"] != data.expected_revision:
            raise Problem(409, "REVISION_CONFLICT", "配置已改变，请重新读取")
        row = self.profile_row(db, user)
        presence = data.presence
        changed = (
            (
                presence is not None
                and presence.model_dump()
                != {k: old["presence"][k] for k in ("place_id", "direction", "source")}
            )
            or data.assist_enabled != old["assist_enabled"]
            or data.mode != old["mode"]
            or data.focus != old["focus"]
        )
        revision = old["revision"] + int(changed)
        db.execute(
            "UPDATE profiles SET place_id=?,direction=?,source=?,updated_at=?,assist_enabled=?,mode=?,focus=?,revision=? WHERE user_id=?",
            (
                presence.place_id if presence else row["place_id"],
                presence.direction if presence else row["direction"],
                presence.source if presence else row["source"],
                self.clock() if refresh_presence and presence else row["updated_at"],
                data.assist_enabled,
                data.mode,
                data.focus,
                revision,
                user,
            ),
        )
        if presence is not None:
            db.execute(
                "UPDATE profiles SET location_json=NULL,latitude=NULL,longitude=NULL,sampled_at=NULL,accuracy_m=NULL WHERE user_id=?",
                (user,),
            )
        if changed:
            focus_only = (
                data.focus != old["focus"]
                and data.mode == old["mode"]
                and data.assist_enabled == old["assist_enabled"]
                and (
                    presence is None
                    or presence.model_dump()
                    == {k: old["presence"][k] for k in ("place_id", "direction", "source")}
                )
            )
            reason = (
                "MODE_QUIET"
                if data.mode == "quiet"
                else "ASSIST_DISABLED"
                if not data.assist_enabled
                else "CONTEXT_CHANGED"
            )
            if focus_only:
                db.execute(
                    "UPDATE deliveries SET profile_revision=?,attention_json=NULL WHERE recipient_id=? "
                    "AND type='invitation' AND state IN ('pending','prompted')",
                    (revision, user),
                )
            else:
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

    def eligibility(self, p, question, db=None):
        if p["mode"] == "quiet":
            return "MODE_QUIET"
        if not (self.voice_invite_allowed(db, p) if db is not None else p["assist_enabled"]):
            return "ASSIST_DISABLED"
        if question["target_latitude"] is not None:
            if (
                p["source"] not in ("android", "android_mock") and not self.is_remote_place(p)
            ) or p["latitude"] is None:
                return "LOCATION_MISMATCH"
            if p["source"] == "android_mock" and not self.settings.allow_mock_location:
                return "LOCATION_UNTRUSTED"
            if p["sampled_at"] + self.settings.location_ttl <= self.clock():
                return "PRESENCE_EXPIRED"
            if p["accuracy_m"] > self.settings.location_accuracy_m:
                return "LOCATION_IMPRECISE"
            if db is not None and self.shared_park(db, p["place_id"], question["target_place_id"]):
                return "ELIGIBLE"
            if (
                distance_m(
                    p["latitude"],
                    p["longitude"],
                    question["target_latitude"],
                    question["target_longitude"],
                )
                + p["accuracy_m"]
                > self.settings.nearby_radius_m
            ):
                return "LOCATION_MISMATCH"
            return "ELIGIBLE"
        if p["source"] != "simulated":
            return "LOCATION_MISMATCH"
        if p["updated_at"] + self.settings.presence_ttl <= self.clock():
            return "PRESENCE_EXPIRED"
        if p["place_id"] != question["target_place_id"] or p["direction"] != question["direction"]:
            return "LOCATION_MISMATCH"
        return "ELIGIBLE"

    def expire(self, db):
        now = self.clock()
        self.expire_chats(db)
        db.execute(
            "UPDATE posts SET question_status='expired' WHERE question_status='waiting' AND expires_at<=?",
            (now,),
        )
        self.sync_personal_tasks(db)
        db.execute(
            "UPDATE deliveries SET state='expired',reason_code='TASK_EXPIRED' WHERE state IN ('pending','prompted') AND expires_at<=?",
            (now,),
        )
        rows = db.execute(
            "SELECT d.*,p.question_status FROM deliveries d JOIN posts p ON p.id=d.question_id WHERE d.type='invitation' AND d.state IN ('pending','prompted')"
        ).fetchall()
        for d in rows:
            q = self.post(db, d["question_id"])
            reason = self.eligibility(self.profile_row(db, d["recipient_id"]), q, db)
            if q["question_status"] != "waiting":
                reason = "TASK_EXPIRED" if q["question_status"] == "expired" else "TASK_CANCELLED"
            if reason != "ELIGIBLE":
                db.execute(
                    "UPDATE deliveries SET state=?,reason_code=? WHERE id=?",
                    ("expired" if reason == "TASK_EXPIRED" else "cancelled", reason, d["id"]),
                )

        for d in db.execute(
            "SELECT * FROM deliveries WHERE voice_json IS NOT NULL AND state='pending'"
        ).fetchall():
            p = self.post(db, d["post_id"])
            gate = self.voice_delivery_gate(db, d["recipient_id"], d, p)
            if gate in ("VOICE_INACTIVE", "OUTSIDE_SCOPE"):
                db.execute(
                    "UPDATE deliveries SET state='cancelled',reason_code=? WHERE id=?",
                    (gate, d["id"]),
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
        if not self.attention_approved(db, d):
            raise Problem(409, "ATTENTION_NOT_APPROVED", "这条邀请尚未通过个人筛选")
        return self.post(db, qid)

    def draft(self, db, user, meta, audio):
        validate_wav(audio)
        fields = meta.model_dump(mode="json", exclude_none=True)
        if isinstance(meta, VoiceDraft):
            # Preserve old immutable request fingerprints when new fields use legacy defaults.
            for key, default in (
                ("validity_policy", "bounded"),
                ("content_origin", "human_original"),
            ):
                if fields.get(key) == default:
                    fields.pop(key)
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
        elif isinstance(meta, ReplyDraft):
            q = self.check_reply(db, user, fields)
            place, direction = q["target_place_id"], q["direction"]
        else:
            place, direction = meta.target_place_id, getattr(meta, "direction", "unknown")
        target = None
        if not isinstance(meta, (AnswerDraft, ReplyDraft)) and place != "zhonghuan_jinke":
            target = db.execute("SELECT * FROM places WHERE id=?", (place,)).fetchone()
            if not target:
                raise Problem(422, "PLACE_UNKNOWN", "请重新选择询问地点")
            if direction != "unknown":
                raise Problem(422, "DIRECTION_UNKNOWN", "附近问答尚未验证道路方向")
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
        if isinstance(meta, AnswerDraft):
            db.execute(
                "UPDATE posts SET target_latitude=?,target_longitude=?,target_label=? WHERE id=?",
                (q["target_latitude"], q["target_longitude"], q["target_label"], pid),
            )
        elif target:
            db.execute(
                "UPDATE posts SET target_latitude=?,target_longitude=?,target_label=? WHERE id=?",
                (target["latitude"], target["longitude"], target["label"], pid),
            )
        if isinstance(meta, (QuestionDraft, AnswerDraft)):
            self.save_qa_source(db, user, self.post(db, pid), fields)
        if isinstance(meta, (VoiceDraft, ReplyDraft)):
            self.save_voice_draft(db, user, pid, fields)
        if self.is_remote_place(self.profile_row(db, user)):
            db.execute(
                "UPDATE posts SET recorded_location=? WHERE id=?",
                (self.profile_row(db, user)["location_json"], pid),
            )
        elif meta.recorded_location:
            fix = meta.recorded_location
            if sampled_epoch(fix) > self.clock() + 10:
                raise Problem(422, "LOCATION_TIME_INVALID", "录音位置时间无效")
            db.execute(
                "UPDATE posts SET recorded_location=? WHERE id=?", (fix.model_dump_json(), pid)
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

    def attention_key(self, profile, post):
        return digest(
            json.dumps(
                [
                    BambooDecision.policy_version,
                    BambooDecision.model,
                    profile["user_id"],
                    post["id"],
                    profile["focus"],
                    post["transcript"],
                    post["target_label"],
                ],
                ensure_ascii=False,
            )
        )

    def attention_record(self, delivery):
        try:
            value = json.loads(delivery["attention_json"] or "null")
            return value if isinstance(value, dict) else {}
        except (ValueError, TypeError):
            return {}

    def attention_approved(self, db, delivery):
        # Conversation first: the model labels relevance, not delivery permission.
        # Explicit quiet/refusal, geography and freshness still come from live rules.
        return (
            self.eligibility(
                self.profile_row(db, delivery["recipient_id"]),
                self.post(db, delivery["post_id"]),
                db,
            )
            == "ELIGIBLE"
        )

    def next_attention(self):
        # Only short database work; the network request happens after this scope exits.
        with self.scope() as db:
            rows = db.execute(
                "SELECT * FROM deliveries WHERE type='invitation' AND state='pending' AND attention_json IS NULL ORDER BY created_at,id LIMIT 50"
            ).fetchall()
            for d in rows:
                profile = self.profile_row(db, d["recipient_id"])
                post = self.post(db, d["question_id"])
                if (
                    post["author_id"] != d["recipient_id"]
                    and post["question_status"] == "waiting"
                    and self.eligibility(profile, post, db) == "ELIGIBLE"
                ):
                    return {
                        "id": d["id"],
                        "recipient_id": d["recipient_id"],
                        "post_id": post["id"],
                        "request_id": d["request_id"],
                        "revision": profile["revision"],
                        "input_key": self.attention_key(profile, post),
                        "focus": profile["focus"],
                        "transcript": post["transcript"],
                        "context": {
                            "kind": "question",
                            "target_label": post["target_label"] or "中环路金科路口",
                            "scope": "nearby"
                            if post["target_latitude"] is not None
                            else "simulated",
                        },
                    }
        return None

    def finish_attention(self, snapshot, result):
        with self.scope() as db:
            d = self.delivery_row(db, snapshot["recipient_id"], snapshot["id"])
            p = self.profile_row(db, snapshot["recipient_id"])
            q = self.post(db, snapshot["post_id"])
            if (
                d["state"] != "pending"
                or d["attention_json"] is not None
                or d["post_id"] != q["id"]
                or d["request_id"] != snapshot["request_id"]
                or p["revision"] != snapshot["revision"]
                or d["profile_revision"] != p["revision"]
                or self.attention_key(p, q) != snapshot["input_key"]
                or q["author_id"] == p["user_id"]
                or q["question_status"] != "waiting"
                or self.eligibility(p, q, db) != "ELIGIBLE"
            ):
                return False
            record = {
                "action": result.action,
                "reason": result.reason,
                "confidence": result.confidence,
                "none_probability": result.none_probability,
                "input_key": snapshot["input_key"],
                "profile_revision": p["revision"],
                "provider": "bamboo",
                "model": BambooDecision.model,
                "policy_version": BambooDecision.policy_version,
                "evaluated_at": stamp(self.clock()),
            }
            db.execute(
                "UPDATE deliveries SET attention_json=? WHERE id=?", (json.dumps(record), d["id"])
            )
            return True

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
        if p["kind"] in ("scene", "knowledge", "announcement", "reply"):
            return (
                self.confirm_voice(db, user, p)
                if p["status"] != "published"
                else self.voice_confirm_result(db, p)
            )
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
                self.ensure_question_task(db, q)
                self.route_question(db, q)
            else:
                q = self.valid_invitation(db, user, p["invitation_id"], p["reply_to"])
                if q["question_status"] != "waiting":
                    raise Problem(409, "QUESTION_CLOSED", "提问已结束")
                source, _ = self.voice_meta(db, pid)
                self.check_channel_scope(
                    db, user, (source or {}).get("channel_id"), q["target_place_id"]
                )
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
        self.task_on_message(db, p)
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
            "target_place": {
                "id": q["target_place_id"],
                "label": q["target_label"] or "中环路金科路口",
            },
            "status": q["question_status"],
            "expires_at": stamp(q["expires_at"]),
            "answer": self.voice_payload(db, answer) if answer else None,
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
        payload = {
            "post_id": p["id"],
            "transcript": p["transcript"],
            "author_label": self.message_author_label(p),
            "published_at": stamp(p["published_at"]),
            "audio_url": f"/v1/audio/{p['id']}",
        }
        try:
            with wave.open(str(self.store.audio_dir / f"{p['id']}.wav"), "rb") as audio:
                payload["duration_seconds"] = audio.getnframes() / audio.getframerate()
        except (OSError, wave.Error):
            pass
        return payload

    def delivery(self, db, user, did):
        if did.startswith("chatd_"):
            return self.chat_delivery(db, user, did)
        d = self.delivery_row(db, user, did)
        p = self.post(db, d["post_id"])
        reason = d["reason_code"]
        actionable = d["state"] == "pending" or (
            d["type"] == "invitation" and d["state"] == "prompted"
        )
        if not actionable:
            reason = reason if reason != "ELIGIBLE" else "ALREADY_HANDLED"
        elif self.profile_row(db, user)["mode"] == "quiet":
            actionable, reason = False, "MODE_QUIET"
        if actionable and d["type"] == "invitation" and not self.attention_approved(db, d):
            actionable, reason = False, "ATTENTION_NOT_APPROVED"
        if (
            actionable
            and d["type"] == "audio"
            and p["kind"] == "question"
            and not self.can_read_audio(db, user, p)
        ):
            actionable, reason = False, "CANDIDATE_EXPIRED"
        if actionable:
            gate = self.voice_delivery_gate(db, user, d, p)
            if gate:
                actionable, reason = False, gate
        payload = (
            self.voice_payload(db, p)
            if d["type"] == "audio"
            else {
                "transcript": p["transcript"],
                "author_id": p["author_id"],
                "author_label": self.message_author_label(p),
                "visibility": "public",
                "operator_reply_channel": self.channel_reply_option(db, user, p["id"]),
                "place_id": p["target_place_id"],
                "place_name": p["target_label"] or "中环路金科路口",
                "location_scope": "nearby" if p["target_latitude"] is not None else "simulated",
                "direction": p["direction"],
                "prompt_key": "onsite_question",
                "question_audio_url": f"/v1/audio/{p['id']}",
                **(
                    {
                        "attention": {
                            "action": "notify" if actionable else "defer",
                            "provider": "sonar",
                            "model_action": self.attention_record(d).get("action", "pending"),
                            "model_reason": self.attention_record(d).get("reason"),
                            "blocking": False,
                            "policy": "conversation_first",
                            "model": BambooDecision.model,
                        }
                    }
                    if self.settings.decision_enabled
                    else {}
                ),
            }
        )
        if d["type"] == "invitation":
            task = self.question_task(db, p)
            payload["collaboration"] = {
                "request_id": p["id"],
                "task_id": task["id"] if task else None,
                "deadline": stamp(p["expires_at"]),
                "visibility": "public",
                "policy_version": "1",
                "allowed_actions": ["answer", "decline"],
            }
        data = json.loads(d["voice_json"] or "{}")
        if d["type"] == "audio":
            payload["reasons"] = data.get(
                "reasons",
                ["回复了你的问题"] if p["kind"] in ("answer", "reply") else ["你主动选择收听"],
            )
            payload["visit_id"] = data.get("visit_id")
            payload["priority"] = (
                0
                if not data.get("automatic", True)
                else 1
                if p["kind"] in ("answer", "reply")
                else 2
                if p["kind"] == "announcement"
                else 4
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
        self.invite_nearby(db, user)
        self.sync_voices(db, user)
        rows = db.execute(
            "SELECT id FROM deliveries WHERE recipient_id=? AND (state='pending' OR (type='invitation' AND state='prompted')) ORDER BY CASE WHEN type='audio' THEN 0 ELSE 1 END,created_at,id",
            (user,),
        ).fetchall()
        items = [self.delivery(db, user, d["id"]) for d in rows]
        items.sort(key=lambda d: d["payload"].get("priority", 3))
        return {
            "items": [d for d in items if d["type"] == "audio" or d["actionable"]][:10],
            **self.now(),
        }

    def ack(self, db, user, did, event, request_id):
        if did.startswith("chatd_"):
            return self.chat_ack(db, user, did, event, request_id)
        d = self.delivery_row(db, user, did)
        if d["request_id"] != request_id:
            raise Problem(409, "REQUEST_CONFLICT", "播放请求已改变")
        if event in ("received", "seen", "played", "skipped", "failed"):
            data = json.loads(d["voice_json"] or "{}")
            db.execute(
                "INSERT OR IGNORE INTO delivery_receipts VALUES (?,?,?,?,?,?,?)",
                (did, request_id, user, d["post_id"], data.get("visit_id"), event, self.clock()),
            )
        if event in ("received", "seen"):
            column = "received_at" if event == "received" else "seen_at"
            db.execute(
                f"UPDATE deliveries SET {column}=COALESCE({column},?) WHERE id=?",
                (self.clock(), did),
            )
            return {"delivery": self.delivery(db, user, did), **self.now()}
        if event == "alerted" and d["type"] == "invitation" and not self.attention_approved(db, d):
            raise Problem(409, "ATTENTION_NOT_APPROVED", "这条邀请尚未通过个人筛选")
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
            if event == "played":
                db.execute(
                    "UPDATE deliveries SET played_at=COALESCE(played_at,?) WHERE id=?",
                    (self.clock(), did),
                )
        return {"delivery": self.delivery(db, user, did), **self.now()}

    def can_read_audio(self, db, user, p):
        if p["author_id"] == user:
            return True
        if p["status"] != "published":
            return False
        source, _ = self.voice_meta(db, p["id"])
        if source and source.get("channel_id") and not self.voice_live(db, p):
            return False
        if p["kind"] in ("scene", "knowledge", "announcement"):
            return self.voice_live(db, p)
        if p["kind"] == "reply":
            meta, _ = self.voice_meta(db, p["id"])
            return self.voice_live(db, p) and user in (p["author_id"], meta["recipient_id"])
        if p["kind"] == "answer":
            q = self.post(db, p["reply_to"])
            return (
                q["author_id"] == user
                and p["published_at"] + self.settings.audio_ttl > self.clock()
            )
        if (
            p["kind"] == "question"
            and p["question_status"] == "answered"
            and p["published_at"] + self.settings.audio_ttl > self.clock()
        ):
            if db.execute(
                "SELECT 1 FROM posts WHERE kind='answer' AND reply_to=? AND author_id=? AND status='published'",
                (p["id"], user),
            ).fetchone():
                return True
        d = db.execute(
            "SELECT * FROM deliveries WHERE recipient_id=? AND post_id=? AND type='invitation' AND state IN ('pending','prompted')",
            (user, p["id"]),
        ).fetchone()
        return bool(d) and self.attention_approved(db, d)

    def get_feed(self, db, user):
        now = self.clock()
        self.feeds = {key: value for key, value in self.feeds.items() if value["expires"] > now}
        candidates = []
        profile = self.profile_row(db, user)
        session = self.current_agent_session(db, user)
        places = {profile["place_id"]}
        if session:
            places.add(session["place_id"])
        places.update(
            r[0]
            for r in db.execute("SELECT place_id FROM user_destinations WHERE user_id=?", (user,))
        )
        rows = db.execute(
            "SELECT * FROM posts WHERE status='published' ORDER BY CASE kind WHEN 'answer' THEN 0 "
            "WHEN 'reply' THEN 0 WHEN 'question' THEN 1 WHEN 'announcement' THEN 2 ELSE 3 END,published_at DESC,id DESC"
        )
        for p in rows:
            if p["author_id"] == user or not self.information_live(db, user, p):
                continue
            if (
                p["kind"] not in ("question", "answer", "reply")
                and p["target_place_id"] not in places
            ):
                continue
            candidate = (
                self.voice_payload(db, p)
                if p["kind"] == "question"
                else self.information_candidate(db, user, p)
            )
            if candidate:
                candidates.append(candidate)
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
            "agent_session": self.current_agent_session(db, user),
            "candidates": candidates,
            "destinations": [
                dict(r)
                for r in db.execute(
                    "SELECT d.alias,p.id AS place_id,p.label FROM user_destinations d JOIN places p ON p.id=d.place_id WHERE d.user_id=? ORDER BY d.alias",
                    (user,),
                )
            ],
            "chat": [
                dict(r)
                for r in db.execute(
                    "SELECT id,state FROM chat_requests WHERE user_id=? AND state IN ('waiting','paired','agent')",
                    (user,),
                )
            ],
        }

    def current_agent_session(self, db, user):
        row = db.execute(
            "SELECT id FROM exchange_sessions WHERE user_id=? AND state='active' "
            "AND expires_at>? ORDER BY created_at DESC LIMIT 1",
            (user, self.clock()),
        ).fetchone()
        return self.exchange_view(db, user, row["id"])["session"] if row else None

    def set_focus(self, db, user, expected_revision, mode=None, assist_enabled=None, focus=None):
        p = self.profile(db, user)
        data = Context(
            expected_revision=expected_revision,
            presence=None,
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
        if not self.information_live(db, user, post):
            raise Problem(410, "CANDIDATE_EXPIRED", "声音已不可访问")
        if post["kind"] in ("scene", "knowledge", "announcement", "reply"):
            queued = self.queue_place_voice(db, user, post_id, request_id + ":" + post_id)
            return {"delivery_id": queued["delivery"]["id"], "state": queued["delivery"]["state"]}
        qid = post["reply_to"] or post["id"]
        if post["kind"] == "answer":
            expires = post["published_at"] + self.settings.audio_ttl
        else:
            invite = db.execute(
                "SELECT expires_at FROM deliveries WHERE recipient_id=? AND post_id=? AND type='invitation' AND state IN ('pending','prompted')",
                (user, post_id),
            ).fetchone()
            if not invite:
                raise Problem(410, "CANDIDATE_EXPIRED", "现场邀请已结束，请查看对话上下文")
            expires = min(post["expires_at"], invite["expires_at"])
        d = self.create_delivery(db, user, qid, post_id, "audio", expires)
        data = json.loads(d["voice_json"] or "{}")
        reasons = data.get("reasons", ["回复了你的问题"] if post["kind"] == "answer" else [])
        data.update(
            {
                "automatic": False,
                "category": post["kind"],
                "reasons": list(dict.fromkeys(reasons + ["你主动选择收听"])),
            }
        )
        db.execute("UPDATE deliveries SET voice_json=? WHERE id=?", (json.dumps(data), d["id"]))
        return {"delivery_id": d["id"], "state": d["state"]}
