"""Select registered AMap POIs without claiming physical GPS presence."""

import json
import re
from datetime import UTC, datetime

from .errors import Problem
from .models import LocationFix, LocationUpdate


class RemotePlaceRules:
    def park_for_place(self, db, place_id):
        rows = db.execute(
            "WITH RECURSIVE ancestors(id,depth) AS (SELECT ?,0 UNION ALL "
            "SELECT p.parent_id,a.depth+1 FROM place_parents p JOIN ancestors a ON p.child_id=a.id WHERE a.depth<8) "
            "SELECT p.* FROM ancestors a JOIN places p ON p.id=a.id "
            "WHERE a.depth>0 OR EXISTS(SELECT 1 FROM place_parents x WHERE x.parent_id=a.id) "
            "ORDER BY a.depth DESC LIMIT 1",
            (place_id,),
        ).fetchone()
        return dict(rows) if rows else None

    def shared_park(self, db, first, second):
        a, b = self.park_for_place(db, first), self.park_for_place(db, second)
        return a if a and b and a["id"] == b["id"] else None

    def question_place_ids(self, db, place_id):
        park = self.park_for_place(db, place_id)
        if not park:
            return [place_id]
        return [
            r[0]
            for r in db.execute(
                "WITH RECURSIVE members(id,depth) AS (SELECT ?,0 UNION ALL "
                "SELECT p.child_id,m.depth+1 FROM place_parents p JOIN members m ON p.parent_id=m.id WHERE m.depth<8) "
                "SELECT DISTINCT id FROM members",
                (park["id"],),
            )
        ]

    def question_match_scope(self, db, place_id):
        park = self.park_for_place(db, place_id)
        row = db.execute("SELECT * FROM places WHERE id=?", (place_id,)).fetchone()
        place = park or (dict(row) if row else {"id": place_id, "label": place_id})
        return {"kind": "park" if park else "poi", "place_id": place["id"], "label": place["label"]}

    def remote_place_pin(self, db, user):
        if db.execute("SELECT 1 FROM remote_place_choices WHERE user_id=?", (user,)).fetchone():
            return None
        return self.settings.remote_place_users.get(user)

    def information_place_ids(self, db, place_id):
        # One provider identity. Historical demo IDs are migrated, never used as aliases.
        return [place_id]

    def message_author_label(self, post):
        location = json.loads(post["recorded_location"] or "{}")
        suffix = {
            "simulated_place": "（历史模拟位置）",
            "remote_place": "（远程地点）",
        }.get(location.get("source"), "")
        return f"参与者 {post['author_id']}{suffix}"

    def is_remote_place(self, profile):
        return bool(
            profile["source"] == "remote_place"
            and re.fullmatch(r"[A-Z][A-Z0-9]{7,31}", profile["place_id"])
            and profile["latitude"] is not None
            and profile["longitude"] is not None
        )

    def remote_location(self, db, place_id, revision):
        place = db.execute("SELECT * FROM places WHERE id=?", (place_id,)).fetchone()
        if not place or not re.fullmatch(r"[A-Z][A-Z0-9]{7,31}", place_id):
            raise Problem(422, "REMOTE_PLACE_UNKNOWN", "请先通过高德搜索选择真实地点")
        fix = LocationFix(
            latitude=place["latitude"],
            longitude=place["longitude"],
            coordinate_system="gcj02",
            accuracy_m=30,
            sampled_at=datetime.fromtimestamp(self.clock(), UTC),
            source="remote_place",
        )
        return LocationUpdate(expected_revision=revision, location=fix), {
            "latitude": place["latitude"],
            "longitude": place["longitude"],
            "places": [dict(place)],
        }

    def select_remote_place(self, db, user, data):
        location, resolved = self.remote_location(db, data.place_id, data.expected_revision)
        db.execute(
            "INSERT INTO remote_place_choices VALUES (?,?,?) ON CONFLICT(user_id) DO UPDATE SET place_id=excluded.place_id,selected_at=excluded.selected_at",
            (user, data.place_id, self.clock()),
        )
        return self.update_location(db, user, location, resolved)
