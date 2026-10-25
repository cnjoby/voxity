import hashlib
import json

from app.demo_audio import import_demo
from app.place_migration import migrate_places


def test_real_poi_upgrade_preserves_audio_identity_and_historical_origin(env):
    client, service, now, settings = env
    assert import_demo(service, shared=True) == 36
    with service.scope() as db:
        identities = [tuple(row) for row in db.execute("SELECT * FROM users ORDER BY id")]
        place = db.execute("SELECT * FROM places WHERE id='B0K2KHYN77'").fetchone()
        db.execute(
            "INSERT INTO places VALUES (?,?,?,?,?,?)",
            ("demo:modaily", place["label"], place["latitude"], place["longitude"], "poi", now[0]),
        )
        db.execute(
            "UPDATE posts SET target_place_id='demo:modaily' WHERE target_place_id='B0K2KHYN77'"
        )
        db.execute(
            "CREATE TABLE simulation_choices(user_id TEXT PRIMARY KEY,place_id TEXT,selected_at REAL)"
        )
        db.execute("INSERT INTO simulation_choices VALUES ('alice','demo:modaily',?)", (now[0],))
        snapshot = {
            row["id"]: (row["fingerprint"], row["recorded_location"])
            for row in db.execute("SELECT * FROM posts")
        }
    audio_before = {
        p.name: hashlib.sha256(p.read_bytes()).hexdigest()
        for p in service.store.audio_dir.glob("*.wav")
    }
    result = migrate_places(service.store, lambda: now[0])
    assert result == {
        "applied": True,
        "post_count": 36,
        "retargeted_posts": 12,
        "identities_preserved": True,
    }
    with service.scope() as db:
        assert [tuple(row) for row in db.execute("SELECT * FROM users ORDER BY id")] == identities
        assert (
            db.execute(
                "SELECT place_id FROM remote_place_choices WHERE user_id='alice'"
            ).fetchone()[0]
            == "B0K2KHYN77"
        )
        assert len(service.catalog(db, "alice", "B0K2KHYN77")["items"]) == 12
        for row in db.execute("SELECT * FROM posts"):
            assert snapshot[row["id"]] == (row["fingerprint"], row["recorded_location"])
    assert audio_before == {
        p.name: hashlib.sha256(p.read_bytes()).hexdigest()
        for p in service.store.audio_dir.glob("*.wav")
    }
    assert not migrate_places(service.store, lambda: now[0])["applied"]


def test_duplicate_place_visits_merge_without_losing_shared_audio(env):
    _, service, now, _ = env
    import_demo(service, shared=True)
    with service.scope() as db:
        place = db.execute("SELECT * FROM places WHERE id='B0K2KHYN77'").fetchone()
        db.execute(
            "INSERT INTO places VALUES (?,?,?,?,?,?)",
            ("demo:modaily", place["label"], place["latitude"], place["longitude"], "poi", now[0]),
        )
        db.execute(
            "INSERT OR REPLACE INTO voice_visits VALUES (?,?,?,?,?,?)",
            ("alice", "demo:modaily", "old-visit", None, 2, now[0]),
        )
        db.execute(
            "INSERT OR REPLACE INTO voice_visits VALUES (?,?,?,?,?,?)",
            ("alice", "B0K2KHYN77", "current-visit", None, 1, now[0] - 5),
        )
        db.execute(
            "UPDATE profiles SET place_id='demo:modaily',source='simulated_place',updated_at=?,location_json=? WHERE user_id='alice'",
            (now[0] - 600, json.dumps({"source": "simulated_place"})),
        )
    migrate_places(service.store, lambda: now[0])
    with service.scope() as db:
        visits = db.execute("SELECT * FROM voice_visits WHERE user_id='alice'").fetchall()
        assert len(visits) == 1 and visits[0]["scene_count"] == 2
        profile = service.profile_row(db, "alice")
        assert profile["source"] == "remote_place" and profile["sampled_at"] == now[0] - 600
        assert not db.execute("PRAGMA foreign_key_check").fetchall()
