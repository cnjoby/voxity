"""One-time upgrade from fixture/hashed place keys to provider POI identities."""

import argparse
import json
from datetime import UTC, datetime

from .storage import Database, digest
from .voice_first import PILOT_PLACES

MARKER = "raw_amap_places_20261025_v1"
LEGACY_POINTS = {
    "demo:modaily": "B0K2KHYN77",
    "demo:ai-island-19": "B0FFJSBSGR",
    "demo:zhangjiang-jinke": "B0FFHPHNSH",
    "zhonghuan_jinke": "B0FFHPHNSH",
}


def migrate_places(store, clock):
    mapping = {**LEGACY_POINTS}
    # The broader AI Island is its own real POI, not building 19.
    for provider_id in (*PILOT_PLACES, "B0FFJEOSPR"):
        mapping["amap:" + digest(provider_id)[:24]] = provider_id
    with store.transaction() as db:
        if db.execute("SELECT 1 FROM schema_migrations WHERE id=?", (MARKER,)).fetchone():
            return {"applied": False, "reason": "already_migrated"}
        before = db.execute("SELECT count(*) FROM posts").fetchone()[0]
        identities = [tuple(r) for r in db.execute("SELECT * FROM users ORDER BY id")]
        for pid, (label, lat, lon, _) in PILOT_PLACES.items():
            db.execute(
                "INSERT INTO places VALUES (?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET label=excluded.label,latitude=excluded.latitude,longitude=excluded.longitude",
                (pid, label, lat, lon, "poi", clock()),
            )
        changed = 0
        for old_id, provider_id in mapping.items():
            old_place = db.execute("SELECT * FROM places WHERE id=?", (old_id,)).fetchone()
            place = db.execute("SELECT * FROM places WHERE id=?", (provider_id,)).fetchone()
            if not place and old_place:
                db.execute(
                    "INSERT INTO places VALUES (?,?,?,?,?,?)",
                    (
                        provider_id,
                        old_place["label"],
                        old_place["latitude"],
                        old_place["longitude"],
                        old_place["kind"],
                        old_place["created_at"],
                    ),
                )
                place = db.execute("SELECT * FROM places WHERE id=?", (provider_id,)).fetchone()
            if not place:
                continue
            changed += db.execute(
                "UPDATE posts SET target_place_id=?,target_label=?,target_latitude=?,target_longitude=? WHERE target_place_id=?",
                (provider_id, place["label"], place["latitude"], place["longitude"], old_id),
            ).rowcount
            for p in db.execute("SELECT * FROM profiles WHERE place_id=?", (old_id,)).fetchall():
                if p["source"] in ("simulated", "simulated_place", "remote_place"):
                    sampled = p["sampled_at"] if p["sampled_at"] is not None else p["updated_at"]
                    fix = json.dumps(
                        {
                            "latitude": place["latitude"],
                            "longitude": place["longitude"],
                            "coordinate_system": "gcj02",
                            "accuracy_m": 30,
                            "sampled_at": datetime.fromtimestamp(sampled, UTC).isoformat(),
                            "source": "remote_place",
                        }
                    )
                    db.execute(
                        "UPDATE profiles SET place_id=?,source='remote_place',revision=revision+1,latitude=?,longitude=?,sampled_at=?,accuracy_m=30,location_json=? WHERE user_id=?",
                        (
                            provider_id,
                            place["latitude"],
                            place["longitude"],
                            sampled,
                            fix,
                            p["user_id"],
                        ),
                    )
                else:
                    db.execute(
                        "UPDATE profiles SET place_id=? WHERE user_id=?",
                        (provider_id, p["user_id"]),
                    )
            for table in (
                "channels",
                "exchange_sessions",
                "personal_tasks",
                "user_destinations",
                "remote_place_choices",
            ):
                db.execute(f"UPDATE {table} SET place_id=? WHERE place_id=?", (provider_id, old_id))
            for old_visit in db.execute(
                "SELECT * FROM voice_visits WHERE place_id=?", (old_id,)
            ).fetchall():
                existing = db.execute(
                    "SELECT * FROM voice_visits WHERE user_id=? AND place_id=?",
                    (old_visit["user_id"], provider_id),
                ).fetchone()
                if existing:
                    recent = max(
                        (
                            v
                            for v in (existing["last_scene_at"], old_visit["last_scene_at"])
                            if v is not None
                        ),
                        default=None,
                    )
                    db.execute(
                        "UPDATE voice_visits SET scene_count=?,last_scene_at=? WHERE user_id=? AND place_id=?",
                        (
                            max(existing["scene_count"], old_visit["scene_count"]),
                            recent,
                            old_visit["user_id"],
                            provider_id,
                        ),
                    )
                    db.execute(
                        "DELETE FROM voice_visits WHERE user_id=? AND place_id=?",
                        (old_visit["user_id"], old_id),
                    )
                else:
                    db.execute(
                        "UPDATE voice_visits SET place_id=? WHERE user_id=? AND place_id=?",
                        (provider_id, old_visit["user_id"], old_id),
                    )
        if db.execute(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='simulation_choices'"
        ).fetchone():
            for choice in db.execute("SELECT * FROM simulation_choices").fetchall():
                selected = mapping.get(choice["place_id"], choice["place_id"])
                if (
                    selected is None
                    or db.execute("SELECT 1 FROM places WHERE id=?", (selected,)).fetchone()
                ):
                    db.execute(
                        "INSERT OR IGNORE INTO remote_place_choices VALUES (?,?,?)",
                        (choice["user_id"], selected, choice["selected_at"]),
                    )
        # Old in-flight grants do not carry into the single new protocol.
        db.execute(
            "UPDATE exchange_sessions SET state='ended',revision=revision+1 WHERE state!='ended'"
        )
        assert db.execute("SELECT count(*) FROM posts").fetchone()[0] == before
        assert [tuple(r) for r in db.execute("SELECT * FROM users ORDER BY id")] == identities
        assert not db.execute("PRAGMA foreign_key_check").fetchall()
        db.execute("INSERT INTO schema_migrations VALUES (?,?)", (MARKER, clock()))
        return {
            "applied": True,
            "post_count": before,
            "retargeted_posts": changed,
            "identities_preserved": True,
        }


def main():
    import time
    from pathlib import Path

    parser = argparse.ArgumentParser()
    parser.add_argument("--data-dir", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(migrate_places(Database(args.data_dir), time.time), ensure_ascii=False))


if __name__ == "__main__":
    main()
