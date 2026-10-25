"""Isolated synthetic release smoke; never reads or writes the production database."""

import argparse
import io
import json
import tempfile
import time
import uuid
import wave
from datetime import UTC, datetime
from pathlib import Path

from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app


class Decisions:
    configured = True
    action = "scene"

    async def decide(self, state, listening=False):
        return "play" if listening else self.action


PLACE = {
    "id": "B0FFJSBSGR",
    "label": "上海七牛信息技术有限公司",
    "latitude": 31.18498,
    "longitude": 121.609005,
    "kind": "poi",
}


class Places:
    def search(self, query):
        return [PLACE] if query in PLACE["label"] else []


class VoiceRoutes:
    async def route(self, state):
        return "reply" if state.get("heard_context") else "private_chat"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--synthetic", required=True, action="store_true")
    parser.parse_args()
    stream = io.BytesIO()
    with wave.open(stream, "wb") as writer:
        writer.setnchannels(1)
        writer.setsampwidth(2)
        writer.setframerate(16000)
        writer.writeframes(bytes(3200))
    original = stream.getvalue()
    with tempfile.TemporaryDirectory(prefix="sonar-release-check-") as directory:
        settings = Settings(
            _env_file=None,
            data_dir=Path(directory),
            alice_token="a" * 40,
            bob_token="b" * 40,
            mcp_enabled=False,
            decision_enabled=False,
            bamboo_api_key="",
            amap_key="",
        )
        app = create_app(settings)
        service = app.state.service
        service.seed()
        service.geo, service.exchange_decider = Places(), Decisions()
        service.voice_intent = VoiceRoutes()
        with service.scope() as db:
            service.register_agent_place(db, PLACE)
            db.execute(
                "INSERT INTO channels VALUES ('release-channel',?,'停车场','测试运营方',1)",
                (PLACE["id"],),
            )
            db.execute("INSERT INTO channel_operators VALUES ('release-channel','bob')")
            for user in ("alice", "bob"):
                db.execute(
                    "UPDATE profiles SET place_id=?,source='android',direction='unknown',latitude=?,longitude=?,accuracy_m=5,sampled_at=?,updated_at=? WHERE user_id=?",
                    (
                        PLACE["id"],
                        PLACE["latitude"],
                        PLACE["longitude"],
                        time.time(),
                        time.time(),
                        user,
                    ),
                )
        with TestClient(app) as client:

            def call(method, path, user="alice", **kwargs):
                response = client.request(
                    method,
                    path,
                    headers={"Authorization": "Bearer " + ("a" if user == "alice" else "b") * 40},
                    **kwargs,
                )
                assert response.status_code in (200, 201), (
                    path,
                    response.status_code,
                    response.text,
                )
                return response.json()

            def session(user, **grants):
                return call(
                    "POST",
                    "/v1/agent/sessions",
                    user,
                    json={
                        "client_request_id": str(uuid.uuid4()),
                        "expected_revision": call("GET", "/v1/me", user)["profile"]["revision"],
                        "place_id": PLACE["id"],
                        "capabilities": grants,
                    },
                )["session"]

            def utter(user, active, text, request_id=None):
                return call(
                    "POST",
                    f"/v1/exchange/sessions/{active['id']}/utterances",
                    user,
                    data={
                        "metadata": json.dumps(
                            {
                                "client_request_id": request_id or str(uuid.uuid4()),
                                "expected_session_revision": active["revision"],
                                "transcript": text,
                                "recorded_at": datetime.now(UTC).isoformat(),
                            }
                        )
                    },
                    files={"audio": ("synthetic-silence.wav", original, "audio/wav")},
                )

            notice = call(
                "POST",
                "/v1/posts/drafts",
                "bob",
                files={"audio": ("synthetic.wav", original, "audio/wav")},
                data={
                    "metadata": json.dumps(
                        {
                            "client_request_id": str(uuid.uuid4()),
                            "kind": "announcement",
                            "target_place_id": PLACE["id"],
                            "channel_id": "release-channel",
                            "purpose": "notice",
                            "transcript": "[合成测试] B2车位已满",
                            "valid_from": datetime.now(UTC).isoformat(),
                            "valid_until": datetime.fromtimestamp(
                                time.time() + 600, UTC
                            ).isoformat(),
                        }
                    )
                },
            )["post_id"]
            call("POST", f"/v1/posts/{notice}/confirm", "bob", json={})
            private = session("alice", private_query=True)
            found = utter("alice", private, "七牛还有车位吗？")
            assert found["agent_response"]["sources"][0]["post_id"] == notice
            assert found["status"] == "responded"
            call("POST", f"/v1/voices/{notice}/withdraw", "bob", json={})
            assert not call("POST", "/v1/information/validate", json={"message_ids": [notice]})[
                "valid"
            ]
            alice = session("alice", request_humans=True, reply_thread=True, auto_play=True)
            bob = session("bob", reply_thread=True, auto_play=True)
            request_id = str(uuid.uuid4())
            question = utter("alice", alice, "帮我问问七牛还有车位吗？", request_id)
            offered = call("GET", f"/v1/exchange/sessions/{bob['id']}/inbox", "bob")["items"][0]
            downloaded = client.get(
                offered["payload"]["audio_url"],
                headers={"Authorization": "Bearer " + "b" * 40},
            )
            assert downloaded.status_code == 200 and downloaded.content == original
            call(
                "POST",
                f"/v1/inbox/{offered['id']}/ack",
                "bob",
                json={"event": "played", "request_id": offered["request_id"]},
            )
            call(
                "PUT",
                f"/v1/exchange/sessions/{bob['id']}/context",
                "bob",
                json={"expected_session_revision": bob["revision"], "post_id": question["post_id"]},
            )
            assert offered["payload"]["kind"] == "question"
            service.exchange_decider.action = "answer"
            response = utter("bob", bob, "[合成测试] 我在入口，B2还有几个位。")
            assert response["action"] == "answer"
            task = call("GET", f"/v1/tasks/{question['task_id']}")
            assert task["status"] == "responded"
            recovered = call("GET", f"/v1/requests/{request_id}")
            assert recovered["turn"]["post_id"] == question["post_id"]
            received = call("GET", "/v1/inbox")["items"][0]
            call(
                "POST",
                f"/v1/inbox/{received['id']}/ack",
                json={"event": "played", "request_id": received["request_id"]},
            )
            assert call("GET", f"/v1/tasks/{task['id']}")["status"] == "responded"
            closed = call(
                "POST",
                f"/v1/tasks/{task['id']}/actions",
                json={
                    "expected_revision": task["revision"],
                    "client_request_id": str(uuid.uuid4()),
                    "action": "resolve",
                },
            )
            assert closed["status"] == "resolved"
            with service.scope() as db:
                assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
                assert not db.execute("PRAGMA foreign_key_check").fetchall()
        print(
            json.dumps(
                {
                    "status": "passed",
                    "database": "isolated_temporary",
                    "audio": "synthetic_silence",
                    "checks": [
                        "notice_retrieval",
                        "withdrawal",
                        "private_query",
                        "original_question_answer",
                        "request_reconciliation",
                        "played_not_resolved",
                        "explicit_resolution",
                        "integrity",
                    ],
                }
            )
        )


if __name__ == "__main__":
    main()
