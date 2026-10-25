"""Publish explicitly labelled, long-lived Modaily guidance from a checked local manifest."""

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

USER = "modaily_notice_demo"
CHANNEL = "modaily:entry-notices:demo"
PLACE = "B0K2KHYN77"


def import_notices(service, assets):
    assets = Path(assets).resolve()
    manifest = json.loads((assets / "manifest.json").read_text())
    if manifest["poi_id"] != PLACE or manifest["synthetic"] is not True:
        raise ValueError("Expected the explicitly synthetic Modaily guidance manifest")
    prepared = []
    for clip in manifest["clips"]:
        path = (assets / clip["file"]).resolve()
        if not path.is_relative_to(assets):
            raise ValueError("Audio must be inside the asset directory")
        audio = path.read_bytes()
        validate_wav(audio)
        if hashlib.sha256(audio).hexdigest() != clip["sha256"]:
            raise ValueError("Audio hash mismatch")
        identity = json.dumps(
            {"clip": clip, "source": manifest["source_note"], "validity_policy": "until_withdrawn"},
            sort_keys=True,
        )
        prepared.append(
            (clip, audio, str(uuid5(NAMESPACE_URL, "sonar/modaily-entry/v1/" + identity)))
        )
    result = []
    with service.scope() as db:
        if not db.execute("SELECT 1 FROM places WHERE id=?", (PLACE,)).fetchone():
            raise ValueError("Resolve the Modaily POI before publishing")
        db.execute(
            "INSERT OR IGNORE INTO users VALUES (?,?,?,?)",
            (USER, digest(secrets.token_urlsafe(48)), "modaily_notice_demo_device", ""),
        )
        db.execute(
            "INSERT OR IGNORE INTO profiles (user_id,place_id,direction,source,updated_at,assist_enabled,mode,focus,revision) "
            "VALUES (?,?,'unknown','unavailable',?,0,'quiet','模拟入园须知',1)",
            (USER, PLACE, service.clock()),
        )
        db.execute(
            "INSERT OR IGNORE INTO channels VALUES (?,?,?,?,1)",
            (CHANNEL, PLACE, "模力社区·模拟入园须知", "模力社区模拟公共提示（AI合成）"),
        )
        if service.channel(db, CHANNEL)["place_id"] != PLACE:
            raise ValueError("The notice channel has a different scope")
        db.execute("INSERT OR IGNORE INTO channel_operators VALUES (?,?)", (CHANNEL, USER))
        for clip, audio, request in prepared:
            old = db.execute(
                "SELECT id FROM posts WHERE author_id=? AND client_request_id=?", (USER, request)
            ).fetchone()
            if old:
                result.append({"clip": clip["id"], "post_id": old["id"], "inserted": False})
                continue
            topic = "notice:" + clip["id"]
            replaces = None
            for row in db.execute(
                "SELECT p.id,v.metadata_json FROM posts p JOIN voice_details v ON v.post_id=p.id "
                "WHERE p.author_id=? AND p.status='published' AND v.withdrawn_at IS NULL AND v.replaced_by IS NULL",
                (USER,),
            ):
                meta = json.loads(row["metadata_json"])
                if meta.get("channel_id") == CHANNEL and topic in meta.get("topics", []):
                    replaces = row["id"]
                    break
            data = VoiceDraft(
                client_request_id=request,
                kind="announcement",
                transcript=clip["text"],
                target_place_id=PLACE,
                purpose="notice",
                channel_id=CHANNEL,
                valid_from=stamp(service.clock()),
                valid_until=None,
                validity_policy="until_withdrawn",
                content_origin="agent_synthesized",
                applicability="unconditional" if clip["automatic_arrival"] else "unknown",
                source_note=manifest["source_note"],
                topics=["模拟公共提示", "入园须知", topic],
                replaces=replaces,
            )
            post, _ = service.draft(db, USER, data, audio)
            service.confirm(db, USER, post["post_id"])
            result.append(
                {
                    "clip": clip["id"],
                    "post_id": post["post_id"],
                    "inserted": True,
                    "automatic_arrival": clip["automatic_arrival"],
                    "valid_until": None,
                }
            )
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, required=True)
    args = parser.parse_args()
    print(
        json.dumps(
            {
                "poi": PLACE,
                "synthetic": True,
                "validity_policy": "until_withdrawn",
                "posts": import_notices(Service(Settings()), args.assets),
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
