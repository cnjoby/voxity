import array
import hashlib
import io
import json
import wave
from collections import Counter

from conftest import auth

from app.demo_audio import ASSETS, USER, import_demo


def test_complete_audible_fixture_and_idempotent_import(env):
    c, s, now, _ = env
    manifest = json.loads((ASSETS / "manifest.json").read_text())
    assert len(manifest["places"]) == 3
    assert len(manifest["clips"]) == 36
    assert len({clip["id"] for clip in manifest["clips"]}) == 36
    if manifest["version"] >= 2:
        assert manifest["tts_engine"]["model"].endswith("CustomVoice-6bit")
        assert all(clip["tts"]["natural_speed"] for clip in manifest["clips"])
        assert not any(clip["voice"] == "Tingting" for clip in manifest["clips"])
    assert Counter(clip["category"] for clip in manifest["clips"]) == {
        "公告": 12,
        "路况": 12,
        "闲聊": 12,
    }
    before = c.get("/v1/me", headers=auth()).json()["profile"]
    for clip in manifest["clips"]:
        data = (ASSETS / clip["file"]).read_bytes()
        assert hashlib.sha256(data).hexdigest() == clip["sha256"]
        assert clip["transcript"].startswith(("模拟", "测试闲聊"))
        with wave.open(io.BytesIO(data)) as wav:
            duration = wav.getnframes() / wav.getframerate()
            assert 0 < duration <= 8
            assert abs(duration - clip["duration_seconds"]) < 0.001
            samples = array.array("h", wav.readframes(wav.getnframes()))
            assert (
                sum(x * x for x in samples) / len(samples) > 10000
            )  # Speech, not a silent fixture.
    assert import_demo(s) == 36
    assert import_demo(s) == 0
    assert c.get("/v1/me", headers=auth()).json()["profile"] == before
    with s.scope() as db:
        assert (
            db.execute("SELECT count(*) FROM posts WHERE author_id=?", (USER,)).fetchone()[0] == 36
        )
        for place in manifest["places"]:
            items = s.catalog(db, "alice", place["id"])["items"]
            assert len(items) == 12
            assert all(
                s.voice_meta(db, item["post_id"])[0]["applicability"] == "unknown" for item in items
            )
    now[0] += 86401
    assert import_demo(s) == 0
    with s.scope() as db:
        for place in manifest["places"]:
            assert not any(
                item["kind"] == "announcement"
                for item in s.catalog(db, "alice", place["id"])["items"]
            )


def test_voice_refresh_retires_only_prior_generated_fixtures(env, tmp_path):
    import shutil

    c, s, _, _ = env
    manifest = json.loads((ASSETS / "manifest.json").read_text())
    for item in manifest["clips"]:
        shutil.copy2(ASSETS / item["file"], tmp_path / item["file"])
    manifest["version"] = 1
    (tmp_path / "manifest.json").write_text(json.dumps(manifest))
    assert import_demo(s, tmp_path) == 36
    with s.scope() as db:
        old_ids = {r[0] for r in db.execute("SELECT id FROM posts WHERE author_id=?", (USER,))}
    before = c.get("/v1/me", headers=auth()).json()["profile"]
    manifest["version"] = 2
    (tmp_path / "manifest.json").write_text(json.dumps(manifest))
    assert import_demo(s, tmp_path) == 36
    assert import_demo(s, tmp_path) == 0
    assert c.get("/v1/me", headers=auth()).json()["profile"] == before
    with s.scope() as db:
        for pid in old_ids:
            assert s.voice_meta(db, pid)[1]["withdrawn_at"] is not None
            assert (s.store.audio_dir / (pid + ".wav")).exists()
        for place in manifest["places"]:
            items = s.catalog(db, "alice", place["id"])["items"]
            assert len(items) == 12
            assert not old_ids.intersection(item["post_id"] for item in items)
