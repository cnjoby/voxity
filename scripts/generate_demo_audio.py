"""Rebuild the bundled Mandarin test voices with macOS speech and ffmpeg."""

import argparse
import hashlib
import json
import subprocess
import tempfile
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "server/demo-assets"
VOICES = [
    "Tingting",
    "Eddy (中文（中国大陆）)",
    "Flo (中文（中国大陆）)",
    "Reed (中文（中国大陆）)",
]


def generate(directory=ASSETS):
    manifest = json.loads((directory / "manifest.json").read_text())
    if manifest.get("version", 1) >= 2:
        raise ValueError(
            "Natural voice assets must be rebuilt with generate_natural_demo_audio.py"
        )
    with tempfile.TemporaryDirectory() as tmp:
        for item in manifest["clips"]:
            raw = Path(tmp) / "speech.aiff"
            subprocess.run(
                [
                    "say",
                    "-v",
                    item["voice"],
                    "-r",
                    "235",
                    "-o",
                    str(raw),
                    item["transcript"],
                ],
                check=True,
            )
            target = directory / item["file"]
            # Never trim speech: speed up an overlength clip and verify the complete result.
            duration = float(
                subprocess.check_output(
                    [
                        "ffprobe",
                        "-v",
                        "error",
                        "-show_entries",
                        "format=duration",
                        "-of",
                        "csv=p=0",
                        str(raw),
                    ]
                )
            )
            speed = max(1.0, duration / 7.8)
            subprocess.run(
                [
                    "ffmpeg",
                    "-v",
                    "error",
                    "-y",
                    "-i",
                    str(raw),
                    "-af",
                    f"atempo={speed:.5f},loudnorm=I=-18:TP=-2:LRA=7",
                    "-ar",
                    "16000",
                    "-ac",
                    "1",
                    "-c:a",
                    "pcm_s16le",
                    str(target),
                ],
                check=True,
            )
            with wave.open(str(target)) as wav:
                assert (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) == (
                    1,
                    2,
                    16000,
                )
                item["duration_seconds"] = round(wav.getnframes() / 16000, 3)
                assert 0 < item["duration_seconds"] <= 8, item["id"]
            item["sha256"] = hashlib.sha256(target.read_bytes()).hexdigest()
            print(item["id"], item["duration_seconds"], flush=True)
    (directory / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=ASSETS)
    generate(parser.parse_args().directory)
