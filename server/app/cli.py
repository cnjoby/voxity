"""Local configuration and idempotent seed. Never print credentials."""

import argparse
import os
import secrets
from pathlib import Path

from .config import Settings
from .service import Service


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["init-env", "seed"])
    args = parser.parse_args()
    if args.command == "init-env":
        path = Path(".env")
        template = Path(__file__).resolve().parent.parent / ".env.example"
        content = template.read_text()
        for key in ("SONAR_ALICE_TOKEN", "SONAR_BOB_TOKEN", "SONAR_MCP_SECRET"):
            content = content.replace(f"{key}=\n", f"{key}={secrets.token_urlsafe(32)}\n")
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write(content)
        print("Created private .env; MCP remains disabled. Credentials were not printed.")
    else:
        Service(Settings()).seed()
        print("Seeded alice/bob; existing profiles and tasks preserved.")


if __name__ == "__main__":
    main()
