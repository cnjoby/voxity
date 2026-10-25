import asyncio
import json
import uuid
from datetime import datetime
from zoneinfo import ZoneInfo

import httpx
import pytest
from conftest import auth, wav
from test_exchange import Decisions, stamp, utter

from app.demo_audio import import_demo
from app.voice_first import VoiceIntentDecider

MODAILY = "B0K2KHYN77"
ISLAND = "B0FFJSBSGR"


def test_gps_replaces_session_here_without_retargeting_published_question(env):
    c, service, now, session = setup_voice(env)
    setup_voice(env, "bob")
    question = utter(c, session, now, transcript="帮我问问模力社区的人，还有车位吗？").json()
    assert question["status"] == "published"
    switched = utter(c, session, now, transcript="换回手机真实位置").json()
    assert switched["voice_action"]["use_real_location"]
    assert switched["session"]["place_id"] == "private:unlocated"
    with service.scope() as db:
        resolved_place = dict(db.execute("SELECT * FROM places WHERE id=?", (ISLAND,)).fetchone())
    service.geo.resolve = lambda fix: {
        "latitude": fix.latitude,
        "longitude": fix.longitude,
        "places": [resolved_place],
    }
    profile = switched["profile"]
    response = c.put(
        "/v1/me/location",
        headers=auth(),
        json={
            "expected_revision": profile["revision"],
            "location": {
                "latitude": resolved_place["latitude"],
                "longitude": resolved_place["longitude"],
                "accuracy_m": 10,
                "coordinate_system": "gcj02",
                "source": "android",
                "sampled_at": stamp(now[0]),
            },
        },
    )
    assert response.status_code == 200, response.text
    latest = c.get(f"/v1/exchange/sessions/{session['id']}", headers=auth()).json()["session"]
    assert latest["place_id"] == ISLAND and not latest["remote"]
    with service.scope() as db:
        assert service.post(db, question["post_id"])["target_place_id"] == MODAILY
        assert service.profile_row(db, "bob")["place_id"] == MODAILY


def test_bootstrap_does_not_claim_a_fake_location(tmp_path):
    from conftest import ALICE, BOB

    from app.config import Settings
    from app.main import create_app

    service = create_app(
        Settings(_env_file=None, data_dir=tmp_path, alice_token=ALICE, bob_token=BOB)
    ).state.service
    service.seed()
    with service.scope() as db:
        for user in ("alice", "bob"):
            p = service.profile_row(db, user)
            assert p["source"] == "unavailable" and p["latitude"] is None
        assert not db.execute("SELECT 1 FROM places WHERE id LIKE 'demo:%'").fetchone()


class Routes:
    def __init__(self):
        self.force = None
        self.calls = []

    async def route(self, state):
        self.calls.append(state)
        return self.force or ("reply" if state.get("heard_context") else "private_chat")


def setup_voice(env, user="alice"):
    c, svc, now, settings = env
    svc.exchange_decider = Decisions()
    if not isinstance(svc.voice_intent, Routes):
        svc.voice_intent = Routes()
    profile = c.get("/v1/me", headers=auth(user)).json()["profile"]
    r = c.put(
        "/v1/me/remote-place",
        headers=auth(user),
        json={"expected_revision": profile["revision"], "place_id": MODAILY},
    )
    assert r.status_code == 200, r.text
    profile = r.json()["profile"]
    r = c.post(
        "/v1/agent/sessions",
        headers=auth(user),
        json={
            "client_request_id": str(uuid.uuid4()),
            "expected_revision": profile["revision"],
            "place_id": MODAILY,
            "timezone": "Asia/Shanghai",
        },
    )
    assert r.status_code == 200, r.text
    return c, svc, now, r.json()["session"]


def heard(c, session, post_id, user="alice"):
    r = c.put(
        f"/v1/exchange/sessions/{session['id']}/context",
        headers=auth(user),
        json={"expected_session_revision": session["revision"], "post_id": post_id},
    )
    assert r.status_code == 200, r.text


def test_one_default_session_is_private_and_shortcuts_are_provider_ids(env):
    c, s, n, a = setup_voice(env)
    assert a["interaction_mode"] == "voice_first" and a["remote"]
    assert a["voice_policy"]["private_default"] and not a["voice_policy"]["continuous_share"]
    me = c.get("/v1/me", headers=auth()).json()
    assert {p["id"] for p in me["place_shortcuts"]} == {MODAILY, ISLAND, "B0FFHPL61V"}
    assert me["profile"]["presence"]["source"] == "remote_place"
    assert me["profile"]["presence"]["remote"]
    r = utter(c, a, n, transcript="今天路上好累").json()
    assert r["action"] == "private_chat"
    with s.scope() as db:
        assert db.execute("SELECT count(*) FROM posts").fetchone()[0] == 0


def test_ordinary_parking_offers_then_yes_publishes_original_audio_once(env):
    c, s, n, a = setup_voice(env)
    r = utter(c, a, n, transcript="模力社区现在还能停车吗？").json()
    assert r["status"] == "clarifying" and r["agent_response"]["kind"] == "human_offer", r
    with s.scope() as db:
        assert not db.execute("SELECT id FROM posts").fetchone()
    yes = utter(c, a, n, transcript="好").json()
    assert yes["status"] == "published", yes
    assert yes["target_place"]["id"] == MODAILY
    with s.scope() as db:
        p = s.post(db, yes["post_id"])
        assert p["transcript"] == "模力社区现在还能停车吗？"
        assert json.loads(p["recorded_location"])["source"] == "remote_place"
    assert c.get("/v1/audio/" + yes["post_id"], headers=auth()).content == wav()


def test_ask_answer_followup_requires_actually_heard_context(env):
    c, s, n, a = setup_voice(env)
    _, _, _, b = setup_voice(env, "bob")
    q = utter(c, a, n, transcript="帮我问问模力社区的人，现在还能停车吗？").json()
    assert q["status"] == "published", q
    # A pending invitation is insufficient without a playback context.
    before = utter(c, b, n, "bob", transcript="东门还有三个位置").json()
    assert before["action"] == "private_chat", before
    items = c.get(f"/v1/exchange/sessions/{b['id']}/inbox", headers=auth("bob")).json()["items"]
    assert items and items[0]["payload"]["post_id"] == q["post_id"]
    heard(c, b, q["post_id"], "bob")
    answer = utter(c, b, n, "bob", transcript="东门还有三个位置").json()
    assert answer["status"] == "published" and answer["action"] == "answer", answer
    heard(c, a, answer["post_id"])
    follow = utter(c, a, n, transcript="问他从哪条路进去").json()
    assert follow["status"] == "published" and follow["root_id"] == q["post_id"], follow
    assert (
        "远程地点"
        in c.get("/v1/messages/" + answer["post_id"], headers=auth())
        .json()["message"]["subject"]
        .get("author_id", "")
        or c.get("/v1/audio/" + answer["post_id"], headers=auth()).status_code == 200
    )


def test_reply_window_expires_and_model_failure_does_not_publish(env):
    c, s, n, a = setup_voice(env)
    _, _, _, b = setup_voice(env, "bob")
    q = utter(c, a, n, transcript="帮我问问模力社区停车？").json()
    heard(c, b, q["post_id"], "bob")
    s.voice_intent.force = "defer"
    r = utter(c, b, n, "bob", transcript="车位还有").json()
    assert r["action"] == "private_chat"
    n[0] += 121
    s.voice_intent.force = None
    r = utter(c, b, n, "bob", transcript="东门还有位置").json()
    assert r.get("status") != "published"


def test_scoped_participation_preserves_own_reply_and_old_refusal(env):
    c, s, n, a = setup_voice(env)
    blocked = utter(c, a, n, transcript="今天别问我").json()
    assert blocked["status"] == "responded", blocked
    with s.scope() as db:
        p = s.profile_row(db, "alice")
        assert not s.voice_invite_allowed(db, p)
        pref = db.execute("SELECT * FROM voice_preferences WHERE user_id='alice'").fetchone()
        assert pref["session_id"] is None
        assert datetime.fromtimestamp(pref["until_at"], ZoneInfo("Asia/Shanghai")).hour == 0
        assert p["assist_enabled"] == 1
    assert utter(c, a, n, transcript="帮我问问模力社区能停车吗？").json()["status"] == "published"
    permanent = utter(c, a, n, transcript="以后都别邀请我回答").json()
    assert permanent["status"] == "responded"
    with s.scope() as db:
        assert s.profile_row(db, "alice")["assist_enabled"] == 0
    _, _, _, another = setup_voice(env)
    with s.scope() as db:
        assert not s.voice_invite_allowed(db, s.profile_row(db, "alice"))
    assert not another["voice_policy"]["continuous_share"]


def test_session_invite_preference_expires_when_session_ends(env):
    c, s, n, a = setup_voice(env)
    assert utter(c, a, n, transcript="这次只听不回答").json()["status"] == "responded"
    with s.scope() as db:
        assert not s.voice_invite_allowed(db, s.profile_row(db, "alice"))
    c.put(
        f"/v1/exchange/sessions/{a['id']}",
        headers=auth(),
        json={"expected_session_revision": a["revision"], "state": "ended"},
    )
    with s.scope() as db:
        assert s.voice_invite_allowed(db, s.profile_row(db, "alice"))


@pytest.mark.parametrize("text", ["我不想回答别人了", "我不愿意回答别人的问题", "今天别问我"])
def test_negative_intent_never_enables_participation(env, text):
    c, s, n, a = setup_voice(env)
    r = utter(c, a, n, transcript=text).json()
    assert r["status"] == "responded", r
    with s.scope() as db:
        assert not s.voice_invite_allowed(db, s.profile_row(db, "alice"))


def test_model_preference_without_polarity_clarifies_then_stays_closed(env):
    c, s, n, a = setup_voice(env)
    s.voice_intent.force = "participation"
    r = utter(c, a, n, transcript="调整一下邀请习惯").json()
    assert r["status"] == "clarifying", r
    with s.scope() as db:
        assert not db.execute("SELECT * FROM voice_preferences").fetchone()
    r = utter(c, a, n, transcript="好").json()
    assert r["status"] == "responded", r
    with s.scope() as db:
        assert not s.voice_invite_allowed(db, s.profile_row(db, "alice"))


def test_continuous_sharing_is_explicit_and_can_be_revoked(env):
    c, s, n, a = setup_voice(env)
    first = utter(c, a, n, transcript="模力社区路口排队了").json()
    assert first["action"] == "private_chat"
    consent = utter(c, a, n, transcript="这一路现场观察都帮我分享").json()
    assert consent["status"] == "responded", consent
    shared = utter(c, a, n, transcript="路口现在排了三辆车").json()
    assert shared["status"] == "published" and shared["action"] == "scene", shared
    assert utter(c, a, n, transcript="本次不要再分享").json()["status"] == "responded"
    assert utter(c, a, n, transcript="东门开始排队").json()["action"] == "private_chat"


def test_task_status_cancel_and_replacement_have_no_task_menu(env):
    c, s, n, a = setup_voice(env)
    q = utter(c, a, n, transcript="帮我问问模力社区还能停车吗？").json()
    status = utter(c, a, n, transcript="刚才问的有回应吗").json()
    assert "等待" in status["agent_response"]["text"], status
    new = utter(c, a, n, transcript="帮我问问人工智能岛还有车位吗？").json()
    assert new["status"] == "clarifying" and new["agent_response"]["kind"] == "replace_question", (
        new
    )
    replacement = utter(c, a, n, transcript="好").json()
    assert (
        replacement["status"] == "published" and replacement["target_place"]["id"] == "B0FFJEOSPR"
    ), replacement
    assert c.get("/v1/questions/" + q["post_id"], headers=auth()).json()["status"] == "cancelled"
    cancelled = utter(c, a, n, transcript="结束这次问题").json()
    assert cancelled["status"] == "responded", cancelled
    assert (
        c.get(f"/v1/exchange/sessions/{a['id']}", headers=auth()).json()["session"]["state"]
        == "active"
    )


def test_remote_switch_only_moves_self_and_real_location_is_requested(env):
    c, s, n, a = setup_voice(env)
    bob = c.get("/v1/me", headers=auth("bob")).json()["profile"]
    switched = utter(c, a, n, transcript="切到人工智能岛").json()
    assert switched["voice_action"] == {
        "type": "location",
        "place_id": "B0FFJEOSPR",
        "use_real_location": False,
    }, switched
    assert switched["profile"]["presence"]["source"] == "remote_place"
    assert c.get("/v1/me", headers=auth("bob")).json()["profile"] == bob
    real = utter(c, switched["session"], n, transcript="换回手机真实位置").json()
    assert real["voice_action"]["use_real_location"]
    assert real["profile"]["presence"]["source"] == "unavailable"


def test_unknown_remote_location_clarifies_and_next_place_completes(env):
    c, s, n, a = setup_voice(env)
    from test_agent_network import PARK, Maps

    s.geo = Maps(n)
    first = utter(c, a, n, transcript="切换到某个停车场位置").json()
    assert first["status"] == "clarifying", first
    # A provider returned place is accepted once the spoken clarification identifies it.
    s.geo.search = lambda query: [{**PARK, "id": "B0TESTPARK"}]
    second = utter(c, a, n, transcript="上海世纪公园").json()
    assert second["voice_action"]["place_id"] == "B0TESTPARK", second


def test_shared_samples_are_server_only_explicit_and_replayable(env):
    c, s, n, a = setup_voice(env)
    assert import_demo(s, shared=True) == 36
    assert not c.get(f"/v1/exchange/sessions/{a['id']}/inbox", headers=auth()).json()["items"]
    sample = utter(c, a, n, transcript="听听这里的测试声音").json()
    assert sample["voice_action"]["type"] == "play_audio", sample
    assert "合成测试" in sample["voice_action"]["preface"]
    item = sample["voice_action"]["items"][0]
    assert item["synthetic"] and item["duration_seconds"] > 0
    assert c.get(item["audio_url"], headers=auth()).status_code == 200
    heard(c, a, item["post_id"])
    replay = utter(c, a, n, transcript="刚才那条再放一遍").json()
    assert replay["voice_action"]["items"][0]["post_id"] == item["post_id"]
    assert utter(c, a, n, transcript="东门还有位置").json()["action"] == "private_chat"


def test_long_complete_original_accepts_thirty_seconds_and_rejects_overflow(env):
    c, s, n, a = setup_voice(env)
    from conftest import draft

    r = c.post(
        f"/v1/exchange/sessions/{a['id']}/utterances",
        headers=auth(),
        data={
            "metadata": json.dumps(
                {
                    "client_request_id": str(uuid.uuid4()),
                    "expected_session_revision": a["revision"],
                    "transcript": "帮我问问模力社区停车？",
                    "recorded_at": stamp(n[0]),
                }
            )
        },
        files={"audio": ("voice.wav", wav(30), "audio/wav")},
    )
    assert r.status_code == 200 and r.json()["status"] == "published", r.text
    assert c.get("/v1/audio/" + r.json()["post_id"], headers=auth()).content == wav(30)
    assert draft(c, audio=wav(30.01)).status_code == 415
    assert draft(c, audio=b"0" * (1024 * 1024 + 1)).status_code == 413


def test_semantic_classifier_is_structured_and_fails_closed(env):
    _, _, _, settings = env
    settings.bamboo_api_key = "isolated-test-key"
    captured = []

    def provider(request):
        captured.append(json.loads(request.content))
        return httpx.Response(
            200,
            json={
                "answers": {
                    "voice_intent": {
                        "type": "choice",
                        "choice": "reply",
                        "confidence": 0.96,
                        "none_probability": 0.01,
                    }
                }
            },
        )

    decider = VoiceIntentDecider(settings, httpx.MockTransport(provider))
    assert (
        asyncio.run(decider.route({"utterance": "那边现在可以进", "heard_context": "还能停车吗"}))
        == "reply"
    )
    assert "voice_intent" in captured[0]["questions"]
    assert "exchange" not in captured[0]["questions"]
    bad = VoiceIntentDecider(
        settings,
        httpx.MockTransport(
            lambda r: httpx.Response(
                200,
                json={
                    "answers": {
                        "voice_intent": {
                            "type": "choice",
                            "choice": "reply",
                            "confidence": True,
                            "none_probability": 0,
                        }
                    }
                },
            )
        ),
    )
    assert asyncio.run(bad.route({})) == "defer"


@pytest.mark.parametrize(
    "text", ["不要帮我问问模力社区停车", "别把刚才的话分享给别人", "只是私聊，不要转告车友"]
)
def test_negated_public_requests_stay_private(env, text):
    c, service, now, session = setup_voice(env)
    result = utter(c, session, now, transcript=text).json()
    assert result["status"] != "published", result
    with service.scope() as db:
        assert not db.execute("SELECT id FROM posts").fetchone()


def test_start_preserves_quiet_but_allows_a_paused_session(env):
    c, service, now, session = setup_voice(env)
    with service.scope() as db:
        db.execute("UPDATE profiles SET mode='quiet' WHERE user_id='alice'")
    profile = c.get("/v1/me", headers=auth()).json()["profile"]
    response = c.post(
        "/v1/agent/sessions",
        headers=auth(),
        json={"client_request_id": str(uuid.uuid4()), "expected_revision": profile["revision"]},
    )
    assert response.status_code == 200, response.text
    assert response.json()["session"]["state"] == "paused"
    assert response.json()["session"]["quiet"]
    assert c.get("/v1/me", headers=auth()).json()["profile"]["mode"] == "quiet"


def test_cancel_speech_revokes_an_inflight_query_before_publication(env):
    c, service, now, session = setup_voice(env)
    from test_agent_network import Maps

    service.geo = Maps(now)
    cancelled = []

    def during_search():
        cancelled.append(utter(c, session, now, transcript="不用等了").json())

    service.geo.after_search = during_search
    result = utter(c, session, now, transcript="帮我问问上海世纪公园还能停车吗？").json()
    assert cancelled[0]["status"] == "responded", cancelled
    assert result["status"] == "deferred" and result["reason"] == "TASK_CLOSED", result
    with service.scope() as db:
        assert not db.execute("SELECT id FROM posts").fetchone()


def test_revoking_continuous_scope_discards_late_scene_decision(env):
    c, service, now, session = setup_voice(env)
    utter(c, session, now, transcript="这一路观察都帮我分享")

    def revoke():
        with service.scope() as db:
            current = service.exchange_row(db, "alice", session["id"])
            policy = service.voice_policy(db, current)
            policy["continuous_share"] = False
            service.save_voice_policy(db, session["id"], policy)

    service.exchange_decider.before_return = revoke
    result = utter(c, session, now, transcript="东门路口排队了").json()
    assert result["status"] == "deferred" and result["reason"] == "PUBLIC_INTENT_REQUIRED", result
    with service.scope() as db:
        assert not db.execute("SELECT id FROM posts").fetchone()


def test_validated_model_actions_reach_the_real_executor(env):
    c, service, now, session = setup_voice(env)
    service.voice_intent.force = "ask"
    spoken = "替我打听一下模力社区可不可以停"
    requested = utter(c, session, now, transcript=spoken).json()
    assert requested["status"] == "published" and requested["action"] == "question", requested
    with service.scope() as db:
        assert service.post(db, requested["post_id"])["transcript"] == spoken
    service.voice_intent.force = "query"
    private = utter(c, session, now, transcript="模力社区给我来点相关资料").json()
    assert private["status"] == "responded" and private["action"] == "agent", private
    with service.scope() as db:
        assert db.execute("SELECT count(*) FROM posts").fetchone()[0] == 1
    service.voice_intent.force = "chat"
    chatting = utter(c, session, now, transcript="给我牵个愿意说话的同路人").json()
    assert (
        chatting["status"] == "clarifying" and chatting["agent_response"]["kind"] == "chat_offer"
    ), chatting


@pytest.mark.parametrize(
    "text",
    [
        "我想了解怎么分享这个功能",
        "如果我说问题解决了，你会做什么",
        "怎么取消刚才的提问",
        "我刚跟现场的人聊过",
    ],
)
def test_discussing_commands_does_not_publish_or_cancel(env, text):
    c, service, now, session = setup_voice(env)
    question = utter(c, session, now, transcript="帮我问问模力社区有车位吗？").json()
    result = utter(c, session, now, transcript=text).json()
    assert result["status"] != "published", result
    with service.scope() as db:
        assert service.post(db, question["post_id"])["question_status"] == "waiting"
        assert db.execute("SELECT count(*) FROM posts").fetchone()[0] == 1


def test_mentioning_reply_cannot_override_private_model_decision(env):
    c, service, now, a = setup_voice(env)
    _, _, _, b = setup_voice(env, "bob")
    question = utter(c, a, now, transcript="帮我问问模力社区有车位吗？").json()
    heard(c, b, question["post_id"], "bob")
    service.voice_intent.force = "private_chat"
    result = utter(c, b, now, "bob", transcript="我还没想好怎么回复他").json()
    assert result["action"] == "private_chat", result
    with service.scope() as db:
        assert service.post(db, question["post_id"])["question_status"] == "waiting"


@pytest.mark.parametrize("text", ["不可以问我", "不可问我", "我今天不能回答别人的问题"])
def test_negative_participation_never_becomes_permission(env, text):
    c, service, now, session = setup_voice(env)
    service.voice_intent.force = "participation"
    result = utter(c, session, now, transcript=text).json()
    assert result["status"] in ("responded", "clarifying"), result
    with service.scope() as db:
        assert not service.voice_invite_allowed(db, service.profile_row(db, "alice"))


def test_explicit_sharing_without_any_location_is_not_published(env):
    c, service, now, _ = env
    service.exchange_decider = Decisions()
    service.voice_intent = Routes()
    profile = c.get("/v1/me", headers=auth()).json()["profile"]
    session = c.post(
        "/v1/agent/sessions",
        headers=auth(),
        json={
            "client_request_id": str(uuid.uuid4()),
            "expected_revision": profile["revision"],
        },
    ).json()["session"]
    result = utter(c, session, now, transcript="帮我分享给后面的车友，这里已经开始放行了").json()
    assert result["status"] == "deferred" and result["reason"] == "PRESENCE_REQUIRED", result
    with service.scope() as db:
        assert not db.execute("SELECT 1 FROM posts").fetchone()


def test_switch_selects_destination_after_from_phrase_and_respects_city(env):
    c, service, now, session = setup_voice(env)
    result = utter(c, session, now, transcript="从模力社区切换到七牛").json()
    assert result["voice_action"]["place_id"] == ISLAND, result
    queries = []

    def search(query):
        queries.append(query)
        return [
            {
                "id": "B0CHENGDU1",
                "label": "成都金科路",
                "latitude": 30.7,
                "longitude": 104.0,
                "kind": "poi",
            }
        ]

    service.geo.search = search
    result = utter(c, result["session"], now, transcript="切到成都金科路").json()
    assert queries == ["成都金科路"]
    assert result["voice_action"]["place_id"] == "B0CHENGDU1", result


def test_synthetic_playback_ends_old_implicit_reply_but_remains_replayable(env):
    c, service, now, a = setup_voice(env)
    _, _, _, b = setup_voice(env, "bob")
    import_demo(service, shared=True)
    question = utter(c, a, now, transcript="帮我问问模力社区有车位吗？").json()
    heard(c, b, question["post_id"], "bob")
    sample = utter(c, b, now, "bob", transcript="听听这里的测试声音").json()
    assert sample["voice_action"]["type"] == "play_audio"
    sample_id = sample["voice_action"]["items"][0]["post_id"]
    heard(c, b, sample_id, "bob")
    session = c.get(f"/v1/exchange/sessions/{b['id']}", headers=auth("bob")).json()["session"]
    assert session["context_post_id"] is None
    assert session["voice_policy"]["heard_post_id"] is None
    assert session["voice_policy"]["last_audio_post_id"] == sample_id
    result = utter(c, session, now, "bob", transcript="这条声音挺有意思").json()
    assert result["action"] == "private_chat", result
    replay = utter(c, session, now, "bob", transcript="再放一遍").json()
    assert replay["voice_action"]["items"][0]["post_id"] == sample_id
    with service.scope() as db:
        assert service.post(db, question["post_id"])["question_status"] == "waiting"


def test_status_then_explicit_followup_keeps_verified_bookmark(env):
    c, service, now, a = setup_voice(env)
    _, _, _, b = setup_voice(env, "bob")
    q = utter(c, a, now, transcript="帮我问问模力社区现在有车位吗？").json()
    heard(c, b, q["post_id"], "bob")
    answer = utter(c, b, now, "bob", transcript="东门还有三个位置").json()
    heard(c, a, answer["post_id"])
    status = utter(c, a, now, transcript="刚才问的有回应吗").json()
    assert status["status"] == "responded"
    reply = utter(c, a, now, transcript="回复他，从哪个门进？").json()
    assert reply["status"] == "published" and reply["action"] == "reply", reply
    assert reply["root_id"] == q["post_id"]


def test_waiting_answer_survives_private_context_and_timeout_is_observable(env):
    c, service, now, a = setup_voice(env)
    _, _, _, b = setup_voice(env, "bob")
    q = utter(c, a, now, transcript="帮我问问模力社区现在有车位吗？").json()
    import_demo(service, shared=True)
    sample = utter(c, a, now, transcript="听听这里的测试声音").json()
    heard(c, a, sample["voice_action"]["items"][0]["post_id"])
    heard(c, b, q["post_id"], "bob")
    answer = utter(c, b, now, "bob", transcript="东门还有三个位置").json()
    feed = c.get(f"/v1/exchange/sessions/{a['id']}/inbox", headers=auth()).json()["items"]
    assert any(d["payload"]["post_id"] == answer["post_id"] for d in feed)
    service.voice_intent.force = "query"
    waiting = utter(c, a, now, transcript="帮我问问模力社区停车入口还排队吗？").json()
    assert waiting["status"] == "published", waiting
    now[0] += 121
    view = c.get(f"/v1/exchange/sessions/{a['id']}", headers=auth()).json()["session"]
    assert any(
        t["question_id"] == waiting["post_id"] and t["status"] == "timed_out"
        for t in view["task_updates"]
    )
