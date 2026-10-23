"""Exercise the real HTTP server with explicitly synthetic WAV and confirmations."""

import argparse
import io
import json
import sys
import uuid
import wave
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
from app.config import Settings  # noqa: E402


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8765")
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument(
        "--synthetic",
        required=True,
        action="store_true",
        help="Acknowledge synthetic audio and simulated user confirmation",
    )
    args = parser.parse_args()
    settings = Settings(_env_file=Path(__file__).resolve().parents[1] / "server" / ".env")
    if not settings.alice_token or not settings.bob_token:
        raise SystemExit("Initialize server/.env and seed first")
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes(b"\x00\x00" * 1600)
    audio = buf.getvalue()
    headers = {
        "alice": {"Authorization": "Bearer " + settings.alice_token},
        "bob": {"Authorization": "Bearer " + settings.bob_token},
    }
    with httpx.Client(base_url=args.base_url, timeout=10, follow_redirects=False) as c:

        def call(method, path, user="alice", **kwargs):
            r = c.request(method, path, headers=headers[user], **kwargs)
            r.raise_for_status()
            return r.json()

        def context(user, mode):
            p = call("GET", "/v1/me", user)["profile"]
            return call(
                "PUT",
                "/v1/me/context",
                user,
                json={
                    "expected_revision": p["revision"],
                    "presence": {
                        "place_id": "zhonghuan_jinke" if user == "bob" else "demo_approach",
                        "direction": "demo_exit",
                        "source": "simulated",
                    },
                    "assist_enabled": True,
                    "mode": mode,
                    "focus": "愿意回答附近路况" if mode == "listen" else "现在安静",
                },
            )

        def publish(user, meta):
            meta = {"client_request_id": str(uuid.uuid4()), **meta}
            p = call(
                "POST",
                "/v1/posts/drafts",
                user,
                files={"audio": ("synthetic.wav", audio, "audio/wav")},
                data={"metadata": json.dumps(meta, ensure_ascii=False)},
            )
            return call("POST", f"/v1/posts/{p['post_id']}/confirm", user, json={})

        context("alice", "listen")
        context("bob", "listen")
        question = {
            "kind": "question",
            "transcript": "[测试转写] 现在中环路金科路口堵不堵？",
            "target_place_id": "zhonghuan_jinke",
            "direction": "demo_exit",
        }
        for round_no in range(args.rounds):
            qid = publish("alice", question)["question_id"]
            inv = call("GET", "/v1/inbox", "bob")["items"][0]
            assert inv["question_id"] == qid and inv["actionable"]
            latest = call("GET", f"/v1/inbox/{inv['id']}", "bob")["delivery"]
            assert latest["request_id"] == inv["request_id"]
            call(
                "POST",
                f"/v1/inbox/{inv['id']}/ack",
                "bob",
                json={"event": "alerted", "request_id": inv["request_id"]},
            )
            aid = publish(
                "bob",
                {
                    "kind": "answer",
                    "transcript": "[测试转写] 好堵啊，下匝道都要十五分钟。",
                    "reply_to": qid,
                    "invitation_id": inv["id"],
                },
            )["post_id"]
            state = call("GET", f"/v1/questions/{qid}")
            assert state["status"] == "answered" and state["answer"]["post_id"] == aid
            delivery = next(
                d for d in call("GET", "/v1/inbox")["items"] if d["payload"]["post_id"] == aid
            )
            response = c.get(delivery["payload"]["audio_url"], headers=headers["alice"])
            response.raise_for_status()
            assert response.content == audio
            call(
                "POST",
                f"/v1/inbox/{delivery['id']}/ack",
                json={"event": "played", "request_id": delivery["request_id"]},
            )
            print(
                json.dumps(
                    {
                        "round": round_no + 1,
                        "question_id": qid,
                        "answer_id": aid,
                        "result": "passed",
                        "audio": "synthetic_silence",
                        "confirmation": "simulated",
                    }
                )
            )
        context("bob", "quiet")
        qid = publish("alice", question)["question_id"]
        assert call("GET", "/v1/inbox", "bob")["items"] == []
        assert call("GET", f"/v1/questions/{qid}")["status"] == "waiting"
        call("POST", f"/v1/questions/{qid}/cancel", json={})
        context("bob", "listen")
        print(json.dumps({"quiet_counterexample": "passed", "device_test": "not_executed"}))


if __name__ == "__main__":
    main()
