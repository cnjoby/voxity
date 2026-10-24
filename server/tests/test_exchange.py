import asyncio
import json
import uuid
from datetime import UTC, datetime

import httpx
from conftest import auth, wav

from app.exchange import ExchangeDecider


def stamp(now):
    return datetime.fromtimestamp(now, UTC).isoformat().replace("+00:00", "Z")


class Decisions:
    def __init__(self, action="scene"):
        self.action, self.calls, self.listens = action, 0, 0
        self.before_return = None

    async def decide(self, state, listening=False):
        if listening:
            self.listens += 1
            if self.before_return:
                self.before_return()
            return "play"
        self.calls += 1
        if self.before_return:
            self.before_return()
        return self.action


def prepare(env):
    c, service, now, _ = env
    service.exchange_decider = Decisions()
    with service.scope() as db:
        db.execute(
            "INSERT INTO places VALUES (?,?,?,?,?,?)",
            ("road", "金科路中环路口", 31.17, 121.6, "poi", now[0]),
        )
        for u in ["alice", "bob"]:
            db.execute(
                "UPDATE profiles SET place_id='road',direction='unknown',source='android',"
                "latitude=31.17,longitude=121.6,sampled_at=?,accuracy_m=5,updated_at=? WHERE user_id=?",
                (now[0], now[0], u),
            )
    return c, service, now


def start(c, user="alice", **changes):
    profile = c.get("/v1/me", headers=auth(user)).json()["profile"]
    return c.post(
        "/v1/exchange/sessions",
        headers=auth(user),
        json={
            "client_request_id": str(uuid.uuid4()),
            "expected_revision": profile["revision"],
            "place_id": "road",
            "auto_publish": True,
            "auto_play": True,
            **changes,
        },
    )


def utter(c, session, now, user="alice", request_id=None, **changes):
    meta = {
        "client_request_id": request_id or str(uuid.uuid4()),
        "expected_session_revision": session["revision"],
        "transcript": "金科路中环路下面堵死了。",
        "recorded_at": stamp(now[0]),
        **changes,
    }
    return c.post(
        f"/v1/exchange/sessions/{session['id']}/utterances",
        headers=auth(user),
        data={"metadata": json.dumps(meta)},
        files={"audio": ("voice.wav", wav(), "audio/wav")},
    )


def test_auto_scene_related_listen_and_real_scene_reply(env):
    c, service, now = prepare(env)
    a, b = start(c).json()["session"], start(c, "bob").json()["session"]
    r = utter(c, a, now)
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "published" and r.json()["action"] == "scene"
    root = r.json()["post_id"]
    voices = c.get("/v1/voices?place_id=road", headers=auth("bob")).json()["items"]
    assert root in {v["post_id"] for v in voices}
    feed = c.get(f"/v1/exchange/sessions/{b['id']}/inbox", headers=auth("bob")).json()
    d = next(d for d in feed["items"] if d["payload"]["post_id"] == root)
    assert d["actionable"] and d["payload"]["kind"] == "scene"
    assert c.get(d["payload"]["audio_url"], headers=auth("bob")).content == wav()
    for _ in range(2):
        c.get(f"/v1/exchange/sessions/{b['id']}/inbox", headers=auth("bob"))
    assert service.exchange_decider.listens == 1
    assert (
        c.put(
            f"/v1/exchange/sessions/{b['id']}/context",
            headers=auth("bob"),
            json={"expected_session_revision": b["revision"], "post_id": root},
        ).status_code
        == 200
    )
    service.exchange_decider.action = "reply"
    r = utter(c, b, now, "bob", transcript="我刚过那儿，右边已经能走了。")
    assert r.status_code == 200 and r.json()["status"] == "published", r.text
    assert r.json()["root_id"] == root
    aid = r.json()["post_id"]
    feed = c.get(f"/v1/exchange/sessions/{a['id']}/inbox", headers=auth()).json()
    d = next(d for d in feed["items"] if d["payload"]["post_id"] == aid)
    assert d["actionable"] and d["payload"]["reply_to"] == root
    conversation = c.get(f"/v1/conversations/{root}", headers=auth()).json()
    assert conversation["root_kind"] == "scene" and conversation["can_reply"]
    assert len(conversation["items"]) == 2
    ack = {"event": "played", "request_id": d["request_id"]}
    assert c.post(f"/v1/inbox/{d['id']}/ack", headers=auth(), json=ack).status_code == 200
    assert all(
        x["id"] != d["id"]
        for x in c.get(f"/v1/exchange/sessions/{a['id']}/inbox", headers=auth()).json()["items"]
    )


def test_session_authority_revocation_expiry_and_idempotency(env):
    c, service, now = prepare(env)
    a = start(c).json()["session"]
    assert c.get(f"/v1/exchange/sessions/{a['id']}", headers=auth("bob")).status_code == 404
    assert utter(c, a, now, "bob").status_code == 404
    rid = str(uuid.uuid4())
    first = utter(c, a, now, request_id=rid)
    again = utter(c, a, now, request_id=rid)
    assert first.json()["post_id"] == again.json()["post_id"]
    assert service.exchange_decider.calls == 1
    assert utter(c, a, now, request_id=rid, transcript="换一句").status_code == 409

    def pause_during_decision():
        from app.models import ExchangeChange

        with service.scope() as db:
            service.change_exchange(
                db, "alice", a["id"], ExchangeChange(expected_session_revision=1, state="paused")
            )

    service.exchange_decider.before_return = pause_during_decision
    r = utter(c, a, now)
    assert r.json()["status"] == "deferred" and r.json()["reason"] == "SESSION_INACTIVE"
    assert utter(c, a, now).status_code == 410
    b = start(c, "bob", auto_publish=False).json()["session"]
    assert utter(c, b, now, "bob").status_code == 403
    now[0] += service.settings.exchange_session_ttl + 1
    assert (
        c.get(f"/v1/exchange/sessions/{b['id']}", headers=auth("bob")).json()["session"]["state"]
        == "ended"
    )


def test_scope_time_model_uncertainty_and_no_relation_forgery(env):
    c, service, now = prepare(env)
    a = start(c).json()["session"]
    for action in ["defer", "ignore", "answer", "reply", "invented", None]:
        service.exchange_decider.action = action
        r = utter(c, a, now)
        assert r.status_code == 200 and r.json()["status"] != "published", r.text
    with service.scope() as db:
        assert db.execute("SELECT COUNT(*) FROM posts").fetchone()[0] == 0
    assert utter(c, a, now, recorded_at=stamp(now[0] - 61)).status_code == 410
    with service.scope() as db:
        db.execute("UPDATE profiles SET sampled_at=? WHERE user_id='alice'", (now[0] - 100,))
    assert utter(c, a, now).status_code == 409


def test_late_context_change_and_quiet_never_publish(env):
    c, service, now = prepare(env)
    a = start(c).json()["session"]

    def quiet_during_decision():
        from app.models import Context

        with service.scope() as db:
            service.context(
                db,
                "alice",
                Context(expected_revision=1, mode="quiet", assist_enabled=True, focus="路况"),
            )

    service.exchange_decider.before_return = quiet_during_decision
    result = utter(c, a, now)
    assert result.json()["status"] == "deferred"
    with service.scope() as db:
        assert db.execute("SELECT COUNT(*) FROM posts").fetchone()[0] == 0


def test_duplicate_start_does_not_reopen_revoked_scope(env):
    c, _, _ = prepare(env)
    request = {
        "client_request_id": str(uuid.uuid4()),
        "expected_revision": 1,
        "place_id": "road",
        "auto_publish": True,
        "auto_play": True,
    }
    first = c.post("/v1/exchange/sessions", headers=auth(), json=request).json()["session"]
    c.put(
        f"/v1/exchange/sessions/{first['id']}",
        headers=auth(),
        json={"expected_session_revision": 1, "state": "ended"},
    )
    assert (
        c.post("/v1/exchange/sessions", headers=auth(), json=request).json()["session"]["state"]
        == "ended"
    )
    request["auto_publish"] = False
    assert c.post("/v1/exchange/sessions", headers=auth(), json=request).status_code == 409


def test_decision_parser_abstains_on_bad_or_uncertain_output(env):
    _, _, _, settings = env
    settings.bamboo_api_key = "isolated-test-key"
    replies = [
        None,
        {},
        {"type": "choice", "choice": "scene", "confidence": 0.84, "none_probability": 0},
        {"type": "choice", "choice": "scene", "confidence": 0.99, "none_probability": 0.3},
        {"type": "choice", "choice": "scene", "confidence": True, "none_probability": 0},
    ]
    for answer in replies:
        transport = httpx.MockTransport(
            lambda request, answer=answer: httpx.Response(
                200, json={"answers": {"exchange": answer}}
            )
        )
        assert (
            asyncio.run(ExchangeDecider(settings, transport).decide({"utterance": "test"}))
            == "defer"
        )
    payload = ExchangeDecider(settings).payload({"utterance": "test"})
    assert set(payload["questions"]["exchange"]["criteria"]) == {
        "scene",
        "question",
        "answer",
        "reply",
        "ignore",
        "defer",
    }


def test_explicit_negative_is_never_overruled_by_model(env):
    c, service, now = prepare(env)
    a = start(c).json()["session"]
    for transcript in ["下面堵死了，别发出去", "不要公开，我只是聊天", "这段不公开"]:
        result = utter(c, a, now, transcript=transcript)
        assert result.json()["status"] == "ignored"
    assert service.exchange_decider.calls == 0
    with service.scope() as db:
        assert db.execute("SELECT COUNT(*) FROM posts").fetchone()[0] == 0


def test_listening_late_result_and_departure_cannot_deliver(env):
    c, service, now = prepare(env)
    a, b = start(c).json()["session"], start(c, "bob").json()["session"]
    root = utter(c, a, now).json()["post_id"]

    def leave_during_decision():
        with service.scope() as db:
            db.execute("UPDATE profiles SET latitude=32 WHERE user_id='bob'")

    service.exchange_decider.before_return = leave_during_decision
    feed = c.get(f"/v1/exchange/sessions/{b['id']}/inbox", headers=auth("bob"))
    assert feed.status_code == 200 and feed.json()["items"] == []
    with service.scope() as db:
        assert not db.execute(
            "SELECT 1 FROM deliveries WHERE post_id=? AND type='audio'", (root,)
        ).fetchone()


def test_withdrawn_scene_closes_original_and_its_direct_replies(env):
    c, service, now = prepare(env)
    a, b = start(c).json()["session"], start(c, "bob").json()["session"]
    root = utter(c, a, now).json()["post_id"]
    c.put(
        f"/v1/exchange/sessions/{b['id']}/context",
        headers=auth("bob"),
        json={"expected_session_revision": b["revision"], "post_id": root},
    )
    service.exchange_decider.action = "reply"
    reply = utter(c, b, now, "bob").json()["post_id"]
    assert c.post(f"/v1/voices/{root}/withdraw", headers=auth(), json={}).status_code == 200
    assert c.get(f"/v1/audio/{reply}", headers=auth()).status_code in (403, 404, 410)
    assert c.get(f"/v1/exchange/sessions/{a['id']}/inbox", headers=auth()).json()["items"] == []
    assert utter(c, b, now, "bob").json()["status"] != "published"


def test_natural_question_keeps_authentic_answer_relationship(env):
    c, service, now = prepare(env)
    a, b = start(c).json()["session"], start(c, "bob").json()["session"]
    service.exchange_decider.action = "question"
    q = utter(c, a, now, transcript="这路口现在还能走吗？").json()
    assert q["status"] == "published", q
    service.exchange_decider.action = "answer"
    result = utter(c, b, now, "bob", transcript="我就在下面，现在走不动。")
    assert result.status_code == 200 and result.json()["status"] == "published", result.text
    assert result.json()["root_id"] == q["post_id"]
    feed = c.get(f"/v1/exchange/sessions/{a['id']}/inbox", headers=auth()).json()["items"]
    assert len(feed) == 1 and feed[0]["payload"]["kind"] == "answer"
    assert feed[0]["question_id"] == q["post_id"]


def test_listening_threshold_is_separate_from_publication(env):
    _, _, _, settings = env
    settings.bamboo_api_key = "isolated-test-key"

    def response(request):
        listening = "play" in json.loads(request.content)["questions"]["exchange"]["criteria"]
        return httpx.Response(
            200,
            json={
                "answers": {
                    "exchange": {
                        "type": "choice",
                        "choice": "play" if listening else "scene",
                        "confidence": 0.69,
                        "none_probability": 0.05,
                    }
                }
            },
        )

    decider = ExchangeDecider(settings, httpx.MockTransport(response))
    assert asyncio.run(decider.decide({}, listening=True)) == "play"
    assert asyncio.run(decider.decide({})) == "defer"


def test_missing_model_does_not_advertise_ready_session(env):
    c, service, _ = prepare(env)
    service.exchange_decider = ExchangeDecider(service.settings)
    result = start(c)
    assert result.status_code == 503
    assert result.json()["error"]["code"] == "DECISION_NOT_CONFIGURED"


def test_remote_quiet_is_visible_and_late_listen_cannot_queue(env):
    from app.models import Context

    c, service, now = prepare(env)
    a, b = start(c).json()["session"], start(c, "bob").json()["session"]
    root = utter(c, a, now).json()["post_id"]

    def quiet_during_listen():
        with service.scope() as db:
            service.context(
                db,
                "bob",
                Context(expected_revision=1, mode="quiet", assist_enabled=True, focus="路况"),
            )

    service.exchange_decider.before_return = quiet_during_listen
    response = c.get(f"/v1/exchange/sessions/{b['id']}/inbox", headers=auth("bob"))
    assert response.status_code == 409
    assert c.get(f"/v1/exchange/sessions/{b['id']}", headers=auth("bob")).json()["session"]["quiet"]
    with service.scope() as db:
        assert not db.execute(
            "SELECT 1 FROM deliveries WHERE type='audio' AND post_id=?", (root,)
        ).fetchone()


def test_existing_pending_audio_is_semantically_filtered_without_reset(env):
    c, service, now = prepare(env)
    a, b = start(c).json()["session"], start(c, "bob").json()["session"]
    root = utter(c, a, now).json()["post_id"]
    with service.scope() as db:
        original = service.create_delivery(db, "bob", root, root, "audio", now[0] + 200)
        did, request = original["id"], original["request_id"]
    feed = c.get(f"/v1/exchange/sessions/{b['id']}/inbox", headers=auth("bob")).json()["items"]
    assert len(feed) == 1 and feed[0]["id"] == did and feed[0]["request_id"] == request
    assert service.exchange_decider.listens == 1
    c.post(
        f"/v1/inbox/{did}/ack", headers=auth("bob"), json={"request_id": request, "event": "played"}
    )
    assert (
        c.get(f"/v1/exchange/sessions/{b['id']}/inbox", headers=auth("bob")).json()["items"] == []
    )
    with service.scope() as db:
        assert (
            db.execute(
                "SELECT COUNT(*) FROM deliveries WHERE recipient_id='bob' AND post_id=? AND type='audio'",
                (root,),
            ).fetchone()[0]
            == 1
        )


def test_current_answer_root_accepts_followups_without_old_context(env):
    c, service, now = prepare(env)
    a, b = start(c).json()["session"], start(c, "bob").json()["session"]
    service.exchange_decider.action = "question"
    question = utter(c, a, now).json()["post_id"]
    # The question is heard as original audio, never an invitation control voice.
    heard = c.get(f"/v1/exchange/sessions/{b['id']}/inbox", headers=auth("bob")).json()["items"]
    assert len(heard) == 1 and heard[0]["type"] == "audio"
    assert heard[0]["payload"]["kind"] == "question"
    assert c.get(heard[0]["payload"]["audio_url"], headers=auth("bob")).content == wav()
    service.exchange_decider.action = "answer"
    answer = utter(c, b, now, "bob").json()["post_id"]
    c.put(
        f"/v1/exchange/sessions/{a['id']}/context",
        headers=auth(),
        json={"expected_session_revision": 1, "post_id": answer},
    )
    service.exchange_decider.action = "reply"
    reply = utter(c, a, now).json()
    assert reply["status"] == "published" and reply["root_id"] == question
    feed = c.get(f"/v1/exchange/sessions/{b['id']}/inbox", headers=auth("bob")).json()["items"]
    assert [d["payload"]["post_id"] for d in feed] == [reply["post_id"]]


def test_new_context_holds_old_direct_replies_and_scene_approvals(env):
    c, service, now = prepare(env)
    a, b = start(c).json()["session"], start(c, "bob").json()["session"]
    root = utter(c, a, now).json()["post_id"]
    queued = c.get(f"/v1/exchange/sessions/{b['id']}/inbox", headers=auth("bob")).json()["items"][0]
    # Bob moves to a new authored task before playing this scene.
    new_root = utter(c, b, now, "bob").json()["post_id"]
    assert (
        c.get(f"/v1/inbox/{queued['id']}", headers=auth("bob")).json()["delivery"]["reason_code"]
        == "EXCHANGE_CONTEXT_CHANGED"
    )
    assert (
        c.get(f"/v1/exchange/sessions/{b['id']}/inbox", headers=auth("bob")).json()["items"] == []
    )
    c.put(
        f"/v1/exchange/sessions/{a['id']}/context",
        headers=auth(),
        json={"expected_session_revision": 1, "post_id": new_root},
    )
    service.exchange_decider.action = "reply"
    old_reply = utter(c, a, now).json()["post_id"]
    service.exchange_decider.action = "scene"
    utter(c, b, now, "bob", transcript="换个话题，这里下雨了。")
    items = c.get(f"/v1/exchange/sessions/{b['id']}/inbox", headers=auth("bob")).json()["items"]
    assert all(d["payload"]["post_id"] not in (root, old_reply) for d in items)
    with service.scope() as db:
        assert (
            db.execute(
                "SELECT state FROM deliveries WHERE post_id=? AND recipient_id='bob'", (old_reply,)
            ).fetchone()[0]
            == "pending"
        )


def test_direct_reply_is_returned_without_calling_new_scene_model(env):
    c, service, now = prepare(env)
    a, b = start(c).json()["session"], start(c, "bob").json()["session"]
    root = utter(c, a, now).json()["post_id"]
    c.put(
        f"/v1/exchange/sessions/{b['id']}/context",
        headers=auth("bob"),
        json={"expected_session_revision": 1, "post_id": root},
    )
    # Another live candidate is available, but a direct reply must not wait for it.
    utter(c, b, now, "bob", transcript="另一条独立现场情况")
    c.put(
        f"/v1/exchange/sessions/{b['id']}/context",
        headers=auth("bob"),
        json={"expected_session_revision": 1, "post_id": root},
    )
    service.exchange_decider.action = "reply"
    reply = utter(c, b, now, "bob").json()["post_id"]
    before = service.exchange_decider.listens
    feed = c.get(f"/v1/exchange/sessions/{a['id']}/inbox", headers=auth()).json()["items"]
    assert feed[0]["payload"]["post_id"] == reply
    assert service.exchange_decider.listens == before


def test_skipped_first_ten_candidates_cannot_starve_eligible_scene(env):
    c, service, now = prepare(env)
    a, b = start(c).json()["session"], start(c, "bob").json()["session"]
    eligible = utter(c, a, now).json()["post_id"]
    for index in range(10):
        pid = utter(c, a, now, transcript=f"已失效的情况 {index}").json()["post_id"]
        with service.scope() as db:
            db.execute("UPDATE posts SET created_at=? WHERE id=?", (now[0] + index + 1, pid))
        c.post(f"/v1/voices/{pid}/withdraw", headers=auth(), json={})
    feed = c.get(f"/v1/exchange/sessions/{b['id']}/inbox", headers=auth("bob")).json()["items"]
    assert [d["payload"]["post_id"] for d in feed] == [eligible]
    assert service.exchange_decider.listens == 1


def test_deferred_client_audio_does_not_block_new_candidate_scheduling(env):
    c, _, now = prepare(env)
    a, b = start(c).json()["session"], start(c, "bob").json()["session"]
    first = utter(c, a, now).json()["post_id"]
    d = c.get(f"/v1/exchange/sessions/{b['id']}/inbox", headers=auth("bob")).json()["items"][0]
    second = utter(c, a, now, transcript="路口又开始动了").json()["post_id"]
    feed = c.get(
        f"/v1/exchange/sessions/{b['id']}/inbox", params={"exclude": d["id"]}, headers=auth("bob")
    ).json()["items"]
    assert [x["payload"]["post_id"] for x in feed] == [second]
    assert first != second
    assert (
        c.get(f"/v1/inbox/{d['id']}", headers=auth("bob")).json()["delivery"]["state"] == "pending"
    )


def test_heard_question_selects_its_real_invitation_among_multiple(env):
    from app.models import QuestionDraft
    from app.storage import digest

    c, service, now = prepare(env)
    a, b = start(c).json()["session"], start(c, "bob").json()["session"]
    service.exchange_decider.action = "question"
    first = utter(c, a, now, transcript="这路口能走吗").json()["post_id"]
    with service.scope() as db:
        db.execute(
            "INSERT INTO users VALUES (?,?,?,?)", ("carol", digest("c" * 40), "carol-device", "")
        )
        profile = dict(service.profile_row(db, "alice"))
        profile["user_id"] = "carol"
        db.execute(
            "INSERT INTO profiles ("
            + ",".join(profile)
            + ") VALUES ("
            + ",".join("?" for _ in profile)
            + ")",
            tuple(profile.values()),
        )
        post, _ = service.draft(
            db,
            "carol",
            QuestionDraft(
                client_request_id=uuid.uuid4(),
                kind="question",
                transcript="另一个问题",
                target_place_id="road",
            ),
            wav(),
        )
        service.confirm(db, "carol", post["post_id"])
    c.put(
        f"/v1/exchange/sessions/{b['id']}/context",
        headers=auth("bob"),
        json={"expected_session_revision": 1, "post_id": first},
    )
    service.exchange_decider.action = "answer"
    result = utter(c, b, now, "bob", transcript="你刚才问的这边还堵着").json()
    assert result["status"] == "published" and result["root_id"] == first
