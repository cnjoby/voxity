"""Public place voices and directed replies. Location never grants operator authority."""

import json
from uuid import uuid4

from .errors import Problem
from .geo import distance_m


def new_id(prefix):
    return f"{prefix}_{uuid4().hex}"


def epoch(value):
    from datetime import datetime

    return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp() if value else None


class VoiceRules:
    def listening(self, db, user):
        row = db.execute(
            "SELECT settings_json FROM voice_settings WHERE user_id=?", (user,)
        ).fetchone()
        return (
            json.loads(row[0])
            if row
            else {
                "scene_enabled": False,
                "channel_enabled": True,
                "replies_enabled": True,
                "interests": [],
                "subscribed_places": [],
                "muted_topics": [],
                "muted_channels": [],
            }
        )

    def set_listening(self, db, user, data):
        p = self.profile_row(db, user)
        if p["revision"] != data.expected_revision:
            raise Problem(409, "REVISION_CONFLICT", "设置已改变，请刷新后重试")
        values = data.model_dump(exclude={"expected_revision"})
        if values != self.listening(db, user):
            db.execute(
                "INSERT INTO voice_settings VALUES (?,?) ON CONFLICT(user_id) DO UPDATE SET settings_json=excluded.settings_json",
                (user, json.dumps(values)),
            )
            db.execute("UPDATE profiles SET revision=revision+1 WHERE user_id=?", (user,))
            revision = self.profile_row(db, user)["revision"]
            # Content and request identity remain immutable; clients recheck the new revision.
            db.execute(
                "UPDATE deliveries SET profile_revision=? WHERE recipient_id=? AND state IN ('pending','prompted')",
                (revision, user),
            )
        return {"profile": self.profile(db, user), **self.now()}

    def voice_meta(self, db, pid):
        row = db.execute("SELECT * FROM voice_details WHERE post_id=?", (pid,)).fetchone()
        return (json.loads(row["metadata_json"]), row) if row else (None, None)

    def channel(self, db, cid):
        row = db.execute("SELECT * FROM channels WHERE id=?", (cid,)).fetchone()
        if not row:
            raise Problem(404, "CHANNEL_UNKNOWN", "频道不存在")
        return row

    def require_operator(self, db, user, cid):
        channel = self.channel(db, cid)
        if not db.execute(
            "SELECT 1 FROM channel_operators WHERE channel_id=? AND user_id=?", (cid, user)
        ).fetchone():
            raise Problem(403, "OPERATOR_FORBIDDEN", "没有该地点的运营发布权限")
        return channel

    def channels_for(self, db, user, place):
        rows = db.execute(
            "SELECT * FROM channels WHERE place_id=? ORDER BY id", (place,)
        ).fetchall()
        return [
            {
                **dict(c),
                "can_publish": bool(
                    db.execute(
                        "SELECT 1 FROM channel_operators WHERE channel_id=? AND user_id=?",
                        (c["id"], user),
                    ).fetchone()
                ),
            }
            for c in rows
        ]

    def thread_root(self, db, user, qid):
        q = self.post(db, qid)
        if q["kind"] not in ("question", "scene") or q["status"] != "published":
            raise Problem(404, "NOT_FOUND", "对话不存在")
        participants = {q["author_id"]} | {
            r[0]
            for r in db.execute(
                "SELECT author_id FROM posts WHERE kind='answer' AND reply_to=? AND status='published'",
                (qid,),
            )
        }
        participants.update(
            r[0]
            for r in db.execute(
                "SELECT p.author_id FROM posts p JOIN voice_details v ON v.post_id=p.id "
                "WHERE p.kind='reply' AND p.status='published' "
                "AND json_extract(v.metadata_json,'$.root_question_id')=?",
                (qid,),
            )
        )
        if q["kind"] == "scene" and self.voice_live(db, q) and self.can_read_audio(db, user, q):
            return q
        if user not in participants:
            raise Problem(404, "NOT_FOUND", "对话不存在")
        return q

    def thread_deadline(self, db, q):
        if q["kind"] == "scene":
            meta, _ = self.voice_meta(db, q["id"])
            return min(
                epoch((meta or {}).get("valid_until")) or 0,
                q["published_at"] + self.settings.audio_ttl,
            )
        return q["published_at"] + self.settings.audio_ttl

    def thread_open(self, db, q):
        return self.thread_deadline(db, q) > self.clock() and (
            self.voice_live(db, q) if q["kind"] == "scene" else q["question_status"] == "answered"
        )

    def check_reply(self, db, user, fields):
        q = self.thread_root(db, user, fields["root_question_id"])
        if not self.thread_open(db, q):
            raise Problem(410, "CONVERSATION_CLOSED", "对话已结束")
        parent = self.post(db, fields["reply_to"])
        meta, _ = self.voice_meta(db, parent["id"])
        root = (
            parent["id"]
            if parent["kind"] in ("question", "scene")
            else parent["reply_to"]
            if parent["kind"] == "answer"
            else (meta or {}).get("root_question_id")
        )
        if (
            parent["status"] != "published"
            or root != q["id"]
            or fields["recipient_id"] != parent["author_id"]
            or parent["author_id"] == user
        ):
            raise Problem(403, "REPLY_FORBIDDEN", "回复对象或上下文不匹配")
        if not self.can_read_audio(db, user, parent):
            raise Problem(403, "REPLY_FORBIDDEN", "原声不可访问，不能引用或公开")
        return q

    def validate_voice(self, db, user, fields):
        if (
            epoch(fields.get("recorded_at")) is not None
            and epoch(fields["recorded_at"]) > self.clock() + 10
        ):
            raise Problem(422, "RECORDING_TIME_INVALID", "录制时间不能在未来")
        if fields["kind"] == "reply":
            q = self.check_reply(db, user, fields)
            self.check_channel_scope(db, user, fields.get("channel_id"), q["target_place_id"])
            return q
        target = db.execute(
            "SELECT * FROM places WHERE id=?", (fields["target_place_id"],)
        ).fetchone()
        if not target:
            raise Problem(422, "PLACE_UNKNOWN", "请明确选择声音适用地点")
        cid = fields.get("channel_id")
        if cid:
            channel = self.require_operator(db, user, cid)
            if channel["place_id"] != target["id"]:
                raise Problem(403, "CHANNEL_PLACE_MISMATCH", "运营权限不适用于该地点")
        kind, purpose = fields["kind"], fields["purpose"]
        start, end = epoch(fields.get("valid_from")), epoch(fields.get("valid_until"))
        if kind == "announcement" and (not cid or purpose != "notice"):
            raise Problem(422, "ANNOUNCEMENT_SCOPE_REQUIRED", "运营告知需要授权频道和告知用途")
        if (kind == "knowledge" or purpose == "explanation") and (
            not fields.get("source_note") or purpose != "explanation"
        ):
            raise Problem(422, "SOURCE_REQUIRED", "知识说明需要来源和解说用途")
        if kind == "scene" and purpose == "notice":
            raise Problem(422, "PURPOSE_INVALID", "个人声音不能冒充运营告知")
        if fields["scope"] != "poi" and not fields.get("scope_label"):
            raise Problem(422, "SCOPE_REQUIRED", "请注明入口、楼层或区域")
        dynamic = kind == "announcement" or purpose == "observation"
        if dynamic:
            maximum = (
                self.settings.announcement_max_ttl
                if kind == "announcement"
                else self.settings.observation_max_ttl
            )
            if start is None or end is None or end <= start or end - start > maximum:
                raise Problem(422, "VALIDITY_REQUIRED", "即时内容必须设置有界的生效与失效时间")
        if end is not None and (end <= self.clock() or (start is not None and end <= start)):
            raise Problem(410, "VOICE_EXPIRED", "声音有效期已结束")
        if fields.get("replaces"):
            old = self.post(db, fields["replaces"])
            previous, state = self.voice_meta(db, old["id"])
            if (
                kind != "announcement"
                or not previous
                or previous.get("channel_id") != cid
                or old["status"] != "published"
                or state["withdrawn_at"]
                or state["replaced_by"]
            ):
                raise Problem(409, "VERSION_CONFLICT", "只能替代本频道当前公告")
            if previous["scope"] != fields["scope"] or previous.get("scope_label") != fields.get(
                "scope_label"
            ):
                raise Problem(409, "SCOPE_CONFLICT", "更新公告须保持适用范围")
            if start > self.clock():
                raise Problem(422, "REPLACEMENT_NOT_ACTIVE", "替代公告必须立即生效")
        return target

    def check_channel_scope(self, db, user, cid, place):
        if cid and self.require_operator(db, user, cid)["place_id"] != place:
            raise Problem(403, "CHANNEL_PLACE_MISMATCH", "运营权限不适用于该地点")

    def save_qa_source(self, db, user, p, fields):
        cid = fields.get("channel_id")
        if fields.get("recorded_at") and epoch(fields["recorded_at"]) > self.clock() + 10:
            raise Problem(422, "RECORDING_TIME_INVALID", "录制时间不能在未来")
        data = {"kind": fields["kind"], "recorded_at": fields.get("recorded_at")}
        if cid:
            if p["kind"] == "question":
                if self.channel(db, cid)["place_id"] != p["target_place_id"]:
                    raise Problem(403, "CHANNEL_PLACE_MISMATCH", "提问地点不属于此频道")
                data["question_channel_id"] = cid
            else:
                self.check_channel_scope(db, user, cid, p["target_place_id"])
                data["channel_id"] = cid
        if cid or fields.get("recorded_at"):
            db.execute(
                "INSERT INTO voice_details(post_id,metadata_json) VALUES (?,?)",
                (p["id"], json.dumps(data)),
            )

    def channel_reply_option(self, db, user, qid):
        meta, _ = self.voice_meta(db, qid)
        cid = (meta or {}).get("question_channel_id")
        if (
            cid
            and db.execute(
                "SELECT 1 FROM channel_operators WHERE channel_id=? AND user_id=?", (cid, user)
            ).fetchone()
        ):
            return cid
        return None

    def save_voice_draft(self, db, user, pid, fields):
        target = self.validate_voice(db, user, fields)
        db.execute(
            "INSERT INTO voice_details(post_id,metadata_json) VALUES (?,?)",
            (pid, json.dumps(fields, ensure_ascii=False)),
        )
        if fields["kind"] == "reply":
            db.execute(
                "UPDATE posts SET target_latitude=?,target_longitude=?,target_label=? WHERE id=?",
                (
                    target["target_latitude"],
                    target["target_longitude"],
                    target["target_label"],
                    pid,
                ),
            )
        else:
            db.execute(
                "UPDATE posts SET target_latitude=?,target_longitude=?,target_label=? WHERE id=?",
                (target["latitude"], target["longitude"], target["label"], pid),
            )

    def confirm_voice(self, db, user, p):
        meta, _ = self.voice_meta(db, p["id"])
        self.validate_voice(db, user, meta)
        db.execute(
            "UPDATE posts SET status='published',published_at=?,expires_at=? WHERE id=?",
            (self.clock(), epoch(meta.get("valid_until")), p["id"]),
        )
        if meta.get("replaces"):
            db.execute(
                "UPDATE voice_details SET replaced_by=? WHERE post_id=?",
                (p["id"], meta["replaces"]),
            )
            self.invalidate_voice(db, meta["replaces"], "VOICE_REPLACED")
        if p["kind"] == "reply":
            q = self.post(db, meta["root_question_id"])
            d = self.create_delivery(
                db,
                meta["recipient_id"],
                q["id"],
                p["id"],
                "audio",
                self.thread_deadline(db, q),
            )
            db.execute(
                "UPDATE deliveries SET voice_json=? WHERE id=?",
                (
                    json.dumps(
                        {"reasons": ["回复了你的问题"], "category": "reply", "automatic": True}
                    ),
                    d["id"],
                ),
            )
        return self.voice_confirm_result(db, self.post(db, p["id"]))

    def voice_confirm_result(self, db, p):
        meta, _ = self.voice_meta(db, p["id"])
        return {
            "post_id": p["id"],
            "status": p["status"],
            "question_id": (meta or {}).get("root_question_id"),
            "question_status": None,
            **self.now(),
        }

    def voice_live(self, db, p, meta=None):
        meta, state = self.voice_meta(db, p["id"])
        if not meta or p["status"] != "published" or state["withdrawn_at"] or state["replaced_by"]:
            return False
        if (
            meta.get("channel_id")
            and not db.execute(
                "SELECT 1 FROM channel_operators WHERE channel_id=? AND user_id=?",
                (meta["channel_id"], p["author_id"]),
            ).fetchone()
        ):
            return False
        if meta["kind"] == "reply":
            q = self.post(db, meta["root_question_id"])
            return self.thread_open(db, q)
        return (epoch(meta.get("valid_from")) or 0) <= self.clock() and (
            epoch(meta.get("valid_until")) is None or epoch(meta["valid_until"]) > self.clock()
        )

    def invalidate_voice(self, db, pid, reason):
        db.execute(
            "UPDATE deliveries SET state='cancelled',reason_code=? WHERE post_id=? AND state='pending'",
            (reason, pid),
        )

    def withdraw_voice(self, db, user, pid):
        p = self.post(db, pid)
        meta, _ = self.voice_meta(db, pid)
        if not meta or p["kind"] not in ("scene", "knowledge", "announcement"):
            raise Problem(404, "NOT_FOUND", "地点声音不存在")
        if meta.get("channel_id"):
            self.require_operator(db, user, meta["channel_id"])
        elif p["author_id"] != user:
            raise Problem(403, "FORBIDDEN", "只能撤回本人的声音")
        db.execute(
            "UPDATE voice_details SET withdrawn_at=COALESCE(withdrawn_at,?) WHERE post_id=?",
            (self.clock(), pid),
        )
        self.invalidate_voice(db, pid, "VOICE_WITHDRAWN")
        return {"post_id": pid, "status": "withdrawn", **self.now()}

    def physically_near(self, db, user, p, meta):
        profile = self.profile_row(db, user)
        if (
            meta.get("scope", "poi") != "poi"
            or profile["latitude"] is None
            or p["target_latitude"] is None
        ):
            return False
        if profile["source"] not in ("android", "android_mock") or (
            profile["source"] == "android_mock" and not self.settings.allow_mock_location
        ):
            return False
        return (
            profile["sampled_at"] + self.settings.location_ttl > self.clock()
            and profile["accuracy_m"] <= self.settings.location_accuracy_m
            and distance_m(
                profile["latitude"],
                profile["longitude"],
                p["target_latitude"],
                p["target_longitude"],
            )
            + profile["accuracy_m"]
            <= self.settings.voice_radius_m
        )

    def visit(self, db, user, p, near):
        place = p["target_place_id"]
        visit = db.execute(
            "SELECT * FROM voice_visits WHERE user_id=? AND place_id=?", (user, place)
        ).fetchone()
        if not near:
            if visit and visit["outside_since"] is None:
                db.execute(
                    "UPDATE voice_visits SET outside_since=? WHERE user_id=? AND place_id=?",
                    (self.clock(), user, place),
                )
            return None
        if not visit or (
            visit["outside_since"] is not None
            and visit["outside_since"] + self.settings.visit_exit_grace <= self.clock()
        ):
            db.execute(
                "INSERT INTO voice_visits VALUES (?,?,?,NULL,0,NULL) ON CONFLICT(user_id,place_id) DO UPDATE SET visit_id=excluded.visit_id,outside_since=NULL,scene_count=0,last_scene_at=NULL",
                (user, place, new_id("visit")),
            )
        else:
            db.execute(
                "UPDATE voice_visits SET outside_since=NULL WHERE user_id=? AND place_id=?",
                (user, place),
            )
        return db.execute(
            "SELECT * FROM voice_visits WHERE user_id=? AND place_id=?", (user, place)
        ).fetchone()

    def automatic_voice(self, db, user, p, meta):
        prefs = self.listening(db, user)
        if meta.get("applicability") == "unknown":
            return False
        if p["kind"] == "announcement":
            return prefs["channel_enabled"] and meta["channel_id"] not in prefs["muted_channels"]
        if (
            p["kind"] != "scene"
            or not prefs["scene_enabled"]
            or meta.get("applicability") != "unconditional"
        ):
            return False
        topics = set(meta.get("topics", []))
        if topics.intersection(prefs["muted_topics"]):
            return False
        return (
            not prefs["interests"]
            or bool(topics.intersection(prefs["interests"]))
            or p["target_place_id"] in prefs["subscribed_places"]
        )

    def sync_voices(self, db, user):
        # All eligible announcements remain visible, irrespective of interest and mute.
        rows = db.execute(
            "SELECT p.* FROM posts p JOIN voice_details v ON p.id=v.post_id WHERE p.status='published' AND p.kind IN ('scene','announcement') AND v.withdrawn_at IS NULL AND v.replaced_by IS NULL ORDER BY CASE p.kind WHEN 'announcement' THEN 0 ELSE 1 END,p.published_at DESC,p.id"
        ).fetchall()
        for p in rows:
            meta, _ = self.voice_meta(db, p["id"])
            near = self.physically_near(db, user, p, meta)
            if meta.get("scope", "poi") != "poi":
                continue
            visit = self.visit(db, user, p, near)
            if not near:
                db.execute(
                    "UPDATE deliveries SET state='cancelled',reason_code='OUTSIDE_SCOPE' WHERE recipient_id=? AND post_id=? AND state='pending' AND json_extract(voice_json,'$.automatic')=1",
                    (user, p["id"]),
                )
            if not near or not self.voice_live(db, p):
                self.invalidate_voice(db, p["id"], "VOICE_NOT_APPLICABLE") if not self.voice_live(
                    db, p
                ) else None
                continue
            if p["kind"] == "scene" and not self.automatic_voice(db, user, p, meta):
                continue
            old = db.execute(
                "SELECT * FROM deliveries WHERE recipient_id=? AND post_id=? AND type='audio'",
                (user, p["id"]),
            ).fetchone()
            previous = json.loads(old["voice_json"] or "{}") if old else {}
            if old and (previous.get("visit_id") == visit["visit_id"] or p["kind"] == "scene"):
                continue
            if p["kind"] == "scene" and (
                visit["scene_count"] >= self.settings.scene_prompt_limit
                or (
                    visit["last_scene_at"] is not None
                    and visit["last_scene_at"] + self.settings.scene_prompt_interval > self.clock()
                )
            ):
                continue
            reason = "该地点的有效运营公告" if p["kind"] == "announcement" else "你到达了此地点附近"
            reasons = [reason]
            if p["target_place_id"] in self.listening(db, user)["subscribed_places"]:
                reasons.append("你订阅了此地点")
            data = {
                "visit_id": visit["visit_id"],
                "reasons": reasons,
                "category": p["kind"],
                "automatic": True,
            }
            expiry = epoch(meta.get("valid_until")) or self.clock() + self.settings.audio_ttl
            d = self.create_delivery(db, user, p["id"], p["id"], "audio", expiry)
            db.execute(
                "UPDATE deliveries SET voice_json=?,state='pending',reason_code='ELIGIBLE',request_id=?,profile_revision=?,expires_at=?,received_at=NULL,seen_at=NULL,played_at=NULL WHERE id=?",
                (
                    json.dumps(data),
                    new_id("r"),
                    self.profile_row(db, user)["revision"],
                    expiry,
                    d["id"],
                ),
            )
            if p["kind"] == "scene":
                db.execute(
                    "UPDATE voice_visits SET scene_count=scene_count+1,last_scene_at=? WHERE user_id=? AND place_id=?",
                    (self.clock(), user, p["target_place_id"]),
                )

    def voice_delivery_gate(self, db, user, d, p):
        data = json.loads(d["voice_json"] or "{}")
        meta, _ = self.voice_meta(db, p["id"])
        prefs = self.listening(db, user)
        if meta and not self.voice_live(db, p):
            return "VOICE_INACTIVE"
        if data.get("category") == "exchange":
            try:
                session = self.require_exchange(db, user, data["exchange_session_id"], play=True)
            except (Problem, KeyError):
                return "EXCHANGE_INACTIVE"
            if p["target_place_id"] != session["place_id"] or not self.physically_near(
                db, user, p, meta or {}
            ):
                return "OUTSIDE_SCOPE"
            if (
                data.get("exchange_context_post_id") != session["context_post_id"]
                and p["id"] != session["context_post_id"]
            ):
                return "EXCHANGE_CONTEXT_CHANGED"
            return None
        if (
            p["kind"] in ("answer", "reply")
            and not prefs["replies_enabled"]
            and data.get("automatic", True)
        ):
            return "REPLIES_DISABLED"
        if meta and data.get("automatic") and p["kind"] != "reply":
            if not self.physically_near(db, user, p, meta):
                return "OUTSIDE_SCOPE"
            if not self.automatic_voice(db, user, p, meta):
                return "LISTENING_DISABLED"
        return None

    def voice_payload(self, db, p):
        from .service import stamp

        meta, state = self.voice_meta(db, p["id"])
        payload = self.audio_payload(p)
        payload.update(
            {
                "kind": p["kind"],
                "visibility": "public",
                "author_id": p["author_id"],
                "target_place_id": p["target_place_id"],
                "place_name": p["target_label"] or self.place_label(db, p["target_place_id"]),
                "recorded_at": (meta or {}).get("recorded_at"),
                "collected_at": stamp(p["created_at"]),
                "location_scope": "POI 附近；未确认入口或楼层",
                "source_type": "personal",
                "valid_until": stamp(p["expires_at"]),
            }
        )
        if p["kind"] == "answer":
            payload["valid_until"] = stamp(p["published_at"] + self.settings.audio_ttl)
        elif p["kind"] == "reply":
            payload["valid_until"] = stamp(
                self.thread_deadline(db, self.post(db, meta["root_question_id"]))
            )
        elif p["kind"] == "question" and p["question_status"] == "answered":
            payload["valid_until"] = stamp(p["published_at"] + self.settings.audio_ttl)
        if meta:
            payload.update(
                {
                    k: v
                    for k, v in meta.items()
                    if k
                    in (
                        "purpose",
                        "scope",
                        "scope_label",
                        "valid_from",
                        "valid_until",
                        "applicability",
                        "source_note",
                        "topics",
                        "root_question_id",
                        "recipient_id",
                        "reply_to",
                        "channel_id",
                    )
                }
            )
            payload["active"] = self.voice_live(db, p)
            if meta.get("question_channel_id"):
                channel = self.channel(db, meta["question_channel_id"])
                payload.update(
                    {"question_channel_id": channel["id"], "channel_label": channel["label"]}
                )
            if meta.get("channel_id"):
                channel = self.channel(db, meta["channel_id"])
                payload.update(
                    {
                        "source_type": "operator",
                        "author_label": channel["operator_label"],
                        "channel_label": channel["label"],
                        "demo_operator": bool(channel["demo"]),
                    }
                )
            if p["kind"] == "scene" and meta["purpose"] != "observation":
                payload["context_note"] = "历史声音，不代表当前现场状态" + (
                    "；录制时间未提供，请留意收录时间"
                    if not meta.get("recorded_at")
                    else "；请留意录制日期"
                )
            if meta.get("scope", "poi") != "poi":
                payload["location_scope"] = "仅供查询；尚不能确认此入口、楼层或区域到达"
        if p["kind"] == "answer":
            payload.update(
                {
                    "root_question_id": p["reply_to"],
                    "reply_to": p["reply_to"],
                    "recipient_id": self.post(db, p["reply_to"])["author_id"],
                }
            )
        return payload

    def catalog(self, db, user, place):
        channels = self.channels_for(db, user, place)
        rows = db.execute(
            "SELECT p.* FROM posts p JOIN voice_details v ON p.id=v.post_id WHERE p.target_place_id=? AND p.status='published' AND p.kind IN ('scene','knowledge','announcement') ORDER BY p.published_at DESC,p.id",
            (place,),
        ).fetchall()
        items = [self.voice_payload(db, p) for p in rows if self.voice_live(db, p)]
        return {
            "items": items,
            "channels": channels,
            "announcement_status": "有有效运营公告"
            if any(p["kind"] == "announcement" for p in items)
            else "暂无有效运营公告",
            **self.now(),
        }

    def queue_place_voice(self, db, user, pid, command_id=None):
        p = self.post(db, pid)
        is_reply = p["kind"] in ("answer", "reply")
        if not self.can_read_audio(db, user, p) or (
            not is_reply
            and (
                p["kind"] not in ("scene", "knowledge", "announcement")
                or not self.voice_live(db, p)
            )
        ):
            raise Problem(410, "VOICE_INACTIVE", "声音已失效，请刷新")
        if is_reply:
            qid = (
                p["reply_to"]
                if p["kind"] == "answer"
                else self.voice_meta(db, pid)[0]["root_question_id"]
            )
            root = self.thread_root(db, user, qid)
            if not self.thread_open(db, root) or (
                p["kind"] == "answer"
                and p["published_at"] + self.settings.audio_ttl <= self.clock()
            ):
                raise Problem(410, "CONVERSATION_CLOSED", "对话已结束")
            expiry = (
                p["published_at"] + self.settings.audio_ttl
                if p["kind"] == "answer"
                else self.thread_deadline(db, root)
            )
        else:
            qid = pid
            expiry = p["expires_at"] or self.clock() + self.settings.audio_ttl
        if command_id:
            previous = db.execute(
                "SELECT * FROM voice_queue_requests WHERE user_id=? AND command_id=?",
                (user, command_id),
            ).fetchone()
            if previous:
                d = self.delivery_row(db, user, previous["delivery_id"])
                if previous["post_id"] != pid or previous["request_id"] != d["request_id"]:
                    raise Problem(409, "COMMAND_CONFLICT", "这次收听请求已被后续操作替代")
                return {"delivery": self.delivery(db, user, d["id"]), **self.now()}
        d = self.create_delivery(db, user, qid, pid, "audio", expiry)
        data = json.loads(d["voice_json"] or "{}")
        data.update(
            {
                "automatic": False,
                "category": p["kind"],
                "reasons": list(dict.fromkeys(data.get("reasons", []) + ["你主动选择收听"])),
            }
        )
        if command_id and d["state"] != "pending":
            db.execute(
                "UPDATE deliveries SET state='pending',reason_code='ELIGIBLE',request_id=?,expires_at=?,profile_revision=? WHERE id=?",
                (new_id("r"), expiry, self.profile_row(db, user)["revision"], d["id"]),
            )
        db.execute("UPDATE deliveries SET voice_json=? WHERE id=?", (json.dumps(data), d["id"]))
        d = self.delivery_row(db, user, d["id"])
        if command_id:
            db.execute(
                "INSERT INTO voice_queue_requests VALUES (?,?,?,?,?)",
                (user, command_id, pid, d["id"], d["request_id"]),
            )
        return {"delivery": self.delivery(db, user, d["id"]), **self.now()}

    def conversation(self, db, user, qid):
        q = self.thread_root(db, user, qid)
        rows = db.execute(
            "SELECT * FROM posts WHERE status='published' AND (id=? OR (kind='answer' AND reply_to=?) OR id IN (SELECT post_id FROM voice_details WHERE json_extract(metadata_json,'$.root_question_id')=?)) ORDER BY published_at,id",
            (qid, qid, qid),
        ).fetchall()
        return {
            "question_id": qid,
            "visibility": "public",
            "can_reply": self.thread_open(db, q),
            "root_kind": q["kind"],
            "items": [
                {
                    **self.voice_payload(db, p),
                    "operator_reply_channel": self.channel_reply_option(db, user, qid),
                }
                for p in rows
                if self.can_read_audio(db, user, p)
            ],
            **self.now(),
        }
