import io
import json
import uuid
import wave

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app

ALICE = "a" * 40
BOB = "b" * 40


def wav(seconds=0.1, rate=16000):
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(b"\x00\x00" * int(rate * seconds))
    return buffer.getvalue()


def auth(user="alice"):
    return {"Authorization": f"Bearer {ALICE if user == 'alice' else BOB}"}


def draft(client, user="alice", metadata=None, audio=None):
    metadata = metadata or {
        "kind": "question",
        "transcript": "现在中环路金科路口堵不堵？",
        "target_place_id": "zhonghuan_jinke",
        "direction": "demo_exit",
    }
    metadata = {"client_request_id": str(uuid.uuid4()), **metadata}
    return client.post(
        "/v1/posts/drafts",
        headers=auth(user),
        files={"audio": ("voice.wav", audio if audio is not None else wav(), "audio/wav")},
        data={"metadata": json.dumps(metadata, ensure_ascii=False)},
    )


def publish(client):
    response = draft(client)
    assert response.status_code == 201, response.text
    pid = response.json()["post_id"]
    response = client.post(f"/v1/posts/{pid}/confirm", headers=auth(), json={})
    assert response.status_code == 200, response.text
    return pid


def invitation(client):
    return client.get("/v1/inbox", headers=auth("bob")).json()["items"][0]


def answer(client, qid, inv):
    response = draft(
        client,
        "bob",
        {
            "kind": "answer",
            "transcript": "好堵啊，下匝道都要十五分钟。",
            "reply_to": qid,
            "invitation_id": inv["id"],
        },
    )
    assert response.status_code == 201, response.text
    pid = response.json()["post_id"]
    response = client.post(f"/v1/posts/{pid}/confirm", headers=auth("bob"), json={})
    assert response.status_code == 200, response.text
    return pid


def set_context(client, user="bob", **changes):
    profile = client.get("/v1/me", headers=auth(user)).json()["profile"]
    data = {
        "expected_revision": profile["revision"],
        "presence": {k: v for k, v in profile["presence"].items() if k != "updated_at"},
        "assist_enabled": profile["assist_enabled"],
        "mode": profile["mode"],
        "focus": profile["focus"],
        **changes,
    }
    return client.put("/v1/me/context", headers=auth(user), json=data)


@pytest.fixture
def env(tmp_path):
    now = [1792720800.0]
    settings = Settings(_env_file=None, data_dir=tmp_path, alice_token=ALICE, bob_token=BOB)
    app = create_app(settings, lambda: now[0])
    app.state.service.seed()
    with TestClient(app) as client:
        yield client, app.state.service, now, settings
