"""Real providers + synthetic voices + real HTTP against an isolated database only."""

import argparse
import asyncio
import hashlib
import json
import secrets
import socket
import subprocess
import sys
import tempfile
import threading
import time
import uuid
import wave
from datetime import UTC, datetime
from pathlib import Path

import httpx
import uvicorn
import websockets
from dotenv import dotenv_values

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))
from app.config import Settings  # noqa: E402
from app.main import create_app  # noqa: E402

OUT: Path
SETTINGS: Settings
REPORT = {
    "started_at": datetime.now(UTC).isoformat(),
    "synthetic_audio": True,
    "location": "android_mock coordinates injected in an isolated database",
    "providers": ["real AMap", "real Bamboo", "real Linx WSS STT"],
    "microphone_used": False,
    "speaker_used": False,
    "production_posts_written": False,
    "checks": [],
}


def record(name, data):
    REPORT["checks"].append({"name": name, **data})
    (OUT / "live-report.json").write_text(json.dumps(REPORT, ensure_ascii=False, indent=2))
    print(name + ": " + json.dumps(data, ensure_ascii=False), flush=True)


def make_audio(name, text):
    aiff, wav = OUT / (name + ".aiff"), OUT / (name + ".wav")
    subprocess.run(["say", "-v", "Tingting", "-r", "210", "-o", str(aiff), text], check=True)
    subprocess.run(
        [
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(aiff),
            "-ar",
            "16000",
            "-ac",
            "1",
            "-c:a",
            "pcm_s16le",
            str(wav),
        ],
        check=True,
    )
    with wave.open(str(wav), "rb") as f:
        assert f.getnframes() / 16000 <= 8
    return wav.read_bytes()


async def stt(name, text, config, device, client):
    audio = make_audio(name, text)
    with wave.open(str(OUT / (name + ".wav")), "rb") as f:
        pcm = f.readframes(f.getnframes())
    url = config["url"].replace("ws://xrobo-io.qiniuapi.com/", "wss://xrobo-io.qiniuapi.com/")
    assert url.startswith("wss://xrobo-io.qiniuapi.com/")
    words = set()
    async with websockets.connect(
        url,
        additional_headers={
            "Authorization": "Bearer " + config["token"],
            "Device-Id": device,
            "Client-Id": client,
            "Protocol-Version": "1",
        },
        open_timeout=15,
        proxy=None,
    ) as ws:
        await ws.send(
            json.dumps(
                {
                    "type": "hello",
                    "version": 1,
                    "features": {"mcp": False},
                    "transport": "websocket",
                    "audio_params": {
                        "format": "pcm",
                        "sample_rate": 16000,
                        "channels": 1,
                        "frame_duration": 20,
                    },
                }
            )
        )
        hello = json.loads(await asyncio.wait_for(ws.recv(), 10))
        assert hello["type"] == "hello"
        sid = hello["session_id"]
        await ws.send(
            json.dumps(
                {
                    "session_id": sid,
                    "type": "listen",
                    "state": "start",
                    "mode": "manual",
                }
            )
        )
        for i in range(0, len(pcm), 640):
            await ws.send(pcm[i : i + 640])
            await asyncio.sleep(0.02)
        await ws.send(
            json.dumps({"session_id": sid, "type": "listen", "state": "stop", "mode": "manual"})
        )
        async with asyncio.timeout(25):
            async for raw in ws:
                if isinstance(raw, bytes):
                    continue
                event = json.loads(raw)
                if event.get("type") == "stt" and event.get("text", "").strip():
                    words.add(event["text"].strip())
                if (
                    words
                    and event.get("type") == "tts"
                    and event.get("state") in ("start", "sentence_start", "stop")
                ):
                    await ws.send(
                        json.dumps(
                            {
                                "session_id": sid,
                                "type": "abort",
                                "reason": "wake_word_detected",
                            }
                        )
                    )
                    break
    assert len(words) == 1, ("STT_CANDIDATE_COUNT", len(words))
    transcript = words.pop()
    record(
        "stt_" + name,
        {
            "input": text,
            "transcript": transcript,
            "audio_sha256": hashlib.sha256(audio).hexdigest(),
        },
    )
    return transcript, audio


async def main():
    linx = dotenv_values(ROOT / "server/.env.linx")
    client, device = linx["LINX_PROTOCOL_CHECK_CLIENT_ID"], SETTINGS.alice_device_id
    apk = ROOT / "android/app/build/outputs/apk/debug/app-debug.apk"
    with httpx.Client(timeout=15) as net:
        ota = net.post(
            "https://xrobo.qiniuapi.com/v1/ota/",
            headers={
                "Device-Id": device,
                "Client-Id": client,
                "Activation-Version": "1",
                "User-Agent": "sonar-android/0.1.1",
                "Accept-Language": "zh-CN",
            },
            json={
                "version": 0,
                "uuid": client,
                "mac_address": device,
                "application": {
                    "name": "sonar-android",
                    "version": "0.1.1",
                    "elf_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                },
                "board": {"type": "sonar-android", "ssid": "", "rssi": 0},
                "chip_model_name": "android",
            },
        )
        ota.raise_for_status()
        config = ota.json()["websocket"]
    with tempfile.TemporaryDirectory(prefix="sonar-live-network-") as tmp:
        settings = SETTINGS.model_copy(
            update={
                "data_dir": Path(tmp),
                "alice_token": secrets.token_hex(24),
                "bob_token": secrets.token_hex(24),
                "alice_device_id": "isolated-a",
                "bob_device_id": "isolated-b",
                "alice_agent_id": "isolated-agent-a",
                "bob_agent_id": "isolated-agent-b",
                "decision_enabled": True,
                "mcp_enabled": False,
                "allow_mock_location": True,
            }
        )
        app = create_app(settings)
        svc = app.state.service
        svc.seed()
        island = svc.geo.search("张江人工智能岛")[0]
        park = svc.geo.search("上海世纪公园")[0]
        with svc.scope() as db:
            for place in [island, park]:
                svc.register_agent_place(db, place)
            svc.register_agent_place(
                db,
                {
                    "id": "origin",
                    "label": "测试出发点",
                    "latitude": 31.205,
                    "longitude": 121.59,
                    "kind": "area",
                },
            )
            for user, lat, lon, place in [
                ("alice", 31.205, 121.59, "origin"),
                ("bob", island["latitude"], island["longitude"], island["id"]),
            ]:
                db.execute(
                    "UPDATE profiles SET place_id=?,direction='unknown',source='android_mock',latitude=?,longitude=?,sampled_at=?,accuracy_m=5,updated_at=?,focus=? WHERE user_id=?",
                    (
                        place,
                        lat,
                        lon,
                        time.time(),
                        time.time(),
                        "愿意回答附近的道路拥堵和景区停车位、天气现场问题",
                        user,
                    ),
                )
        sock = socket.socket()
        sock.bind(("127.0.0.1", 0))
        server = uvicorn.Server(uvicorn.Config(app, log_level="critical", access_log=False))
        thread = threading.Thread(target=lambda: server.run(sockets=[sock]), daemon=True)
        thread.start()
        deadline = time.monotonic() + 10
        while not server.started:
            if time.monotonic() > deadline or not thread.is_alive():
                raise RuntimeError("Isolated HTTP server did not start")
            await asyncio.sleep(0.05)
        base = "http://127.0.0.1:" + str(sock.getsockname()[1])
        c = httpx.Client(base_url=base, timeout=30)

        def headers(user):
            return {
                "Authorization": "Bearer "
                + (settings.alice_token if user == "alice" else settings.bob_token)
            }

        sessions = {}

        def start(user, place):
            p = c.get("/v1/me", headers=headers(user)).json()["profile"]
            r = c.post(
                "/v1/exchange/sessions",
                headers=headers(user),
                json={
                    "client_request_id": str(uuid.uuid4()),
                    "expected_revision": p["revision"],
                    "place_id": place,
                    "auto_publish": True,
                    "auto_play": True,
                    "network_enabled": True,
                },
            )
            r.raise_for_status()
            sessions[user] = r.json()["session"]

        def refresh():
            with svc.scope() as db:
                db.execute(
                    "UPDATE profiles SET sampled_at=?,updated_at=?",
                    (time.time(), time.time()),
                )

        def send(user, text, audio):
            refresh()
            r = c.post(
                "/v1/exchange/sessions/" + sessions[user]["id"] + "/utterances",
                headers=headers(user),
                data={
                    "metadata": json.dumps(
                        {
                            "client_request_id": str(uuid.uuid4()),
                            "expected_session_revision": 1,
                            "transcript": text,
                            "recorded_at": datetime.now(UTC).isoformat(),
                        }
                    )
                },
                files={"audio": ("synthetic.wav", audio, "audio/wav")},
            )
            r.raise_for_status()
            return r.json()

        def listen(user):
            refresh()
            r = c.get(
                "/v1/exchange/sessions/" + sessions[user]["id"] + "/inbox",
                headers=headers(user),
            )
            r.raise_for_status()
            return r.json()["items"]

        def hear(user, delivery, original):
            r = c.get(delivery["payload"]["audio_url"], headers=headers(user))
            assert r.content == original
            r = c.put(
                "/v1/exchange/sessions/" + sessions[user]["id"] + "/context",
                headers=headers(user),
                json={
                    "expected_session_revision": 1,
                    "post_id": delivery["payload"]["post_id"],
                },
            )
            r.raise_for_status()
            r = c.post(
                "/v1/inbox/" + delivery["id"] + "/ack",
                headers=headers(user),
                json={"event": "played", "request_id": delivery["request_id"]},
            )
            r.raise_for_status()
            assert r.json()["delivery"]["state"] == "played"

        async def wait_original(user):
            for _ in range(20):
                items = listen(user)
                if items:
                    return items
                await asyncio.sleep(1)
            with svc.scope() as db:
                details = [
                    dict(r)
                    for r in db.execute(
                        "SELECT state,reason_code,attention_json FROM deliveries WHERE recipient_id=? AND type=?",
                        (user, "invitation"),
                    )
                ]
            record("missing_original_diagnostics", {"user": user, "invitations": details})
            return []

        async def question_answer(name, question, answer, place):
            qtext, qaudio = await stt(name + "_question", question, config, device, client)
            q = send("alice", qtext, qaudio)
            assert q["status"] == "published", q
            assert q["target_place"]["id"] == place["id"]
            await asyncio.sleep(1)
            offered = await wait_original("bob")
            assert offered and offered[0]["payload"]["kind"] == "question", (
                "NO_APPROVED_QUESTION",
                offered,
            )
            hear("bob", offered[0], qaudio)
            atext, aaudio = await stt(name + "_answer", answer, config, device, client)
            a = send("bob", atext, aaudio)
            assert a["status"] == "published" and a["action"] == "answer", a
            returned = listen("alice")
            assert returned and returned[0]["payload"]["post_id"] == a["post_id"]
            hear("alice", returned[0], aaudio)
            record(
                name + "_closed_loop",
                {
                    "question_id": q["post_id"],
                    "answer_id": a["post_id"],
                    "question_original_equal": True,
                    "answer_original_equal": True,
                    "played_ack": True,
                    "remote_origin": True,
                    "map_target": q["target_place"],
                    "real_invitation_filter": True,
                    "real_exchange_decider": True,
                },
            )

        try:
            start("alice", "origin")
            start("bob", island["id"])
            text, audio = await stt(
                "route",
                "Hi，声呐，我想去张江人工智能岛，现在堵车吗",
                config,
                device,
                client,
            )
            route = send("alice", text, audio)
            assert route.get("agent_response", {}).get("kind") == "route", route
            record("agent_route", route)
            text, audio = await stt("weather", "张江人工智能岛下雨了吗", config, device, client)
            weather = send("alice", text, audio)
            assert weather.get("agent_response", {}).get("kind") == "weather", weather
            record("agent_weather", weather)
            await question_answer(
                "traffic",
                "帮我问问张江人工智能岛现在堵不堵",
                "我就在岛东门，车排了十分钟。",
                island,
            )
            text, original = await stt(
                "parking_ambiguous",
                "Hi，声呐，前面景区还有停车位吗",
                config,
                device,
                client,
            )
            first = send("alice", text, original)
            assert first["status"] == "clarifying", first
            with svc.scope() as db:
                assert (
                    db.execute(
                        "SELECT count(*) FROM posts WHERE kind='question' AND question_status='waiting'"
                    ).fetchone()[0]
                    == 0
                )
            text, loc_audio = await stt("parking_location", "上海世纪公园", config, device, client)
            q = send("alice", text, loc_audio)
            assert q["status"] == "published" and q["target_place"]["id"] == park["id"], q
            with svc.scope() as db:
                db.execute(
                    "UPDATE profiles SET latitude=?,longitude=?,place_id=? WHERE user_id='bob'",
                    (park["latitude"], park["longitude"], park["id"]),
                )
            start("bob", park["id"])
            await asyncio.sleep(1)
            offered = await wait_original("bob")
            assert offered, "NO_APPROVED_PARKING_QUESTION"
            hear("bob", offered[0], original)
            text, answer = await stt(
                "parking_answer",
                "我在西门停车场，里面还有十几个空位。",
                config,
                device,
                client,
            )
            a = send("bob", text, answer)
            assert a["status"] == "published" and a["action"] == "answer", a
            returned = listen("alice")
            assert returned
            hear("alice", returned[0], answer)
            record(
                "parking_clarified_closed_loop",
                {
                    "clarification": first["agent_response"],
                    "target": q["target_place"],
                    "nothing_published_before_clarification": True,
                    "original_question_audio_equal": True,
                    "original_answer_audio_equal": True,
                    "played_ack": True,
                },
            )
            chat_audio = make_audio("chat_offer", "Hi，声呐，我想闲聊")
            first = send("alice", "Hi，声呐，我想闲聊", chat_audio)
            assert first["agent_response"]["kind"] == "chat_offer"
            peer_audio = make_audio("chat_peer", "我也想闲聊")
            pair = send("bob", "我也想闲聊", peer_audio)
            assert pair["action"] == "human_chat", pair
            for user, expected in [("alice", peer_audio), ("bob", chat_audio)]:
                delivery = listen(user)[0]
                assert delivery["payload"]["kind"] == "chat"
                hear(user, delivery, expected)
            msg = make_audio("chat_message", "你好，你今天去哪里？")
            sent = send("alice", "你好，你今天去哪里？", msg)
            assert sent["action"] == "human_chat"
            delivery = listen("bob")[0]
            hear("bob", delivery, msg)
            record(
                "mutual_chat_closed_loop",
                {
                    "mutual_opt_in": True,
                    "peer_audio_equal": True,
                    "agent_fallback_not_started": True,
                    "played_ack": True,
                },
            )
            c.put(
                "/v1/exchange/sessions/" + sessions["bob"]["id"],
                headers=headers("bob"),
                json={"expected_session_revision": 1, "state": "ended"},
            ).raise_for_status()
            first = send("alice", "我想闲聊", chat_audio)
            assert first["agent_response"]["kind"] == "chat_offer"
            yes = send("alice", "好，你陪我聊吧", make_audio("yes_agent", "好，你陪我聊吧"))
            assert yes["action"] == "agent_chat", yes
            private = send("alice", "今天有点累", make_audio("private_chat", "今天有点累"))
            assert private["action"] == "private_chat"
            record(
                "agent_fallback_consent",
                {
                    "offered_before_start": True,
                    "explicit_yes_required": True,
                    "private_route_after_yes": True,
                },
            )
            REPORT["status"] = "passed"
        finally:
            c.close()
            server.should_exit = True
            thread.join(timeout=5)
            REPORT["finished_at"] = datetime.now(UTC).isoformat()
            (OUT / "live-report.json").write_text(json.dumps(REPORT, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--live",
        action="store_true",
        help="Explicitly enable real Linx, AMap and Bamboo calls; business state stays in a temporary database.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT / "output" / ("voice-network-" + datetime.now(UTC).strftime("%Y%m%d-%H%M%S")),
    )
    args = parser.parse_args()
    if not args.live:
        parser.error(
            "Use --live to enable provider calls. This script does not use a microphone or production posts."
        )
    OUT = args.output.resolve()
    OUT.mkdir(parents=True, exist_ok=False)
    SETTINGS = Settings(_env_file=ROOT / "server/.env")
    try:
        asyncio.run(main())
    except Exception as exc:
        REPORT["status"] = "failed"
        REPORT["failure_type"] = type(exc).__name__
        (OUT / "live-report.json").write_text(json.dumps(REPORT, ensure_ascii=False, indent=2))
        raise
