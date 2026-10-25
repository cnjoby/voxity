"""Read projections only: preserve legacy storage and access rules."""

from .contracts import VoiceMessageView
from .errors import Problem


class MessageViews:
    def message_view(self, db, user, message_id):
        from .service import stamp

        if message_id.startswith("chatm_"):
            # Reuse the exact audio ACL, including expiration and pair membership.
            self.chat_audio(db, user, message_id)
            p = db.execute("SELECT * FROM chat_messages WHERE id=?", (message_id,)).fetchone()
            chat = db.execute(
                "SELECT * FROM chat_requests WHERE id=?", (p["request_id"],)
            ).fetchone()
            peer = db.execute(
                "SELECT user_id FROM chat_requests WHERE id=?", (chat["peer_id"],)
            ).fetchone()
            data = {
                "message_id": p["id"],
                "kind": "chat",
                "time": {
                    "collected_at": stamp(p["created_at"]),
                    "published_at": stamp(p["created_at"]),
                    "valid_until": stamp(chat["expires_at"]),
                },
                "place": {},
                "subject": {
                    "author_id": p["author_id"],
                    "principal_id": p["author_id"],
                    "source_type": "personal",
                },
                "body": {
                    "transcript": p["transcript"],
                    "content_origin": "human_original",
                    "audio_ref": f"/v1/chat/audio/{p['id']}",
                },
                "state": {"publication": "published", "applicability": "valid"},
                "visibility": "participants",
                "recipient_ids": [peer["user_id"]],
                "conversation_id": min(chat["id"], chat["peer_id"]),
            }
        else:
            p = self.post(db, message_id)
            if not self.can_read_audio(db, user, p):
                raise Problem(404, "NOT_FOUND", "声音不存在")
            meta, state = self.voice_meta(db, message_id)
            meta = meta or {}
            payload = self.voice_payload(db, p)
            publication = p["status"]
            if state and state["withdrawn_at"]:
                publication = "withdrawn"
            elif state and state["replaced_by"]:
                publication = "replaced"
            deadline = payload.get("valid_until")
            start = meta.get("valid_from")
            from .voices import epoch

            applicability = "valid"
            if publication != "published" or (deadline and epoch(deadline) <= self.clock()):
                applicability = "expired" if publication != "draft" else "unknown"
            elif start and epoch(start) > self.clock():
                applicability = "not_started"
            elif meta.get("applicability") == "unknown":
                applicability = "unknown"
            elif (
                p["kind"] in ("scene", "knowledge", "announcement", "reply")
                and not self.voice_live(db, p)
            ) or (p["kind"] == "question" and p["question_status"] in ("cancelled", "expired")):
                applicability = "expired"
            root = self.exchange_root(db, p)
            recipients = []
            if p["kind"] == "answer":
                recipients = [self.post(db, p["reply_to"])["author_id"]]
            elif meta.get("recipient_id"):
                recipients = [meta["recipient_id"]]
            acting = db.execute(
                "SELECT 1 FROM exchange_turns WHERE post_id=? AND user_id=?",
                (message_id, p["author_id"]),
            ).fetchone()
            delivery = db.execute(
                "SELECT state FROM deliveries WHERE post_id=? AND recipient_id=? "
                "ORDER BY created_at DESC LIMIT 1",
                (message_id, user),
            ).fetchone()
            # No coordinates, bindings, private destinations, or other users' receipts.
            data = {
                "message_id": p["id"],
                "kind": p["kind"],
                "time": {
                    "observed_at": meta.get("observed_at"),
                    "recorded_at": meta.get("recorded_at"),
                    "published_at": stamp(p["published_at"]),
                    "collected_at": stamp(p["created_at"]),
                    "valid_from": start,
                    "valid_until": deadline,
                },
                "place": {
                    "about_place_id": p["target_place_id"],
                    "label": payload["place_name"],
                    "scope": meta.get("scope", "poi"),
                    "scope_label": meta.get("scope_label"),
                    "direction": p["direction"],
                    "recorded_location_ref": f"owner-location:{p['id']}"
                    if p["author_id"] == user and p["recorded_location"]
                    else None,
                },
                "subject": {
                    "author_id": p["author_id"],
                    "principal_id": p["author_id"],
                    "acting_agent_id": f"personal:{p['author_id']}" if acting else None,
                    "source_type": "poi_operator" if meta.get("channel_id") else "personal",
                    "channel_id": meta.get("channel_id"),
                },
                "body": {
                    "audio_ref": f"/v1/audio/{p['id']}",
                    "transcript": p["transcript"],
                    "content_origin": payload.get("content_origin", "human_original"),
                },
                "state": {
                    "publication": publication,
                    "applicability": applicability,
                    "task": p["question_status"] if p["author_id"] == user else None,
                    "delivery": delivery["state"] if delivery else None,
                },
                "visibility": "public",
                "recipient_ids": recipients,
                "conversation_id": root,
                "reply_to_message_id": p["reply_to"],
                "original_request_id": p["client_request_id"] if p["author_id"] == user else None,
                "replaces_message_id": meta.get("replaces"),
            }
        return VoiceMessageView.model_validate(data).model_dump(mode="json")
