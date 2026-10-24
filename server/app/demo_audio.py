"""Explicit, idempotent import of bundled synthetic audio into a local test database."""

import argparse
import hashlib
import json
import secrets
from pathlib import Path
from uuid import NAMESPACE_URL, uuid5

from .audio import validate_wav
from .config import Settings
from .models import VoiceDraft
from .service import Service, stamp
from .storage import digest

ASSETS = Path(__file__).resolve().parents[2] / "android/app/src/main/assets/demo"
USER = "synthetic_audio_test"


def fixture_request_id(version, clip_id):
    return str(uuid5(NAMESPACE_URL, f"sonar/demo-audio/v{version}/{clip_id}"))


def import_demo(service, assets=ASSETS):
    manifest = json.loads((assets / "manifest.json").read_text())
    version = manifest.get("version", 1)
    if not isinstance(version, int) or not 1 <= version <= 100:
        raise ValueError("Unsupported demo audio version")
    # Validate every file before the first DB mutation.
    for clip in manifest["clips"]:
        audio = (assets / clip["file"]).read_bytes()
        validate_wav(audio)
        if hashlib.sha256(audio).hexdigest() != clip["sha256"] or not clip["synthetic"]:
            raise ValueError("Invalid synthetic audio fixture: " + clip["id"])
    inserted = 0
    with service.scope() as db:
        db.execute(
            "INSERT OR IGNORE INTO users VALUES (?,?,?,?)",
            (USER, digest(secrets.token_urlsafe(48)), "synthetic_audio_test_device", ""),
        )
        db.execute(
            "INSERT OR IGNORE INTO profiles (user_id,place_id,direction,source,updated_at,assist_enabled,mode,focus,revision) VALUES (?,?,'unknown','unavailable',?,0,'quiet','合成测试音频',1)",
            (USER, manifest["places"][0]["id"], service.clock()),
        )
        for place in manifest["places"]:
            db.execute(
                "INSERT OR IGNORE INTO places VALUES (?,?,?,?,?,?)",
                (
                    place["id"],
                    place["label"],
                    place["latitude"],
                    place["longitude"],
                    "poi",
                    service.clock(),
                ),
            )
            cid = place["id"] + ":test-notices"
            db.execute(
                "INSERT OR IGNORE INTO channels VALUES (?,?,?,?,1)",
                (cid, place["id"], "合成测试公告", "测试音频（非真实运营方）"),
            )
            db.execute("INSERT OR IGNORE INTO channel_operators VALUES (?,?)", (cid, USER))
        for clip in manifest["clips"]:
            request_id = fixture_request_id(version, clip["id"])
            # Never renew an expired/withdrawn fixture or overwrite its immutable metadata.
            if db.execute(
                "SELECT 1 FROM posts WHERE author_id=? AND client_request_id=?", (USER, request_id)
            ).fetchone():
                continue
            announcement = clip["category"] == "公告"
            fields = dict(
                client_request_id=request_id,
                kind="announcement" if announcement else "scene",
                transcript=clip["transcript"],
                target_place_id=clip["place_id"],
                purpose="notice" if announcement else "experience",
                applicability="unknown",  # Fixtures are always queried deliberately, never auto recommended.
                source_note="合成测试音频；不是实时公告、路况或现场发言。",
                topics=["合成测试", clip["category"]],
            )
            if announcement:
                start = service.clock()
                fields.update(
                    channel_id=clip["place_id"] + ":test-notices",
                    valid_from=stamp(start),
                    valid_until=stamp(start + min(86400, service.settings.announcement_max_ttl)),
                )
            post, _ = service.draft(
                db, USER, VoiceDraft.model_validate(fields), (assets / clip["file"]).read_bytes()
            )
            service.confirm(db, USER, post["post_id"])
            inserted += 1
        # Keep immutable prior audio/history; retire only this generator's known older
        # fixture IDs after the replacement batch has been validated and published.
        for clip in manifest["clips"]:
            for previous in range(1, version):
                old = db.execute(
                    "SELECT id FROM posts WHERE author_id=? AND client_request_id=? AND target_place_id=?",
                    (USER, fixture_request_id(previous, clip["id"]), clip["place_id"]),
                ).fetchone()
                if old:
                    service.withdraw_voice(db, USER, old["id"])
    return inserted


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, default=ASSETS)
    args = parser.parse_args()
    count = import_demo(Service(Settings()), args.assets)
    print(
        f"Imported {count} synthetic voices; existing profiles, tasks and voices preserved. Test announcements expire within 24h; offline previews remain available."
    )


if __name__ == "__main__":
    main()
