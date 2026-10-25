"""Prepare a pinned offline control model; no credentials or device configuration."""
import hashlib
import os
from pathlib import Path
import shutil
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
NAME = "vosk-model-small-cn-0.22.zip"
SHA = "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba"
URL = "https://alphacephei.com/vosk/models/" + NAME
DEST = ROOT / "app/build/generated/voiceAssets/offline" / NAME
CACHE = ROOT / "build/voice-model-cache" / NAME


def valid(path):
    return path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == SHA


if not valid(DEST):
    CACHE.parent.mkdir(parents=True, exist_ok=True)
    if not valid(CACHE):
        candidate = Path(os.environ.get("SONAR_VOICE_MODEL_ZIP", ""))
        if valid(candidate):
            shutil.copyfile(candidate, CACHE)
        else:
            temporary = CACHE.with_suffix(".download")
            with urllib.request.urlopen(URL, timeout=60) as source, temporary.open("wb") as target:
                shutil.copyfileobj(source, target)
            if not valid(temporary):
                temporary.unlink(missing_ok=True)
                raise RuntimeError("Offline voice model checksum mismatch")
            temporary.replace(CACHE)
    DEST.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(CACHE, DEST)
print("Offline Chinese controls: pinned Vosk model ready")
