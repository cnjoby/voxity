"""Permission and time gates precede purpose selection; retrieval never grants presence."""

import re

from .errors import Problem
from .voices import epoch

PATTERNS = {
    "parking": r"车位|停车|车库|满位|满额|B[0-9]",
    "weather": r"天气|下雨|雨|雪|气温|降温",
    "route": r"路况|拥堵|堵|缓行|事故|封闭|通行|通畅",
}


class InformationRules:
    def information_live(self, db, user, post):
        if post["status"] != "published" or not self.can_read_audio(db, user, post):
            return False
        if post["kind"] == "question":
            return post["question_status"] == "waiting" and post["expires_at"] > self.clock()
        if post["kind"] in ("scene", "knowledge", "announcement", "reply"):
            if not self.voice_live(db, post):
                return False
        if post["kind"] in ("answer", "reply"):
            deadline = self.voice_payload(db, post).get("valid_until")
            if deadline and epoch(deadline) <= self.clock():
                return False
        return post["kind"] in ("scene", "knowledge", "announcement", "answer", "reply")

    def information_candidate(self, db, user, post, query="", purpose="all"):
        if not self.information_live(db, user, post) or post["kind"] == "question":
            return None
        meta, _ = self.voice_meta(db, post["id"])
        meta = meta or {}
        historical = post["kind"] == "knowledge" or (
            post["kind"] == "scene" and meta.get("purpose") != "observation"
        )
        if purpose in PATTERNS:
            # Long-lived knowledge/reflections cannot answer a dynamic state query.
            if (
                historical
                or meta.get("applicability") == "unknown"
                or meta.get("validity_policy") == "until_withdrawn"
            ):
                return None
            if not re.search(
                PATTERNS[purpose], post["transcript"] + " ".join(meta.get("topics", [])), re.I
            ):
                return None
            # An answer without any bounded observation validity is historical evidence.
            if not self.voice_payload(db, post).get("valid_until"):
                return None
            if post["kind"] in ("answer", "reply"):
                recorded = epoch(meta.get("recorded_at")) or post["published_at"]
                if (
                    self.information_expiry(recorded, self.settings.observation_max_ttl)
                    <= self.clock()
                ):
                    return None
        elif purpose == "knowledge" and not historical:
            return None
        for topic in ("停车", "车位", "入口", "营业", "开放", "设施"):
            if topic in query and not re.search(
                r"停车|车位|车库|B[0-9]" if topic in ("停车", "车位") else topic,
                post["transcript"] + " ".join(meta.get("topics", [])),
                re.I,
            ):
                return None
        payload = self.voice_payload(db, post)
        payload.update(
            {
                "observed_at": meta.get("observed_at"),
                "content_origin": payload.get("content_origin", "human_original"),
                "temporal_class": "standing_notice"
                if meta.get("validity_policy") == "until_withdrawn"
                else "historical_knowledge"
                if historical
                else "bounded_report",
                "observation_time_known": bool(meta.get("observed_at")),
                "retrieval_reason": "明确地点的合法有效内容",
                "version": post["id"],
                "retrieval_purpose": purpose,
            }
        )
        return payload

    def find_information(self, db, user, place_id, query="", purpose="all", limit=20, kinds=None):
        if purpose not in ("all", "parking", "weather", "route", "knowledge"):
            raise Problem(422, "INVALID_PURPOSE", "不支持的信息用途")
        places = self.information_place_ids(db, place_id)
        placeholders = ",".join("?" for _ in places)
        rows = db.execute(
            f"SELECT * FROM posts WHERE target_place_id IN ({placeholders}) AND status='published' "
            "AND kind IN ('announcement','scene','knowledge','answer','reply') "
            "ORDER BY CASE kind WHEN 'announcement' THEN 0 WHEN 'scene' THEN 1 "
            "WHEN 'answer' THEN 2 WHEN 'reply' THEN 2 ELSE 3 END,published_at DESC,id",
            places,
        )
        items = []
        for post in rows:
            if kinds and post["kind"] not in kinds:
                continue
            candidate = self.information_candidate(db, user, post, query, purpose)
            if candidate:
                items.append(candidate)
                if len(items) >= min(max(limit, 1), 100):
                    break
        return {
            "place_id": place_id,
            "purpose": purpose,
            "items": items,
            "status": "available" if items else "no_valid_information",
            "requires_source_comparison": len(items) > 1,
            **self.now(),
        }

    def validate_information(self, db, user, message_ids, purpose="all"):
        valid = []
        for pid in dict.fromkeys(message_ids):
            post = db.execute("SELECT * FROM posts WHERE id=?", (pid,)).fetchone()
            if post and self.information_candidate(db, user, post, purpose=purpose):
                valid.append(pid)
        return {
            "valid": len(valid) == len(set(message_ids)),
            "valid_message_ids": valid,
            **self.now(),
        }

    def information_response(self, found):
        selected = found["items"][:3]
        parts, sources = [], []
        for item in selected:
            label = item["author_label"]
            at = item.get("recorded_at") or item["published_at"]
            time_label = "录制" if item.get("recorded_at") else "发布（观察时间未提供）"
            scope = item.get("scope_label") or {
                "poi": "整个地点",
                "entrance": "入口",
                "floor": "指定楼层",
                "area": "指定区域",
            }.get(item.get("scope"), "报告所述范围")
            parts.append(f"{label}于{at}{time_label}，适用范围：{scope}。{item['transcript']}")
            sources.append(
                {
                    k: item.get(k)
                    for k in (
                        "post_id",
                        "source_type",
                        "author_label",
                        "channel_label",
                        "content_origin",
                        "target_place_id",
                        "kind",
                        "scope",
                        "scope_label",
                        "observed_at",
                        "recorded_at",
                        "published_at",
                        "valid_from",
                        "valid_until",
                        "version",
                        "audio_url",
                        "temporal_class",
                        "retrieval_purpose",
                    )
                }
            )
        if len(selected) > 1:
            parts.append("这些来源分别报告各自范围和时间的情况，不能合并为统一现场结论。")
        return {
            "speaker": "agent",
            "kind": "place_information",
            "text": " ".join(parts),
            "sources": sources,
        }
