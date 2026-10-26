"""SNR-002: one execution path, separate grants, original-audio and race boundaries."""

import uuid

import pytest
from conftest import auth
from fastapi.testclient import TestClient
from test_agent_network import ISLAND, inbox, setup
from test_exchange import stamp, utter

from app.main import create_app
from app.models import ExchangeChange


def start_cap(c, user="alice", **capabilities):
    profile = c.get("/v1/me", headers=auth(user)).json()["profile"]
    response = c.post(
        "/v1/agent/sessions",
        headers=auth(user),
        json={
            "client_request_id": str(uuid.uuid4()),
            "expected_revision": profile["revision"],
            "place_id": "road" if user == "alice" else ISLAND["id"],
            "capabilities": capabilities,
        },
    )
    assert response.status_code == 200, response.text
    return response.json()["session"]


def metadata(session, now, transcript, request_id=None):
    return {
        "client_request_id": request_id or str(uuid.uuid4()),
        "expected_session_revision": session["revision"],
        "transcript": transcript,
        "recorded_at": stamp(now[0]),
    }


def text_turn(c, session, now, text, user="alice", request_id=None):
    return c.post(
        f"/v1/agent/sessions/{session['id']}/turns",
        headers=auth(user),
        json=metadata(session, now, text, request_id),
    )


def counts(svc):
    with svc.scope() as db:
        return {
            t: db.execute(f"SELECT count(*) FROM {t}").fetchone()[0]
            for t in ("posts", "deliveries", "chat_requests", "chat_messages")
        }


def test_private_query_without_publish_or_model_and_without_presence(env):
    c, svc, now, _ = setup(env)
    svc.exchange_decider.configured = False
    a = start_cap(c, private_query=True)
    assert not a["auto_publish"] and not a["network_enabled"]
    assert a["capabilities"]["private_query"] and not a["capabilities"]["auto_play"]
    with svc.scope() as db:
        db.execute("UPDATE profiles SET sampled_at=0 WHERE user_id='alice'")
    before = counts(svc)
    r = text_turn(c, a, now, "张江人工智能岛天气怎样？")
    assert r.status_code == 200, r.text
    assert r.json()["result"]["outcome"] == "information"
    assert r.json()["agent_response"]["sources"][0]["scope"] == "district"
    assert counts(svc) == before
    assert svc.exchange_decider.calls == 0


def test_private_audio_and_text_queries_share_semantics_and_private_chat(env):
    c, svc, now, _ = setup(env)
    a = start_cap(c, private_query=True)
    text = "去张江人工智能岛现在堵不堵？"
    written = text_turn(c, a, now, text).json()
    spoken = utter(c, a, now, transcript=text).json()
    assert written["agent_response"] == spoken["agent_response"]
    assert written["result"]["outcome"] == spoken["result"]["outcome"] == "information"
    private = text_turn(c, a, now, "我想和AI聊聊").json()
    assert private["result"]["outcome"] == "private_chat"
    assert text_turn(c, a, now, "今天有点累").json()["action"] == "private_chat"
    assert not any(counts(svc).values())


@pytest.mark.parametrize(
    "grants,text,decision",
    [
        ({"private_query": True}, "帮我问问张江人工智能岛还有车位吗？", "question"),
        ({"private_query": True}, "我想找个车友聊聊", "scene"),
        ({"publish_scene": True}, "张江人工智能岛天气怎样？", "scene"),
        ({"request_humans": True}, "金科路中环路下面堵死了。", "scene"),
        ({"publish_scene": True}, "我再说一句", "reply"),
    ],
)
def test_missing_each_grant_has_no_public_or_match_side_effect(env, grants, text, decision):
    c, svc, now, _ = setup(env)
    a = start_cap(c, **grants)
    svc.exchange_decider.action = decision
    before = counts(svc)
    result = utter(c, a, now, transcript=text).json()
    assert result["status"] == "deferred", result
    assert result["reason"] == "CAPABILITY_NOT_AUTHORIZED", result
    assert counts(svc) == before
    if "天气" in text:
        assert svc.geo.queries == []


def test_play_only_cannot_submit_and_query_only_cannot_autoplay(env):
    c, _, now, _ = setup(env)
    a = start_cap(c, auto_play=True)
    assert text_turn(c, a, now, "帮我查天气").status_code == 403
    a = start_cap(c, private_query=True)
    assert c.get(f"/v1/exchange/sessions/{a['id']}/inbox", headers=auth()).status_code == 403


def test_explicit_scene_permission_preserves_original_without_network_permission(env):
    c, svc, now, _ = setup(env)
    a = start_cap(c, publish_scene=True)
    original = utter(c, a, now, transcript="帮我分享，金科路中环路下面堵死了。").json()
    assert original["status"] == "published" and original["action"] == "scene", original
    assert not a["capabilities"]["request_humans"] and not a["capabilities"]["match_chat"]
    with svc.scope() as db:
        assert (
            svc.post(db, original["post_id"])["transcript"] == "帮我分享，金科路中环路下面堵死了。"
        )


def test_text_never_fabricates_human_audio_and_explicit_human_voice_still_works(env):
    c, svc, now, _ = setup(env)
    a = start_cap(c, request_humans=True, auto_play=True)
    before = counts(svc)
    result = text_turn(c, a, now, "帮我问问张江人工智能岛还有车位吗？").json()
    assert result["reason"] == "ORIGINAL_AUDIO_REQUIRED", result
    assert counts(svc) == before
    original = utter(c, a, now, transcript="帮我问问张江人工智能岛还有车位吗？").json()
    assert original["result"]["outcome"] == "human_request", original
    assert original["result"]["message_id"] == original["post_id"]


def test_ambiguous_human_query_can_use_text_to_complete_original_audio_after_restart(env):
    c, svc, now, _ = setup(env)
    a = start_cap(c, request_humans=True)
    first = utter(c, a, now, transcript="前面景区还有停车位吗？").json()
    assert first["result"]["outcome"] == "clarification", first
    assert not counts(svc)["posts"]
    app = create_app(svc.settings, lambda: now[0])
    app.state.service.geo = svc.geo
    app.state.service.exchange_decider = svc.exchange_decider
    with TestClient(app) as client:
        result = text_turn(client, a, now, "上海世纪公园").json()
        assert result["result"]["outcome"] == "human_request", result
        with app.state.service.scope() as db:
            post = app.state.service.post(db, result["post_id"])
            assert post["transcript"] == "前面景区还有停车位吗？"


def test_tool_failure_offer_cannot_escalate_private_only_authorization(env):
    c, svc, now, _ = setup(env)
    a = start_cap(c, private_query=True)
    svc.geo.fail = True
    offer = text_turn(c, a, now, "张江人工智能岛天气怎样？").json()
    assert offer["status"] == "clarifying"
    denied = text_turn(c, a, now, "好").json()
    assert denied["reason"] == "CAPABILITY_NOT_AUTHORIZED", denied
    assert not any(counts(svc).values())


def test_parking_without_human_permission_does_not_publish(env):
    c, svc, now, _ = setup(env)
    a = start_cap(c, private_query=True)
    result = utter(c, a, now, transcript="张江人工智能岛还有车位吗？").json()
    assert result["status"] == "clarifying", result
    assert result["agent_response"]["kind"] == "human_offer"
    assert not any(counts(svc).values())


def test_changed_authorization_or_cancelled_clarification_discards_late_results(env):
    c, svc, now, _ = setup(env)
    a = start_cap(c, private_query=True)

    def pause():
        with svc.scope() as db:
            svc.change_exchange(
                db, "alice", a["id"], ExchangeChange(expected_session_revision=1, state="paused")
            )

    svc.geo.after_search = pause
    late = text_turn(c, a, now, "上海世纪公园天气怎样？").json()
    assert late["status"] == "deferred" and late["reason"] == "SESSION_INACTIVE", late
    assert not any(counts(svc).values())


def test_new_capabilities_preserve_remote_original_answer_and_followup(env):
    c, svc, now, _ = setup(env)
    a = start_cap(c, request_humans=True, reply_thread=True, auto_play=True)
    b = start_cap(c, "bob", reply_thread=True, auto_play=True)
    question = utter(c, a, now, transcript="帮我问问张江人工智能岛现在堵不堵？").json()
    offered = inbox(c, b, "bob")[0]
    c.put(
        f"/v1/exchange/sessions/{b['id']}/context",
        headers=auth("bob"),
        json={"expected_session_revision": 1, "post_id": question["post_id"]},
    )
    svc.exchange_decider.action = "answer"
    answer = utter(c, b, now, "bob", transcript="还有几个车位，天气也还好。").json()
    assert answer["status"] == "published" and answer["action"] == "answer", answer
    assert (
        svc.geo.queries == []
    )  # Registered AMap shortcut resolved without another provider search.
    returned = inbox(c, a)[0]
    c.put(
        f"/v1/exchange/sessions/{a['id']}/context",
        headers=auth(),
        json={"expected_session_revision": 1, "post_id": returned["payload"]["post_id"]},
    )
    svc.exchange_decider.action = "reply"
    follow = utter(c, a, now, transcript="西门还有多少车位？").json()
    assert follow["root_id"] == question["post_id"], follow
    assert offered["payload"]["post_id"] == question["post_id"]


def test_http_and_actual_mcp_transport_share_turn_idempotency_acl_and_clarification(env):
    c, svc, now, _ = setup(env)
    svc.settings.mcp_enabled, svc.settings.mcp_secret = True, "m" * 40
    svc.settings.alice_agent_id, svc.settings.bob_agent_id = "agent-a", "agent-b"
    svc.seed()
    a = start_cap(c, private_query=True)

    def call_mcp(body, user="alice", discovery=False):
        headers = {
            "Authorization": "Bearer " + svc.settings.mcp_secret,
            "Accept": "application/json, text/event-stream",
        }
        if not discovery:
            headers.update(
                {
                    "X-Linx-Device-Id": getattr(svc.settings, f"{user}_device_id"),
                    "X-Linx-Agent-Id": getattr(svc.settings, f"{user}_agent_id"),
                    "X-Linx-Session-Id": "test-contract",
                }
            )
        r = c.post(
            "/mcp/http",
            headers=headers,
            json={
                "jsonrpc": "2.0",
                "id": 1,
                "method": "tools/call",
                "params": {"name": "agent_turn", "arguments": body},
            },
        )
        assert r.status_code == 200, r.text
        return r.json()["result"]

    first = metadata(a, now, "去公司路况怎样？")
    http = c.post(f"/v1/agent/sessions/{a['id']}/turns", headers=auth(), json=first).json()
    mcp = call_mcp({"session_id": a["id"], **first})
    assert mcp["structuredContent"] == http
    assert http["status"] == "clarifying"
    continuation = call_mcp({"session_id": a["id"], **metadata(a, now, "张江人工智能岛")})
    assert continuation["structuredContent"]["result"]["outcome"] == "information"
    denied = call_mcp({"session_id": a["id"], **first}, user="bob")
    assert denied["isError"] and "NOT_FOUND" in denied["content"][0]["text"]
    anonymous = call_mcp({"session_id": a["id"], **first}, discovery=True)
    assert anonymous["isError"] and "MCP_IDENTITY_MISSING" in anonymous["content"][0]["text"]
    assert not any(counts(svc).values())
