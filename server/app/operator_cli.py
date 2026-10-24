"""Local, deliberate authorization for a closed POI pilot; no public self-grant API."""

import argparse

from .config import Settings
from .errors import Problem
from .service import Service


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    create = sub.add_parser("create")
    create.add_argument("--channel", required=True)
    create.add_argument("--place", required=True, help="Existing resolved POI ID")
    create.add_argument("--label", required=True)
    create.add_argument("--operator-label", required=True)
    for command in ("grant", "revoke"):
        action = sub.add_parser(command)
        action.add_argument("--channel", required=True)
        action.add_argument("--user", required=True)
    args = parser.parse_args()
    service = Service(Settings())
    with service.scope() as db:
        if args.command == "create":
            if not db.execute("SELECT 1 FROM places WHERE id=?", (args.place,)).fetchone():
                parser.error("Resolve/select this POI through /v1/places before creating a channel")
            if db.execute("SELECT 1 FROM channels WHERE id=?", (args.channel,)).fetchone():
                parser.error("Channel already exists; its identity and scope cannot be overwritten")
            db.execute(
                "INSERT INTO channels VALUES (?,?,?,?,1)",
                (args.channel, args.place, args.label, args.operator_label),
            )
        else:
            try:
                service.channel(db, args.channel)
            except Problem:
                parser.error("Channel does not exist")
            if not db.execute("SELECT 1 FROM users WHERE id=?", (args.user,)).fetchone():
                parser.error("User does not exist")
            if args.command == "grant":
                db.execute(
                    "INSERT OR IGNORE INTO channel_operators VALUES (?,?)",
                    (args.channel, args.user),
                )
            else:
                db.execute(
                    "DELETE FROM channel_operators WHERE channel_id=? AND user_id=?",
                    (args.channel, args.user),
                )
                for p in db.execute(
                    "SELECT p.id FROM posts p JOIN voice_details v ON p.id=v.post_id WHERE p.author_id=? AND json_extract(v.metadata_json,'$.channel_id')=?",
                    (args.user, args.channel),
                ).fetchall():
                    service.invalidate_voice(db, p["id"], "OPERATOR_REVOKED")
    print("Closed-pilot channel authorization updated; existing profiles preserved.")


if __name__ == "__main__":
    main()
