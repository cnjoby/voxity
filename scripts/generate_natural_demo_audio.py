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
STAGING = ROOT / "output/natural-tts/conversational"
MODEL = "mlx-community/Qwen3-TTS-12Hz-1.7B-CustomVoice-6bit"
REVISION = "1c6c0ff58c43afa8df571facde2efa077efd85e2"
STYLE = {
    "公告": (
        "Serena",
        "随口提醒女声",
        "像给熟人发一条微信语音，很平常地随口提醒一句。普通话，轻松、随和，语调自然，句尾轻轻落下，带一点生活气息。语速正常偏轻快，短句一口气说完，只有意思转折处稍作停顿。不用广播主持的发声方式，不要字字用力，不刻意抒情或表演。",
    ),
    "路况": (
        "Dylan",
        "随口路况男声",
        "像站在路口给朋友发微信语音，顺嘴说一下眼前看到的情况。很随意的成年男性普通话，放松，口语化，轻微呼吸和自然重音，语速正常偏轻快。不播报新闻，不正式朗诵，不夸张演戏。",
    ),
    "闲聊": (
        "Vivian",
        "朋友聊天女声",
        "像给熟悉的朋友发微信语音，边想边随口聊一句。语气平常、放松、有生活感，普通话，带很轻的一点笑意和自然呼吸，问句轻轻上扬，正常语速，连贯自然。不像主持人，不像有声书，不撒娇卖萌，不拖尾音，不夸张热情。",
    ),
}

CONVERSATIONAL_TEXT = {
    "zhangjiang-jinke": [
        "过马路记得看灯啊，转弯的车也留意一下。",
        "那边有人修绿化，咱们走人行道就行。",
        "单车往旁边停一点吧，别把过街的路堵了。",
        "前面辅路有引导牌，跟着现场指示走就行。",
        "金科路口有点排队，再等一个灯看看吧。",
        "我这边车还不多，对面怎么样我没看清。",
        "匝道口走得有点慢，主路上我还不清楚。",
        "这会儿过马路的人挺多，转弯慢一点啊。",
        "刚下班走到金科路，有人一起散个步吗？",
        "想在附近喝杯咖啡，有没有人一起啊？",
        "周末想在张江骑骑车，有啥路线推荐吗？",
        "终于忙完了，出来看看夜景，舒服多了。",
    ],
    "ai-island-19": [
        "第一次来十九号楼的话，先登记一下哈。",
        "十九号楼这边开始签到了，大家慢慢来。",
        "里面还在开会，咱们说话稍微轻一点哈。",
        "门口有个设备演示，别把出入口挡住就好。",
        "十九号楼这边人挺多，开车稍微慢一点。",
        "门口有车在接人，咱们往前走两步再上车吧。",
        "停车入口排了几辆，里面还有没有位我得问问。",
        "楼旁边这条小路能走，车道那边我没看。",
        "我在十九号楼附近，有人也在做语音应用吗？",
        "写了一上午代码，出来吹吹风，清醒多了。",
        "我做了个小演示，有没有人愿意帮我试试？",
        "今天总算把那个功能跑通了，真的挺开心。",
    ],
    "modaily": [
        "模力社区那场分享快开始了，记得去签到哈。",
        "桌子用完帮忙收拾一下呗，东西也别落下。",
        "第一次过来的话，先问一下要去哪栋楼。",
        "这边还有人在办公，咱们声音稍微小一点哈。",
        "入口这儿有车进出，走过去的时候看一眼啊。",
        "门口接人的车有点多，咱找个能等的地方吧。",
        "这边骑车的人挺多，拐弯的时候慢一点哈。",
        "入口现在还挺顺的，车位剩多少得问一下。",
        "有人在做端侧模型吗？想聊聊推理速度。",
        "中午还没吃呢，有没有人一起去吃个饭？",
        "周末做了个语音小工具，想找人帮我试试。",
        "刚搬来这边，想认识几个做产品的朋友。",
    ],
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
    manifest["version"] = 3
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
        group, number = item["id"].rsplit("-", 1)
        transcript = CONVERSATIONAL_TEXT[group][int(number) - 1]
        previous = item.get("tts", {})
        if (
            target.exists()
            and previous.get("revision") == REVISION
            and previous.get("style") == instruction
            and previous.get("speaker") == speaker
            and item["transcript"] == transcript
            and hashlib.sha256(target.read_bytes()).hexdigest() == item.get("sha256")
        ):
            validate(target, item)
            print("Cached", item["id"], flush=True)
            continue
        # These are visibly labelled fictional fixtures. Do not read category labels
        # as an unnatural preamble before every short voice message.
        item["transcript"] = transcript
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
    if manifest["version"] != 3 or len(manifest["clips"]) != 36:
        raise ValueError("Expected 36 version-3 clips")
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
