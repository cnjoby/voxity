"""Mutually requested human voice chat. Private assistant state is never exchanged."""

import json
import secrets
from datetime import UTC, datetime

from .errors import Problem


def ident(prefix):
    return f"{prefix}_{secrets.token_hex(12)}"


def stamp(value):
    return datetime.fromtimestamp(value, UTC).isoformat().replace("+00:00", "Z")


class ChatRules:
    def chat_status(self, db, user, sid):
        row = db.execute(
            "SELECT id,state,expires_at FROM chat_requests WHERE user_id=? AND session_id=? "
            "ORDER BY created_at DESC,id DESC LIMIT 1",
            (user, sid),
        ).fetchone()
        return (
            {"id": row["id"], "state": row["state"], "expires_at": stamp(row["expires_at"])}
            if row
            else None
        )

    def expire_chats(self, db):
        rows = db.execute(
            "SELECT c.id,c.peer_id FROM chat_requests c JOIN exchange_sessions s ON s.id=c.session_id "
            "WHERE c.state IN ('waiting','paired','agent') AND (c.expires_at<=? OR s.state='ended' OR s.expires_at<=?)",
            (self.clock(), self.clock()),
        ).fetchall()
        for row in rows:
            self.close_chat(db, row["id"])
        db.execute(
            "DELETE FROM agent_pending WHERE expires_at<=? OR session_id IN "
            "(SELECT id FROM exchange_sessions WHERE state!='active' OR expires_at<=?)",
            (self.clock(), self.clock()),
        )

    def close_chat(self, db, request_id):
        row = db.execute("SELECT peer_id FROM chat_requests WHERE id=?", (request_id,)).fetchone()
        ids = [request_id, row["peer_id"]] if row and row["peer_id"] else [request_id]
        for rid in ids:
            db.execute("UPDATE chat_requests SET state='closed',audio=X'' WHERE id=?", (rid,))
            db.execute(
                "UPDATE chat_deliveries SET state='expired' WHERE message_id IN "
                "(SELECT id FROM chat_messages WHERE request_id=?) AND state='pending'",
                (rid,),
            )
            db.execute("UPDATE chat_messages SET audio=X'' WHERE request_id=?", (rid,))

    def active_chat(self, db, user, sid):
        return db.execute(
            "SELECT * FROM chat_requests WHERE user_id=? AND session_id=? "
            "AND state IN ('waiting','paired','agent')",
            (user, sid),
        ).fetchone()

    def request_chat(self, db, user, session, text, audio):
        old = self.active_chat(db, user, session["id"])
        if old and old["state"] == "agent":
            self.close_chat(db, old["id"])
        elif old:
            return old
        for row in db.execute(
            "SELECT id FROM chat_requests WHERE user_id=? AND state!='closed'", (user,)
        ):
            self.close_chat(db, row["id"])
        rid = ident("chat")
        db.execute(
            "INSERT INTO chat_requests VALUES (?,?,?,?,?,?,?,?,?,?)",
            (
                rid,
                user,
                session["id"],
                "waiting",
                None,
                text,
                audio,
                self.clock(),
                min(session["expires_at"], self.clock() + 120),
                self.clock(),
            ),
        )
        peer = db.execute(
            "SELECT c.* FROM chat_requests c JOIN exchange_sessions s ON s.id=c.session_id "
            "JOIN profiles p ON p.user_id=c.user_id WHERE c.state='waiting' AND c.user_id!=? "
            "AND c.expires_at>? AND c.seen_at>? AND s.state='active' AND s.expires_at>? "
            "AND s.network_enabled=1 AND p.mode='listen' ORDER BY c.created_at,c.id LIMIT 1",
            (user, self.clock(), self.clock() - 30, self.clock()),
        ).fetchone()
        if peer:
            own = db.execute("SELECT * FROM chat_requests WHERE id=?", (rid,)).fetchone()
            for a, b in ((own, peer), (peer, own)):
                deadline = min(
                    session["expires_at"],
                    self.exchange_row(db, b["user_id"], b["session_id"])["expires_at"],
                )
                db.execute(
                    "UPDATE chat_requests SET state='paired',peer_id=?,expires_at=? WHERE id=?",
                    (b["id"], deadline, a["id"]),
                )
            self.chat_message(db, own, text, audio)
            self.chat_message(db, peer, peer["transcript"], peer["audio"])
            db.execute("UPDATE chat_requests SET audio=X'' WHERE id IN (?,?)", (rid, peer["id"]))
            old = db.execute(
                "SELECT turn_id FROM agent_pending WHERE session_id=?", (peer["session_id"],)
            ).fetchone()
            if old:
                db.execute(
                    "UPDATE exchange_turns SET status='chatting',result_json=? WHERE id=?",
                    (
                        json.dumps(
                            {"status": "chatting", "action": "human_chat", "chat_id": peer["id"]}
                        ),
                        old["turn_id"],
                    ),
                )
                db.execute("DELETE FROM agent_pending WHERE session_id=?", (peer["session_id"],))
        return db.execute("SELECT * FROM chat_requests WHERE id=?", (rid,)).fetchone()

    def chat_message(self, db, chat, text, audio):
        # Read the live pair again; a queued turn cannot target a replaced peer.
        chat = db.execute("SELECT * FROM chat_requests WHERE id=?", (chat["id"],)).fetchone()
        peer = db.execute("SELECT * FROM chat_requests WHERE id=?", (chat["peer_id"],)).fetchone()
        if (
            chat["state"] != "paired"
            or not peer
            or peer["state"] != "paired"
            or peer["peer_id"] != chat["id"]
        ):
            raise Problem(409, "CHAT_CLOSED", "本次车友交流已结束")
        mid, did = ident("chatm"), ident("chatd")
        db.execute(
            "INSERT INTO chat_messages VALUES (?,?,?,?,?,?)",
            (mid, chat["id"], chat["user_id"], text, audio, self.clock()),
        )
        db.execute(
            "INSERT INTO chat_deliveries VALUES (?,?,?,?,?,?,?)",
            (
                did,
                peer["user_id"],
                mid,
                ident("play"),
                self.profile_row(db, peer["user_id"])["revision"],
                "pending",
                chat["expires_at"],
            ),
        )
        return mid

    def chat_delivery(self, db, user, did):
        row = db.execute(
            "SELECT d.*,m.transcript,m.created_at,m.author_id,c.id AS chat_id,c.state AS chat_state,"
            "c.session_id,c.peer_id FROM chat_deliveries d JOIN chat_messages m ON m.id=d.message_id "
            "JOIN chat_requests c ON c.id=m.request_id WHERE d.id=? AND d.recipient_id=?",
            (did, user),
        ).fetchone()
        if not row:
            raise Problem(404, "NOT_FOUND", "声音不存在")
        profile = self.profile_row(db, user)
        own = db.execute(
            "SELECT * FROM chat_requests WHERE id=? AND user_id=?", (row["peer_id"], user)
        ).fetchone()
        session = self.exchange_row(db, user, own["session_id"]) if own else None
        active = bool(
            own
            and own["state"] == "paired"
            and row["chat_state"] == "paired"
            and session["state"] == "active"
            and session["expires_at"] > self.clock()
            and session["auto_play"]
            and profile["mode"] == "listen"
            and row["state"] == "pending"
            and row["expires_at"] > self.clock()
        )
        if row["profile_revision"] != profile["revision"] and row["state"] == "pending":
            db.execute(
                "UPDATE chat_deliveries SET profile_revision=?,request_id=? WHERE id=?",
                (profile["revision"], ident("play"), did),
            )
            return self.chat_delivery(db, user, did)
        return {
            "id": did,
            "type": "audio",
            "question_id": row["chat_id"],
            "request_id": row["request_id"],
            "profile_revision": row["profile_revision"],
            "state": row["state"],
            "expires_at": stamp(row["expires_at"]),
            "actionable": active,
            "reason_code": "ELIGIBLE" if active else "CHAT_PAUSED_OR_CLOSED",
            "payload": {
                "post_id": row["message_id"],
                "kind": "chat",
                "transcript": row["transcript"],
                "author_label": f"车友 {row['author_id']}",
                "published_at": stamp(row["created_at"]),
                "audio_url": f"/v1/chat/audio/{row['message_id']}",
                "priority": 0,
                "source_type": "human_original",
                "visibility": "participants",
            },
        }

    def chat_inbox(self, db, user, sid, excluded=()):
        db.execute(
            "UPDATE chat_requests SET seen_at=? WHERE user_id=? AND session_id=? AND state IN ('waiting','paired')",
            (self.clock(), user, sid),
        )
        rows = db.execute(
            "SELECT d.id FROM chat_deliveries d JOIN chat_messages m ON m.id=d.message_id "
            "JOIN chat_requests c ON c.id=m.request_id JOIN chat_requests peer ON peer.id=c.peer_id "
            "WHERE d.recipient_id=? AND peer.session_id=? AND d.state='pending' "
            "ORDER BY m.created_at,m.id LIMIT 20",
            (user, sid),
        ).fetchall()
        return [
            d
            for r in rows
            if r["id"] not in excluded
            for d in [self.chat_delivery(db, user, r["id"])]
            if d["actionable"]
        ][:10]

    def chat_ack(self, db, user, did, event, request_id):
        current = self.chat_delivery(db, user, did)
        if current["request_id"] != request_id:
            raise Problem(409, "REQUEST_CONFLICT", "播放请求已改变")
        if event not in ("played", "skipped", "failed", "received", "seen"):
            raise Problem(409, "INVALID_TRANSITION", "不能使用该回执")
        if event in ("played", "skipped") and current["state"] != event:
            if current["state"] != "pending":
                raise Problem(409, "INVALID_TRANSITION", "任务已结束")
            db.execute("UPDATE chat_deliveries SET state=? WHERE id=?", (event, did))
        return {"delivery": self.chat_delivery(db, user, did), **self.now()}

    def chat_audio(self, db, user, mid):
        row = db.execute(
            "SELECT m.*,c.state,c.peer_id,c.expires_at FROM chat_messages m "
            "JOIN chat_requests c ON c.id=m.request_id WHERE m.id=?",
            (mid,),
        ).fetchone()
        peer = (
            db.execute("SELECT user_id FROM chat_requests WHERE id=?", (row["peer_id"],)).fetchone()
            if row
            else None
        )
        if (
            not row
            or row["state"] != "paired"
            or row["expires_at"] <= self.clock()
            or not peer
            or user not in (row["author_id"], peer["user_id"])
        ):
            raise Problem(404, "NOT_FOUND", "声音不存在")
        return bytes(row["audio"])
