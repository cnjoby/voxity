import hashlib
import json
import uuid

import pytest
from conftest import auth, draft, wav
from test_voice_first import MODAILY, setup_voice
from test_voice_matching import ack, inbox, locate, preferences, setup_place, voice

from app.poi_notices import import_notices
from app.service import stamp


def test_standing_notice_is_long_lived_and_preserves_synthesized_source(env):
    c, service, now = setup_place(env)
    pid = voice(
        env,
        kind="announcement",
        transcript="欢迎来到模力社区，访客请到一楼前台登记。",
        valid_until=None,
        validity_policy="until_withdrawn",
        content_origin="agent_synthesized",
        source_note="用户提供文案；合成模拟公共提示，非正式运营公告。",
    )
    now[0] += 86400 * 30
    locate(env)
    item = next(d for d in inbox(c) if d["payload"]["post_id"] == pid)
    assert item["actionable"] and item["payload"]["valid_until"] is None
    assert item["payload"]["content_origin"] == "agent_synthesized"
    assert item["payload"]["synthetic"]
    message = c.get("/v1/messages/" + pid, headers=auth()).json()
    assert message["message"]["body"]["content_origin"] == "agent_synthesized"
    with service.scope() as db:
        assert service.post(db, pid)["expires_at"] is None
        assert service.find_information(db, "alice", "scenic", purpose="route")["items"] == []
        assert (
            service.find_information(db, "alice", "scenic")["items"][0]["temporal_class"]
            == "standing_notice"
        )
    assert c.get(item["payload"]["audio_url"], headers=auth()).content == wav()
    assert ack(c, item).status_code == 200
    assert not any(d["payload"]["post_id"] == pid for d in inbox(c))
    c.post("/v1/voices/" + pid + "/withdraw", headers=auth("bob"), json={})
    assert c.get("/v1/audio/" + pid, headers=auth()).status_code == 404


@pytest.mark.parametrize(
    "kind,purpose,end,note",
    [
        ("scene", "observation", None, "模拟"),
        ("announcement", "notice", "end", "模拟"),
        ("announcement", "notice", None, None),
    ],
)
def test_standing_validity_cannot_extend_dynamic_observations_or_omit_source(
    env, kind, purpose, end, note
):
    c, _, now = setup_place(env)
    r = draft(
        c,
        "bob",
        {
            "kind": kind,
            "purpose": purpose,
            "transcript": "模拟提示",
            "target_place_id": "scenic",
            "channel_id": "parking",
            "validity_policy": "until_withdrawn",
            "valid_from": stamp(now[0]),
            "valid_until": stamp(now[0] + 600) if end else None,
            "source_note": note,
        },
    )
    assert r.status_code == 422, r.text


def test_legacy_voice_default_fields_keep_the_old_idempotency_fingerprint(env):
    c, service, now = setup_place(env)
    request = str(uuid.uuid4())
    fields = {
        "client_request_id": request,
        "kind": "scene",
        "purpose": "reflection",
        "transcript": "这里很安静",
        "target_place_id": "scenic",
        "scope": "poi",
        "visibility": "public",
        "applicability": "unconditional",
        "topics": [],
    }
    r = draft(c, "bob", fields)
    assert r.status_code == 201
    expected = hashlib.sha256(
        json.dumps(fields, sort_keys=True, ensure_ascii=False).encode() + wav()
    ).hexdigest()
    with service.scope() as db:
        assert service.post(db, r.json()["post_id"])["fingerprint"] == expected
    repeated = draft(
        c, "bob", {**fields, "validity_policy": "bounded", "content_origin": "human_original"}
    )
    assert repeated.status_code == 200 and repeated.json()["post_id"] == r.json()["post_id"]


def test_arrival_notice_reaches_active_voice_session_but_respects_quiet_and_category_gate(env):
    c, svc, now, session = setup_voice(env)
    with svc.scope() as db:
        db.execute(
            "INSERT INTO channels VALUES ('entry',?,'模拟入园须知','模拟运营方',1)", (MODAILY,)
        )
        db.execute("INSERT INTO channel_operators VALUES ('entry','bob')")
    base = {
        "kind": "announcement",
        "purpose": "notice",
        "transcript": "欢迎来到模力社区。",
        "target_place_id": MODAILY,
        "channel_id": "entry",
        "validity_policy": "until_withdrawn",
        "valid_from": stamp(now[0]),
        "source_note": "用户文案的合成模拟",
        "content_origin": "agent_synthesized",
    }

    def publish_notice(**changes):
        r = draft(c, "bob", {**base, **changes})
        assert r.status_code == 201, r.text
        pid = r.json()["post_id"]
        assert (
            c.post("/v1/posts/" + pid + "/confirm", headers=auth("bob"), json={}).status_code == 200
        )
        return pid

    primary = publish_notice()
    supplemental = publish_notice(applicability="unknown", transcript="访客请到一楼前台登记。")
    route = f"/v1/exchange/sessions/{session['id']}/inbox"
    items = c.get(route, headers=auth()).json()["items"]
    assert [d["payload"]["post_id"] for d in items] == [primary]
    assert not any(d["payload"]["post_id"] == supplemental for d in items)
    preferences(c, channel_enabled=False)
    assert c.get(route, headers=auth()).json()["items"] == []
    preferences(c, channel_enabled=True)
    profile = c.get("/v1/me", headers=auth()).json()["profile"]
    c.put(
        "/v1/me/context",
        headers=auth(),
        json={
            "expected_revision": profile["revision"],
            "mode": "quiet",
            "assist_enabled": profile["assist_enabled"],
            "focus": profile["focus"],
        },
    )
    blocked = c.get(route, headers=auth())
    assert blocked.status_code == 409 and blocked.json()["error"]["code"] == "MODE_QUIET"


def test_notice_import_is_idempotent_and_does_not_change_existing_people(env, tmp_path):
    c, service, now, session = setup_voice(env)
    before = c.get("/v1/me", headers=auth()).json()["profile"]
    audio = wav()
    (tmp_path / "notice.wav").write_bytes(audio)
    clips = [
        {
            "id": key,
            "text": text,
            "file": "notice.wav",
            "sha256": hashlib.sha256(audio).hexdigest(),
            "automatic_arrival": key == "welcome",
        }
        for key, text in [
            ("welcome", "欢迎来到模力社区。"),
            ("visitor", "访客请到一楼前台登记。"),
            ("customer", "园区客户请刷脸进入。"),
        ]
    ]
    (tmp_path / "manifest.json").write_text(
        json.dumps(
            {
                "poi_id": MODAILY,
                "synthetic": True,
                "source_note": "用户文案；合成模拟入园须知。",
                "clips": clips,
            }
        )
    )
    result = import_notices(service, tmp_path)
    assert len(result) == 3 and all(p["inserted"] for p in result)
    repeat = import_notices(service, tmp_path)
    assert not any(p["inserted"] for p in repeat)
    assert [p["post_id"] for p in repeat] == [p["post_id"] for p in result]
    assert c.get("/v1/me", headers=auth()).json()["profile"] == before
    with service.scope() as db:
        for record in result:
            post = service.post(db, record["post_id"])
            assert post["expires_at"] is None
            assert service.voice_payload(db, post)["synthetic"]
    items = c.get(f"/v1/exchange/sessions/{session['id']}/inbox", headers=auth()).json()["items"]
    assert [d["payload"]["post_id"] for d in items] == [result[0]["post_id"]]
