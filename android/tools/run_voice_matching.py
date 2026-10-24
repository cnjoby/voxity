"""Isolated voice-matching emulator acceptance; synthetic transcripts/peer, no external API.

uv run --project server python android/tools/run_voice_matching.py --serial emulator-5554
Does not read normal server credentials or modify its persisted database.
"""

import argparse
import json
import os
import secrets
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "server"))


def serve(config_path):
    import uvicorn

    from app.config import Settings
    from app.main import create_app
    from app.models import LocationUpdate
    from app.service import stamp

    config = json.loads(Path(config_path).read_text())
    app = create_app(Settings(_env_file=None, **config))
    service = app.state.service
    service.seed()
    place = {
        "id": "voice_test",
        "label": "演示观景点停车场",
        "latitude": 31.2,
        "longitude": 121.4,
        "kind": "poi",
    }
    with service.scope() as db:
        db.execute(
            "INSERT INTO places VALUES (?,?,?,?,?,?)",
            (*place.values(), service.clock()),
        )
        db.execute(
            "INSERT INTO channels VALUES ('parking','voice_test','停车场演示频道','停车场运营方',1)"
        )
        db.execute("INSERT INTO channel_operators VALUES ('parking','bob')")
        db.execute("UPDATE profiles SET mode='quiet' WHERE user_id='alice'")
        for user in ("alice", "bob"):
            service.update_location(
                db,
                user,
                LocationUpdate.model_validate(
                    {
                        "expected_revision": 1,
                        "location": {
                            "latitude": 31.2,
                            "longitude": 121.4,
                            "accuracy_m": 5,
                            "sampled_at": stamp(service.clock()),
                            "source": "android_mock",
                        },
                    }
                ),
                {"latitude": 31.2, "longitude": 121.4, "places": [place]},
            )
    # Test-only adapter: refreshes the explicit injected location, never a physical-arrival claim.
    service.geo.resolve = lambda fix: {
        "latitude": fix.latitude,
        "longitude": fix.longitude,
        "places": [place],
    }
    service.geo.search = lambda query: [place]
    uvicorn.run(app, host="127.0.0.1", port=8767, access_log=False, log_level="warning")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial")
    parser.add_argument("--serve")
    args = parser.parse_args()
    if args.serve:
        serve(args.serve)
        return
    if not args.serial:
        parser.error("--serial is required")
    adb = ["adb", "-s", args.serial]
    if (
        subprocess.check_output(adb + ["shell", "getprop", "ro.kernel.qemu"], text=True).strip()
        != "1"
    ):
        raise SystemExit("This test requires an emulator")
    report = ROOT / "android/build/reports/voice-matching"
    report.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="sonar-voice-check-") as folder:
        path = Path(folder) / "private.json"
        config = {
            "data_dir": str(Path(folder) / "data"),
            "alice_token": secrets.token_urlsafe(32),
            "bob_token": secrets.token_urlsafe(32),
            "allow_mock_location": True,
            "location_ttl": 600,
        }
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as file:
            json.dump(config, file)
        server = subprocess.Popen([sys.executable, __file__, "--serve", str(path)])
        try:
            for _ in range(100):
                try:
                    if httpx.get("http://127.0.0.1:8767/healthz", timeout=1).is_success:
                        break
                except httpx.HTTPError:
                    pass
                if server.poll() is not None:
                    raise RuntimeError("Test server did not start")
                time.sleep(0.1)
            else:
                raise RuntimeError("Test server not ready")
            for name in (
                "app/build/outputs/apk/debug/app-debug.apk",
                "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
            ):
                subprocess.run(
                    adb + ["install", "-r", str(ROOT / "android" / name)],
                    check=True,
                    capture_output=True,
                )
            subprocess.run(
                adb + ["reverse", "tcp:8767", "tcp:8767"],
                check=True,
                capture_output=True,
            )
            subprocess.run(adb + ["shell", "am", "force-stop", "com.sonar.app"], check=True)
            for user, filename in (
                ("alice", "bootstrap.json"),
                ("bob", "voice-peer.json"),
            ):
                device = {
                    "base_url": "http://127.0.0.1:8767",
                    "token": config[user + "_token"],
                    "debug_transcript": True,
                    "simulated_location": True,
                }
                subprocess.run(
                    adb
                    + [
                        "shell",
                        "run-as",
                        "com.sonar.app",
                        "sh",
                        "-c",
                        f"'mkdir -p files && cat > files/{filename}'",
                    ],
                    input=json.dumps(device).encode(),
                    check=True,
                )
            for permission in (
                "RECORD_AUDIO",
                "ACCESS_FINE_LOCATION",
                "ACCESS_COARSE_LOCATION",
            ):
                subprocess.run(
                    adb
                    + [
                        "shell",
                        "pm",
                        "grant",
                        "com.sonar.app",
                        "android.permission." + permission,
                    ],
                    check=True,
                )
            result = subprocess.run(
                adb
                + [
                    "shell",
                    "am",
                    "instrument",
                    "-w",
                    "-e",
                    "voice_matching",
                    "true",
                    "-e",
                    "class",
                    "com.sonar.app.VoiceMatchingTest",
                    "com.sonar.app.test/androidx.test.runner.AndroidJUnitRunner",
                ],
                check=False,
                capture_output=True,
                text=True,
                timeout=240,
            )
            (report / "instrumentation.txt").write_text(result.stdout + result.stderr)
            for name in (
                "voice-catalog",
                "voice-composer",
                "voice-draft",
                "voice-conversation",
                "voice-settings",
            ):
                image = subprocess.run(
                    adb
                    + [
                        "exec-out",
                        "run-as",
                        "com.sonar.app",
                        "cat",
                        f"files/test-{name}.png",
                    ],
                    check=False,
                    capture_output=True,
                )
                if image.returncode == 0 and image.stdout.startswith(b"\x89PNG\r\n\x1a\n"):
                    (report / (name + ".png")).write_bytes(image.stdout)
            print(result.stdout)
            if result.returncode or "FAILURES" in result.stdout or "OK (" not in result.stdout:
                raise SystemExit("Voice matching emulator check failed; see instrumentation.txt")
            print(
                "Isolated emulator check passed. Injected location, manual transcript and synthetic peer only."
            )
        finally:
            subprocess.run(
                adb + ["shell", "am", "force-stop", "com.sonar.app"],
                check=False,
                capture_output=True,
            )
            server.terminate()
            server.wait(timeout=10)
            subprocess.run(
                adb
                + [
                    "shell",
                    "run-as",
                    "com.sonar.app",
                    "rm",
                    "-f",
                    "files/voice-peer.json",
                ],
                check=False,
                capture_output=True,
            )


if __name__ == "__main__":
    main()
