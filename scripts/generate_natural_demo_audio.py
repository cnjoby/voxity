"""Generate expressive Mandarin demo speech locally; stage files before --apply."""

import argparse
import hashlib
import json
import shutil
import subprocess
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "android/app/src/main/assets/demo"
STAGING = ROOT / "output/natural-tts/generated"
MODEL = "mlx-community/Qwen3-TTS-12Hz-1.7B-CustomVoice-6bit"
REVISION = "1c6c0ff58c43afa8df571facde2efa077efd85e2"
STYLE = {
    "公告": (
        "Serena",
        "温柔提醒女声",
        "用自然、温和、亲切的普通话提醒身边的人。语速适中，语义之间有短暂自然停顿，重音清晰，音调有轻微起伏，像社区同事善意提醒。不要机器人逐字朗读，也不要夸张播音腔。",
    ),
    "路况": (
        "Uncle_Fu",
        "随口分享男声",
        "用自然随和的普通话，像刚走到路口的普通人，随口向朋友描述看到的情况。语速适中偏轻快，停顿自然，陈述中带轻微语气变化。不要新闻播报腔，不要夸张表演。",
    ),
    "闲聊": (
        "Vivian",
        "轻松聊天女声",
        "用轻松亲切的普通话和朋友随口聊天。自然常速，带一点笑意，有细微的停顿和语调起伏，提问时自然上扬。不要字正腔圆地播报，不要夸张可爱或拖长尾音。",
    ),
}


def read(path):
    return json.loads(path.read_text())


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def validate(path, item):
    with wave.open(str(path)) as wav:
        if (
            wav.getnchannels(),
            wav.getsampwidth(),
            wav.getframerate(),
            wav.getcomptype(),
        ) != (1, 2, 16000, "NONE"):
            raise ValueError("Unsupported WAV format: " + str(path))
        seconds = wav.getnframes() / 16000
        if not 0 < seconds <= 8:
            raise ValueError(
                f"Speech must remain complete and at most 8 seconds: {path.name} ({seconds:.2f}s)"
            )
    item["duration_seconds"] = round(seconds, 3)
    item["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()


def generate(directory, preview=False):
    import mlx.core as mx
    import numpy as np
    from huggingface_hub import snapshot_download
    from mlx_audio.tts.utils import load_model
    from scipy.io.wavfile import write

    directory.mkdir(parents=True, exist_ok=True)
    checkpoint = directory / "manifest.json"
    manifest = read(checkpoint if checkpoint.exists() else ASSETS / "manifest.json")
    manifest["version"] = 2
    manifest["tts_engine"] = {
        "model": MODEL,
        "revision": REVISION,
        "runtime": "mlx-audio==0.6.1",
        "processing": "resample and volume normalize only; no tempo changes or speech cuts",
    }
    clips = manifest["clips"]
    selected = (
        [next(c for c in clips if c["category"] == k) for k in STYLE]
        if preview
        else clips
    )
    model_path = snapshot_download(MODEL, revision=REVISION)
    model = load_model(model_path)
    print("Model loaded; speakers:", model.get_supported_speakers(), flush=True)
    for item in selected:
        target = directory / item["file"]
        speaker, label, instruction = STYLE[item["category"]]
        previous = item.get("tts", {})
        if (
            target.exists()
            and previous.get("revision") == REVISION
            and previous.get("style") == instruction
        ):
            validate(target, item)
            print("Cached", item["id"], flush=True)
            continue
        # Keep the explicit test label, but integrate it into the spoken sentence.
        item["transcript"] = (
            item["transcript"]
            .replace("模拟公告。", "模拟公告，")
            .replace("模拟路况。", "模拟路况，")
            .replace("测试闲聊。", "测试闲聊，")
        )
        raw = directory / (item["id"] + "-original.wav")
        for attempt in range(2):
            mx.random.seed(2400 + clips.index(item) * 10 + attempt)
            prompt = instruction + ("说得稍微紧凑一点，保持口语感。" if attempt else "")
            result = list(
                model.generate_custom_voice(
                    text=item["transcript"],
                    speaker=speaker,
                    language="Chinese",
                    instruct=prompt,
                    max_tokens=256,
                    temperature=0.75,
                    verbose=False,
                )
            )
            audio = np.concatenate(
                [np.array(r.audio.astype(mx.float32)) for r in result]
            )
            rate = result[0].sample_rate
            if len(audio) / rate > 8:
                print(
                    "Regenerating overlength speech:",
                    item["id"],
                    round(len(audio) / rate, 2),
                    flush=True,
                )
                continue
            write(raw, rate, audio.astype(np.float32))
            subprocess.run(
                [
                    "ffmpeg",
                    "-v",
                    "error",
                    "-y",
                    "-i",
                    str(raw),
                    "-af",
                    "loudnorm=I=-18:TP=-2:LRA=7",
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
            validate(target, item)
            item["voice"] = label
            item["tts"] = {
                "model": MODEL,
                "revision": REVISION,
                "speaker": speaker,
                "instruction": prompt,
                "style": instruction,
                "seed": 2400 + clips.index(item) * 10 + attempt,
                "natural_speed": True,
            }
            save(checkpoint, manifest)
            print("Generated", item["id"], item["duration_seconds"], label, flush=True)
            break
        else:
            raise RuntimeError(
                "Clip remains overlength; shorten its wording without speeding or trimming speech: "
                + item["id"]
            )
    save(checkpoint, manifest)


def apply(directory):
    manifest = read(directory / "manifest.json")
    if manifest["version"] != 2 or len(manifest["clips"]) != 36:
        raise ValueError("Expected 36 version-2 clips")
    for item in manifest["clips"]:
        if item.get("tts", {}).get("revision") != REVISION:
            raise ValueError("Incomplete natural speech batch: " + item["id"])
        validate(directory / item["file"], item)
    backup = ROOT / "output/natural-tts/legacy-system-voices"
    if not backup.exists():
        shutil.copytree(ASSETS, backup)
    for item in manifest["clips"]:
        shutil.copy2(directory / item["file"], ASSETS / item["file"])
    save(ASSETS / "manifest.json", manifest)
    print(
        "Applied 36 complete natural-voice WAVs; legacy backup saved locally.",
        flush=True,
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=STAGING)
    parser.add_argument("--preview", action="store_true")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    if args.apply:
        apply(args.directory)
    else:
        generate(args.directory, args.preview)
