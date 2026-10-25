"""SNR-001: read contracts preserve provenance, ACLs and legacy persisted grants."""

import json
from pathlib import Path

import pytest
from conftest import auth, draft
from pydantic import ValidationError
from test_exchange import prepare, start, utter
from test_voice_matching import setup_place, voice

from app.contracts import AgentTurnResult, SessionCapabilities, VoiceMessageView
from app.models import ExchangeStart
from app.service import Service

FIXTURES = Path(__file__).parents[2] / "docs/development/fixtures/contracts-v1.json"


def test_versioned_fixtures_and_defaults():
    data = json.loads(FIXTURES.read_text())
    assert not SessionCapabilities().any_enabled()
    assert SessionCapabilities.model_validate(data["capabilities"]).private_query
    assert ExchangeStart.model_validate(data["legacy_session"]).capabilities is None
    assert ExchangeStart.model_validate(data["private_session"]).capabilities.private_query
    message = VoiceMessageView.model_validate(data["operator_message"])
    assert message.subject.source_type == "poi_operator"
    assert message.body.content_origin == "human_original"
    assert message.time.observed_at is None
    assert AgentTurnResult.model_validate(data["private_result"]).outcome == "information"
    frozen = json.loads((FIXTURES.parent.parent / "contracts/v1/schemas.json").read_text())
    assert frozen == {
        m.__name__: m.model_json_schema()
        for m in (VoiceMessageView, AgentTurnResult, SessionCapabilities)
    }
    for model, invalid in [
        (SessionCapabilities, {"private_query": "true"}),
        (SessionCapabilities, {"private_query": True, "operator": True}),
        (ExchangeStart, {**data["private_session"], "auto_publish": False}),
        (VoiceMessageView, {**data["operator_message"], "kind": "chat"}),
        (AgentTurnResult, {**data["private_result"], "outcome": "published"}),
    ]:
        with pytest.raises(ValidationError):
            model.model_validate(invalid)


def test_operator_original_projection_and_unknown_times(env):
    c, _, _ = setup_place(env)
    pid = voice(env, kind="announcement")
    r = c.get(f"/v1/messages/{pid}", headers=auth()).json()["message"]
    view = VoiceMessageView.model_validate(r)
    assert view.subject.source_type == "poi_operator"
    assert view.subject.channel_id == "parking"
    assert view.body.content_origin == "human_original"
    assert view.time.observed_at is None and view.time.recorded_at is None
    assert view.state.moderation == "not_evaluated"
    assert view.event.event_id is None
    assert view.original_request_id is None
    assert "latitude" not in json.dumps(r)
    assert c.get("/v1/contracts", headers=auth()).json()["schemas"]["VoiceMessageView"]
    assert c.get("/v1/contracts").status_code == 401
    c.post(f"/v1/voices/{pid}/withdraw", headers=auth("bob"), json={})
    assert c.get(f"/v1/messages/{pid}", headers=auth()).status_code == 404
    own = c.get(f"/v1/messages/{pid}", headers=auth("bob")).json()["message"]
    assert own["state"]["publication"] == "withdrawn"


def test_draft_acl_and_scene_conversation_projection(env):
    c, svc, now = prepare(env)
    pid = draft(c).json()["post_id"]
    assert c.get(f"/v1/messages/{pid}", headers=auth("bob")).status_code == 404
    own = c.get(f"/v1/messages/{pid}", headers=auth()).json()["message"]
    assert own["state"]["publication"] == "draft"
    a, b = start(c).json()["session"], start(c, "bob").json()["session"]
    scene = utter(c, a, now).json()["post_id"]
    c.put(
        f"/v1/exchange/sessions/{b['id']}/context",
        headers=auth("bob"),
        json={"expected_session_revision": 1, "post_id": scene},
    )
    svc.exchange_decider.action = "reply"
    reply = utter(c, b, now, "bob", transcript="刚开始动了").json()["post_id"]
    view = c.get(f"/v1/messages/{reply}", headers=auth()).json()["message"]
    assert view["conversation_id"] == scene
    assert view["reply_to_message_id"] == scene
    assert view["recipient_ids"] == ["alice"]
    assert view["subject"]["acting_agent_id"] == "personal:bob"
    assert view["time"]["observed_at"] is None


def test_additive_migration_and_legacy_fingerprint_do_not_upgrade_grants(env):
    c, svc, now = prepare(env)
    request = json.loads(FIXTURES.read_text())["legacy_session"]
    first = c.post("/v1/exchange/sessions", headers=auth(), json=request)
    assert first.status_code == 200
    sid = first.json()["session"]["id"]
    published = utter(
        c, first.json()["session"], now, transcript="帮我分享，金科路中环路下面堵死了。"
    ).json()["post_id"]
    audio = (svc.store.audio_dir / f"{published}.wav").read_bytes()
    db = svc.store.connect()
    db.execute("ALTER TABLE exchange_sessions DROP COLUMN capabilities_json")
    db.close()
    restarted = Service(svc.settings, svc.clock)
    with restarted.scope() as db:
        restored = restarted.start_exchange(db, "alice", ExchangeStart.model_validate(request))
        row = restarted.exchange_row(db, "alice", sid)
        assert row["capabilities_json"] is None
        assert restored["session"]["id"] == sid
        assert restored["session"]["authorization_format"] == "legacy"
        assert not restored["session"]["capabilities"]["request_humans"]
        assert not restored["session"]["capabilities"]["match_chat"]
        assert restarted.post(db, published)["status"] == "published"
        assert (restarted.store.audio_dir / f"{published}.wav").read_bytes() == audio
