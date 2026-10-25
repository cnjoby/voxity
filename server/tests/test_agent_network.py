import uuid
from datetime import datetime

from conftest import auth, wav
from fastapi.testclient import TestClient
from test_exchange import Decisions, prepare, utter

from app.errors import Problem
from app.main import create_app
from app.storage import digest

ISLAND = {
    "id": "B0FFJEOSPR",
    "label": "张江人工智能岛",
    "latitude": 31.185578,
    "longitude": 121.609707,
    "kind": "poi",
}
PARK = {
    "id": "amap:park",
    "label": "上海世纪公园",
    "latitude": 31.215725,
    "longitude": 121.552563,
    "kind": "poi",
}


class Maps:
    def __init__(self, now):
        self.now, self.queries, self.fail, self.after_search = now, [], False, None

    def search(self, query):
        self.queries.append(query)
        if self.after_search:
            self.after_search()
        return [p for p in [ISLAND, PARK] if query in p["label"]]

    def weather(self, place):
        if self.fail:
            raise Problem(503, "WEATHER_UNAVAILABLE", "unavailable")
        from zoneinfo import ZoneInfo

        return {
            "city": "浦东新区",
            "weather": "小雨",
            "temperature": "20",
            "provider": "amap",
            "scope": "district",
            "reporttime": datetime.fromtimestamp(
                self.now[0], ZoneInfo("Asia/Shanghai")
            ).isoformat(),
        }

    def driving(self, origin, place):
        if self.fail:
            raise Problem(503, "ROUTE_UNAVAILABLE", "unavailable")
        return {
            "duration_seconds": 1500,
            "distance_m": 12345,
            "traffic_m": {"拥堵": 1500, "畅通": 10845},
            "provider": "amap",
            "scope": "driving_route",
        }


def setup(env):
    c, svc, now = prepare(env)
    svc.geo = Maps(now)
    with svc.scope() as db:
        svc.register_agent_place(db, ISLAND)
        db.execute(
            "UPDATE profiles SET latitude=?,longitude=? WHERE user_id='bob'",
            (ISLAND["latitude"], ISLAND["longitude"]),
        )

    def start(user="alice", network=True):
        profile = c.get("/v1/me", headers=auth(user)).json()["profile"]
        r = c.post(
            "/v1/exchange/sessions",
            headers=auth(user),
            json={
                "client_request_id": str(uuid.uuid4()),
                "expected_revision": profile["revision"],
                "place_id": "road" if user == "alice" else ISLAND["id"],
                "auto_publish": True,
                "auto_play": True,
                "network_enabled": network,
            },
        )
        assert r.status_code == 200, r.text
        return r.json()["session"]

    return c, svc, now, start


def inbox(c, session, user="alice"):
    r = c.get(f"/v1/exchange/sessions/{session['id']}/inbox", headers=auth(user))
    assert r.status_code == 200, r.text
    return r.json()["items"]


def test_remote_human_question_original_answer_followup_and_played(env):
    c, svc, now, start = setup(env)
    a, b = start(), start("bob")
    q = utter(c, a, now, transcript="帮我问问张江人工智能岛现在堵不堵？").json()
    assert q["status"] == "published", q
    assert q["target_place"]["id"] == ISLAND["id"]
    offered = inbox(c, b, "bob")[0]
    assert c.get(offered["payload"]["audio_url"], headers=auth("bob")).content == wav()
    assert (
        c.put(
            f"/v1/exchange/sessions/{b['id']}/context",
            headers=auth("bob"),
            json={"expected_session_revision": 1, "post_id": q["post_id"]},
        ).status_code
        == 200
    )
    svc.exchange_decider.action = "answer"
    answer = utter(c, b, now, "bob", transcript="我在入口，排队大约十分钟。").json()
    assert answer["status"] == "published", answer
    returned = inbox(c, a)[0]
    assert returned["payload"]["post_id"] == answer["post_id"]
    assert c.get(returned["payload"]["audio_url"], headers=auth()).content == wav()
    assert (
        c.put(
            f"/v1/exchange/sessions/{a['id']}/context",
            headers=auth(),
            json={"expected_session_revision": 1, "post_id": answer["post_id"]},
        ).status_code
        == 200
    )
    # The owner can continue the actual thread even after moving away from their session's initial place.
    with svc.scope() as db:
        db.execute("UPDATE profiles SET latitude=30.9 WHERE user_id='alice'")
    svc.exchange_decider.action = "reply"
    follow = utter(c, a, now, transcript="我指的是西门入口。").json()
    assert follow["root_id"] == q["post_id"], follow
    assert inbox(c, b, "bob")[0]["payload"]["post_id"] == follow["post_id"]
    ack = c.post(
        f"/v1/inbox/{returned['id']}/ack",
        headers=auth(),
        json={"event": "played", "request_id": returned["request_id"]},
    )
    assert ack.status_code == 200 and ack.json()["delivery"]["state"] == "played"
    assert c.get("/v1/questions/" + q["post_id"], headers=auth("bob")).status_code == 404


def test_ambiguous_scenic_area_never_uses_session_place_and_continuation_survives_restart(env):
    c, svc, now, start = setup(env)
    a = start()
    original = utter(c, a, now, transcript="HI，声呐，前面景区还有停车位吗？").json()
    assert original["status"] == "clarifying"
    assert "哪个景区" in original["agent_response"]["text"]
    assert not svc.geo.queries
    with svc.scope() as db:
        assert db.execute("SELECT count(*) FROM posts").fetchone()[0] == 0
    restarted = create_app(svc.settings, lambda: now[0])
    restarted.state.service.exchange_decider = Decisions()
    restarted.state.service.geo = Maps(now)
    with TestClient(restarted) as client:
        result = utter(client, a, now, transcript="上海世纪公园").json()
        assert (
            result["status"] == "clarifying" and result["agent_response"]["kind"] == "human_offer"
        )
        result = utter(client, a, now, transcript="好").json()
        assert result["status"] == "published", result
        assert result["target_place"]["id"] == PARK["id"]
        assert client.get("/v1/audio/" + result["post_id"], headers=auth()).content == wav()
        task = client.get("/v1/questions/" + result["post_id"], headers=auth()).json()
        assert task["target_place"]["label"] == PARK["label"]
        assert (
            client.get("/v1/exchange/turns/" + original["turn_id"], headers=auth()).json()[
                "post_id"
            ]
            == result["post_id"]
        )
        with restarted.state.service.scope() as db:
            assert (
                db.execute("SELECT transcript FROM posts").fetchone()[0]
                == "HI，声呐，前面景区还有停车位吗？"
            )
            assert db.execute("SELECT count(*) FROM agent_pending").fetchone()[0] == 0


def test_duplicate_poi_names_require_selection_and_expired_clarification_cannot_publish(env):
    c, svc, now, start = setup(env)
    a = start()
    svc.geo.search = lambda query: [PARK, {**PARK, "id": "another-park", "longitude": 120}]
    first = utter(c, a, now, transcript="世纪公园还有停车位吗？").json()
    assert first["status"] == "clarifying"
    selected = utter(c, a, now, transcript="第二个").json()
    assert (
        selected["status"] == "clarifying" and selected["agent_response"]["kind"] == "human_offer"
    )
    selected = utter(c, a, now, transcript="好").json()
    assert selected["target_place"]["id"] == "another-park"
    c.post("/v1/questions/" + selected["post_id"] + "/cancel", headers=auth(), json={})
    first = utter(c, a, now, transcript="前面景区还有车位吗？").json()
    now[0] += 181
    late = utter(c, a, now, transcript="上海世纪公园").json()
    assert late.get("status") != "published"


def test_weather_and_route_are_private_sourced_agent_responses_not_human_audio(env):
    c, svc, now, start = setup(env)
    a = start()
    weather = utter(c, a, now, transcript="张江人工智能岛下雨了吗？").json()
    assert weather["status"] == "responded", weather
    assert weather["agent_response"]["speaker"] == "agent"
    assert weather["agent_response"]["sources"][0]["scope"] == "district"
    assert "不能确认景区此刻" in weather["agent_response"]["text"]
    route = utter(c, a, now, transcript="HI，声呐，我想去张江人工智能岛，现在堵车吗？").json()
    assert route["agent_response"]["kind"] == "route", route
    assert "25分钟" in route["agent_response"]["text"]
    assert "origin" not in route["agent_response"]["sources"][0]
    with svc.scope() as db:
        assert db.execute("SELECT count(*) FROM posts").fetchone()[0] == 0
    with svc.scope() as db:
        db.execute("UPDATE profiles SET sampled_at=0 WHERE user_id='alice'")
    stale = utter(c, a, now, transcript="去张江人工智能岛路况怎样？").json()
    assert stale["agent_response"]["kind"] == "location_required", stale


def test_company_location_is_clarified_and_is_private_to_this_user(env):
    c, svc, now, start = setup(env)
    a, b = start(), start("bob")
    first = utter(c, a, now, transcript="去公司路况怎样？").json()
    assert first["status"] == "clarifying"
    answer = utter(c, a, now, transcript="张江人工智能岛").json()
    assert answer["agent_response"]["kind"] == "route", answer
    assert utter(c, a, now, transcript="去公司路况怎样？").json()["status"] == "responded"
    other = utter(c, b, now, "bob", transcript="去公司路况怎样？").json()
    assert other["status"] == "clarifying" and "公司在哪" in other["agent_response"]["text"]


def test_tool_failure_only_offers_human_question_until_explicit_yes(env):
    c, svc, now, start = setup(env)
    a = start()
    svc.geo.fail = True
    first = utter(c, a, now, transcript="张江人工智能岛下雨了吗？").json()
    assert first["status"] == "clarifying"
    with svc.scope() as db:
        assert db.execute("SELECT count(*) FROM posts").fetchone()[0] == 0
    published = utter(c, a, now, transcript="好").json()
    assert published["status"] == "published", published
    assert published["target_place"]["id"] == ISLAND["id"]


def test_no_chat_peer_offers_ai_only_after_yes_and_no_does_not_start_ai(env):
    c, svc, now, start = setup(env)
    a = start()
    offer = utter(c, a, now, transcript="Hi，声呐，我想找车友聊聊").json()
    assert offer["status"] == "clarifying" and offer["agent_response"]["kind"] == "chat_offer", (
        offer
    )
    assert utter(c, a, now, transcript="不用").json()["status"] == "ignored"
    utter(c, a, now, transcript="我想找人聊天")
    yes = utter(c, a, now, transcript="好啊").json()
    assert yes["action"] == "agent_chat"
    future = utter(c, a, now, transcript="今天有点累").json()
    assert future["action"] == "private_chat", future
    with svc.scope() as db:
        assert db.execute("SELECT count(*) FROM posts").fetchone()[0] == 0
        assert db.execute("SELECT count(*) FROM chat_messages").fetchone()[0] == 0


def test_two_willing_users_pair_send_exact_originals_and_cannot_leak_to_third_user(env):
    c, svc, now, start = setup(env)
    a, b = start(), start("bob")
    # Being online with assist_enabled is insufficient: only the explicit second request pairs.
    assert utter(c, a, now, transcript="我想找车友聊聊").json()["status"] == "clarifying"
    assert inbox(c, b, "bob") == []
    paired = utter(c, b, now, "bob", transcript="我也想找车友聊聊").json()
    assert paired["action"] == "human_chat", paired
    initial_a, initial_b = inbox(c, a)[0], inbox(c, b, "bob")[0]
    assert initial_a["payload"]["source_type"] == "human_original"
    # A late yes to the old AI offer goes to the newly matched human, never to an AI.
    assert utter(c, a, now, transcript="你好，很高兴认识你").json()["action"] == "human_chat"
    messages = inbox(c, b, "bob")
    assert len(messages) == 2
    assert any(d["payload"]["transcript"] == "你好，很高兴认识你" for d in messages)
    for d in messages:
        assert c.get(d["payload"]["audio_url"], headers=auth("bob")).content == wav()
    assert (
        c.put(
            f"/v1/exchange/sessions/{b['id']}/context",
            headers=auth("bob"),
            json={"expected_session_revision": 1, "post_id": initial_b["payload"]["post_id"]},
        ).status_code
        == 200
    )
    with svc.scope() as db:
        db.execute(
            "INSERT INTO users VALUES (?,?,?,?)",
            ("charlie", digest("c" * 40), "device-c", "agent-c"),
        )
    assert (
        c.get(
            initial_a["payload"]["audio_url"], headers={"Authorization": "Bearer " + "c" * 40}
        ).status_code
        == 404
    )
    assert c.get("/v1/inbox/" + initial_a["id"], headers=auth("bob")).status_code == 404
    ack = c.post(
        "/v1/inbox/" + initial_b["id"] + "/ack",
        headers=auth("bob"),
        json={"event": "played", "request_id": initial_b["request_id"]},
    )
    assert ack.json()["delivery"]["state"] == "played"
    assert len(inbox(c, b, "bob")) == 1
    c.put(
        f"/v1/exchange/sessions/{a['id']}",
        headers=auth(),
        json={"expected_session_revision": 1, "state": "ended"},
    )
    assert c.get(initial_a["payload"]["audio_url"], headers=auth()).status_code == 404
    assert inbox(c, b, "bob") == []


def test_network_permission_and_late_map_results_cannot_publish(env):
    c, svc, now, start = setup(env)
    a = start(network=False)
    result = utter(c, a, now, transcript="帮我问问张江人工智能岛还有车位吗？").json()
    assert result["reason"] == "NETWORK_NOT_AUTHORIZED"
    a = start()

    def revoke():
        c.put(
            f"/v1/exchange/sessions/{a['id']}",
            headers=auth(),
            json={"expected_session_revision": 1, "state": "paused"},
        )

    svc.geo.after_search = revoke
    result = utter(c, a, now, transcript="帮我问问上海世纪公园还有车位吗？").json()
    assert result["status"] == "deferred"
    with svc.scope() as db:
        assert db.execute("SELECT count(*) FROM posts").fetchone()[0] == 0


def test_negative_publish_clears_pending_and_active_question_does_not_leave_orphan_audio(env):
    c, svc, now, start = setup(env)
    a = start()
    utter(c, a, now, transcript="前面景区还有车位吗？")
    assert utter(c, a, now, transcript="刚才的问题别发出去").json()["status"] == "ignored"
    with svc.scope() as db:
        assert svc.pending_agent(db, a["id"]) is None
    first = utter(c, a, now, transcript="帮我问问张江人工智能岛现在堵不堵？").json()
    assert first["status"] == "published"
    before = set(svc.store.audio_dir.iterdir())
    second = utter(c, a, now, transcript="帮我问问上海世纪公园还有停车位吗？").json()
    assert (
        second["status"] == "clarifying" and second["agent_response"]["kind"] == "replace_question"
    )
    assert set(svc.store.audio_dir.iterdir()) == before


def test_parking_observation_with_haiyou_is_an_answer_not_a_new_query(env):
    c, svc, now, start = setup(env)
    a, b = start(), start("bob")
    question = utter(c, a, now, transcript="帮我问问张江人工智能岛还有停车位吗？").json()
    invitation = inbox(c, b, "bob")[0]
    c.put(
        f"/v1/exchange/sessions/{b['id']}/context",
        headers=auth("bob"),
        json={"expected_session_revision": 1, "post_id": question["post_id"]},
    )
    before = list(svc.geo.queries)
    svc.exchange_decider.action = "answer"
    answer = utter(c, b, now, "bob", transcript="我在西门停车场，里面还有十几个空位。").json()
    assert answer["status"] == "published" and answer["action"] == "answer", answer
    assert svc.geo.queries == before
    assert invitation["payload"]["kind"] == "question"


def test_personal_agent_feed_isolated_and_user_can_seek_humans_again_after_ai_companion(env):
    c, svc, now, start = setup(env)
    a = start()
    utter(c, a, now, transcript="去公司路况怎样？")
    utter(c, a, now, transcript="张江人工智能岛")
    utter(c, a, now, transcript="我想找车友聊聊")
    utter(c, a, now, transcript="好啊")
    with svc.scope() as db:
        own, other = svc.get_feed(db, "alice"), svc.get_feed(db, "bob")
        assert own["destinations"][0]["place_id"] == ISLAND["id"]
        assert own["chat"][0]["state"] == "agent"
        assert other["destinations"] == [] and other["chat"] == []
    again = utter(c, a, now, transcript="我想找车友聊天").json()
    assert again["agent_response"]["kind"] == "chat_offer", again


def test_personal_task_answer_has_priority_and_mixed_network_inbox_is_bounded(env):
    c, svc, now, start = setup(env)
    a, b = start(), start("bob")
    question = utter(c, a, now, transcript="帮我问问张江人工智能岛还有停车位吗？").json()
    offered = inbox(c, b, "bob")[0]
    c.put(
        f"/v1/exchange/sessions/{b['id']}/context",
        headers=auth("bob"),
        json={"expected_session_revision": 1, "post_id": offered["payload"]["post_id"]},
    )
    svc.exchange_decider.action = "answer"
    answer = utter(c, b, now, "bob", transcript="我在西门停车场，还有十几个空位。").json()
    assert answer["status"] == "published"
    utter(c, a, now, transcript="我想找车友聊聊")
    utter(c, b, now, "bob", transcript="我也想找车友聊聊")
    for i in range(12):
        assert (
            utter(c, b, now, "bob", transcript=f"第{i}条普通聊天").json()["action"] == "human_chat"
        )
    items = inbox(c, a)
    assert len(items) == 10
    assert items[0]["payload"]["post_id"] == answer["post_id"]
    assert items[0]["question_id"] == question["post_id"]
