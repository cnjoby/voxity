import asyncio
import io
import json
import math
import struct
import uuid

import av
import pytest
from conftest import auth, draft, wav
from test_exchange import stamp, utter
from test_voice_first import heard, setup_voice

from app.audio import OGG_CRC, decode_opus, opus_duration, validate_audio
from app.errors import Problem
from app.mcp import ProtectedMCP
from app.models import ExchangeUtterance


def opus(seconds=1):
    output = io.BytesIO()
    with av.open(output, "w", format="ogg") as container:
        stream = container.add_stream("libopus", rate=16000)
        stream.layout = "mono"
        stream.bit_rate = 24000
        for cursor in range(0, int(seconds * 16000), 320):
            frame = av.AudioFrame(format="s16", layout="mono", samples=320)
            frame.sample_rate = 16000
            frame.pts = cursor
            frame.planes[0].update(
                struct.pack(
                    "<320h", *(int(6000 * math.sin((cursor + i) * 0.12)) for i in range(320))
                )
            )
            for packet in stream.encode(frame):
                container.mux(packet)
        for packet in stream.encode(None):
            container.mux(packet)
    return output.getvalue()


def test_opus_upload_is_immutable_small_and_returns_correct_content_type(env):
    c, service, _, _ = env
    encoded = opus(5)
    assert len(encoded) < len(wav(5)) / 5
    validate_audio(encoded)
    assert opus_duration(encoded) == pytest.approx(5)
    response = draft(c, audio=encoded)
    assert response.status_code == 201, response.text
    pid = response.json()["post_id"]
    assert service.store.audio_path(pid).suffix == ".opus"
    assert service.store.audio_path(pid).read_bytes() == encoded
    assert not (service.store.audio_dir / f"{pid}.wav").exists()
    assert c.post(f"/v1/posts/{pid}/confirm", headers=auth(), json={}).status_code == 200
    downloaded = c.get(f"/v1/audio/{pid}", headers=auth())
    assert downloaded.headers["content-type"] == "audio/ogg"
    assert downloaded.content == encoded
    assert len(decode_opus(encoded)) == len(wav(5))
    with service.scope() as db:
        assert service.audio_payload(service.post(db, pid))["duration_seconds"] == 5
        assert service.voice_audio_item(db, "alice", service.post(db, pid))["duration_seconds"] == 5


@pytest.mark.parametrize("damage", ["truncate", "checksum", "chain", "not_opus"])
def test_malformed_ogg_cannot_be_salvaged_or_stored(env, damage):
    c, service, _, _ = env
    encoded = opus()
    if damage == "truncate":
        encoded = encoded[:-1]
    elif damage == "checksum":
        encoded = encoded[:-1] + bytes([encoded[-1] ^ 1])
    elif damage == "chain":
        encoded += opus()
    else:
        encoded = b"OggS" + b"\0" * 100
    response = draft(c, audio=encoded)
    assert response.status_code == 415
    assert list(service.store.audio_dir.iterdir()) == []


def test_opus_duration_bound_and_oversized_decoded_payload():
    validate_audio(opus(30))
    with pytest.raises(Problem):
        validate_audio(opus(30.2))


def test_valid_checksum_cannot_hide_false_duration(env):
    c, _, _, _ = env
    encoded = bytearray(opus())
    offset = 0
    while offset < len(encoded):
        count = encoded[offset + 26]
        length = 27 + count + sum(encoded[offset + 27 : offset + 27 + count])
        if encoded[offset + 5] & 4:
            encoded[offset + 6 : offset + 14] = (72000).to_bytes(8, "little")
            encoded[offset + 22 : offset + 26] = b"\0" * 4
            crc = 0
            for byte in encoded[offset : offset + length]:
                crc = ((crc << 8) & 0xFFFFFFFF) ^ OGG_CRC[((crc >> 24) ^ byte) & 255]
            encoded[offset + 22 : offset + 26] = crc.to_bytes(4, "little")
        offset += length
    assert draft(c, audio=bytes(encoded)).status_code == 415


def test_managed_voice_mcp_is_read_only_for_same_user_even_while_paused(env):
    c, service, now, session = setup_voice(env)
    with service.scope() as db:
        policy = service.voice_policy(db, session)
        policy["client_managed_voice"] = True
        service.save_voice_policy(db, session["id"], policy)
        guard = ProtectedMCP(service)
        with pytest.raises(Problem, match="终端"):
            guard.require_mutation_owner(db, "alice")
        guard.require_mutation_owner(db, "bob")
        db.execute("UPDATE exchange_sessions SET state='paused' WHERE id=?", (session["id"],))
        with pytest.raises(Problem):
            guard.require_mutation_owner(db, "alice")
        db.execute("UPDATE exchange_sessions SET state='ended' WHERE id=?", (session["id"],))
        with pytest.raises(Problem):
            guard.require_mutation_owner(db, "alice")
    data = ExchangeUtterance.model_validate(
        {
            "client_request_id": str(uuid.uuid4()),
            "expected_session_revision": session["revision"],
            "transcript": "你好",
            "recorded_at": "2026-10-23T02:00:00Z",
        }
    )
    with pytest.raises(Problem) as error:
        asyncio.run(service.submit_agent_turn("alice", session["id"], data, from_mcp=True))
    assert error.value.code == "CLIENT_MANAGED_VOICE"
    with service.scope() as db:
        assert db.execute("SELECT count(*) FROM exchange_turns").fetchone()[0] == 0


def test_explicit_polite_reply_bypasses_private_semantic_route(env):
    c, service, now, alice = setup_voice(env)
    _, _, _, bob = setup_voice(env, "bob")
    question = utter(c, alice, now, transcript="帮我问问模力社区有车位吗？").json()
    heard(c, bob, question["post_id"], "bob")
    service.voice_intent.force = "private_chat"
    response = utter(c, bob, now, "bob", transcript="你帮我回复他说可以停车，可以过来").json()
    assert response["status"] == "published" and response["action"] == "answer", response
    with service.scope() as db:
        assert service.post(db, response["post_id"])["reply_to"] == question["post_id"]


@pytest.mark.parametrize(
    "text",
    [
        "你不要回复他",
        "如果你帮我回复他说可以呢",
        "什么叫回复他",
        "我还没想好怎么回复他",
        "“你帮我回复他”这句话是什么意思",
    ],
)
def test_quoted_or_negative_reply_is_not_an_explicit_action(text):
    from app.voice_first import explicit_reply

    assert not explicit_reply(text)


def test_explicit_reply_without_context_clarifies_instead_of_starting_new_scene(env):
    c, service, now, session = setup_voice(env)
    result = utter(c, session, now, transcript="你帮我回复他说可以停车").json()
    assert result["agent_response"]["kind"] == "clarification", result
    with service.scope() as db:
        assert db.execute("SELECT count(*) FROM posts").fetchone()[0] == 0


def test_recording_frozen_context_cannot_be_replaced_during_capture(env):
    c, service, now, alice = setup_voice(env)
    _, _, _, bob = setup_voice(env, "bob")
    question = utter(c, alice, now, transcript="帮我问问模力社区有车位吗？").json()
    heard(c, bob, question["post_id"], "bob")
    # Recording started without a heard context; a later context must not hijack it.
    result = utter(
        c, bob, now, "bob", transcript="你帮我回复他说可以停车", captured_context_post_id=None
    )
    assert result.status_code == 409 and result.json()["error"]["code"] == "CONTEXT_CHANGED"
    with service.scope() as db:
        assert db.execute("SELECT count(*) FROM posts WHERE kind='answer'").fetchone()[0] == 0


@pytest.mark.parametrize(
    "mapped,verified", [("", set()), ("actual-agent", set()), ("actual-agent", {"other-agent"})]
)
def test_unverified_platform_refuses_managed_voice_without_replacing_session(env, mapped, verified):
    c, service, _, current = setup_voice(env)
    service.settings.managed_voice_verified_agents = verified
    with service.scope() as db:
        db.execute("UPDATE users SET agent_id=? WHERE id='alice'", (mapped,))
    assert not c.get("/v1/me", headers=auth()).json()["media"]["client_managed_voice"]
    profile = c.get("/v1/me", headers=auth()).json()["profile"]
    response = c.post(
        "/v1/agent/sessions",
        headers=auth(),
        json={
            "client_request_id": str(uuid.uuid4()),
            "expected_revision": profile["revision"],
            "client_managed_voice": True,
        },
    )
    assert (
        response.status_code == 503
        and response.json()["error"]["code"] == "VOICE_PLATFORM_NOT_VERIFIED"
    )
    with service.scope() as db:
        assert service.exchange_row(db, "alice", current["id"])["state"] == "active"


def test_managed_voice_requires_explicit_verified_mapping(env):
    c, service, _, _ = setup_voice(env)
    with service.scope() as db:
        db.execute("UPDATE users SET agent_id='verified-agent' WHERE id='alice'")
    service.settings.managed_voice_verified_agents = {"verified-agent"}
    profile = c.get("/v1/me", headers=auth()).json()["profile"]
    assert c.get("/v1/me", headers=auth()).json()["media"]["client_managed_voice"]
    response = c.post(
        "/v1/agent/sessions",
        headers=auth(),
        json={
            "client_request_id": str(uuid.uuid4()),
            "expected_revision": profile["revision"],
            "client_managed_voice": True,
        },
    )
    assert response.status_code == 200, response.text
    assert response.json()["session"]["client_managed_voice"]


def test_ogg_utterance_returns_ogg_audio_without_transcoding(env):
    c, service, now, session = setup_voice(env)
    encoded = opus(1)
    response = c.post(
        f"/v1/exchange/sessions/{session['id']}/utterances",
        headers=auth(),
        files={"audio": ("voice.opus", encoded, "audio/ogg")},
        data={
            "metadata": json.dumps(
                {
                    "client_request_id": str(uuid.uuid4()),
                    "expected_session_revision": session["revision"],
                    "transcript": "帮我问问模力社区还有车位吗？",
                    "recorded_at": stamp(now[0]),
                }
            )
        },
    )
    assert response.status_code == 200, response.text
    turn = response.json()
    assert turn["status"] == "published", turn
    audio = c.get(f"/v1/audio/{turn['post_id']}", headers=auth())
    assert audio.status_code == 200 and audio.headers["content-type"] == "audio/ogg"
    assert audio.content == encoded
    assert service.store.audio_path(turn["post_id"]).suffix == ".opus"


def test_openapi_documents_both_binary_audio_formats(env):
    c, _, _, _ = env
    schema = c.get("/openapi.json").json()
    assert schema["info"]["version"] == "0.3.0"
    for path in ("/v1/audio/{pid}", "/v1/chat/audio/{mid}"):
        content = schema["paths"][path]["get"]["responses"]["200"]["content"]
        assert set(content) == {"audio/wav", "audio/ogg"}
        assert all(value["schema"]["format"] == "binary" for value in content.values())
    request = schema["paths"]["/v1/exchange/sessions/{sid}/utterances"]["post"]["requestBody"]
    ref = request["content"]["multipart/form-data"]["schema"]["$ref"].split("/")[-1]
    description = schema["components"]["schemas"][ref]["properties"]["audio"]["description"]
    assert "audio/ogg" in description and "audio/wav" in description
