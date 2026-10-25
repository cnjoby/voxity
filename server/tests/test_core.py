import uuid
from concurrent.futures import ThreadPoolExecutor

import pytest
from conftest import answer, auth, draft, invitation, publish, set_context, wav
from fastapi.testclient import TestClient

from app.main import create_app


def test_s1_identity_seed_and_optimistic_config(env):
    c, service, now, _ = env
    assert c.get("/healthz").json() == {"status": "ok"}
    assert c.get("/v1/me").status_code == 401
    assert c.get("/v1/me", headers={"Authorization": "Bearer wrong"}).status_code == 401
    assert c.get("/v1/me", headers=auth()).json()["profile"]["user_id"] == "alice"
    assert (
        c.get("/v1/me", headers=auth("bob")).json()["profile"]["presence"]["place_id"]
        == "zhonghuan_jinke"
    )
    assert set_context(c, mode="quiet").json()["profile"]["revision"] == 2
    service.seed()
    assert c.get("/v1/me", headers=auth("bob")).json()["profile"]["mode"] == "quiet"
    response = set_context(c, expected_revision=1)
    assert response.status_code == 409
    now[0] += 10
    assert set_context(c).json()["profile"]["revision"] == 2


def test_three_rounds_http_core_and_original_audio(env):
    c, _, _, _ = env
    for _ in range(3):
        qid = publish(c)
        d = invitation(c)
        assert d["actionable"] and d["question_id"] == qid
        for _ in range(10):
            assert invitation(c)["id"] == d["id"]
        ack = {"event": "alerted", "request_id": d["request_id"]}
        assert c.post(f"/v1/inbox/{d['id']}/ack", headers=auth("bob"), json=ack).status_code == 200
        assert c.post(f"/v1/inbox/{d['id']}/ack", headers=auth("bob"), json=ack).status_code == 200
        still = c.get("/v1/inbox", headers=auth("bob")).json()["items"]
        assert len(still) == 1 and still[0]["id"] == d["id"] and still[0]["state"] == "prompted"
        aid = answer(c, qid, d)
        result = c.get(f"/v1/questions/{qid}", headers=auth()).json()
        assert result["status"] == "answered" and result["answer"]["post_id"] == aid
        item = c.get("/v1/inbox", headers=auth()).json()["items"][0]
        assert item["payload"]["post_id"] == aid
        assert c.get(item["payload"]["audio_url"], headers=auth()).content == wav()
        ack = {"event": "played", "request_id": item["request_id"]}
        for _ in range(2):
            assert (
                c.post(f"/v1/inbox/{item['id']}/ack", headers=auth(), json=ack).status_code == 200
            )
        assert c.get("/v1/inbox", headers=auth()).json()["items"] == []


def test_draft_privacy_idempotency_and_format(env):
    c, _, _, _ = env
    meta = {
        "client_request_id": str(uuid.uuid4()),
        "kind": "question",
        "transcript": "问题",
        "target_place_id": "zhonghuan_jinke",
        "direction": "demo_exit",
    }
    r = draft(c, metadata=meta)
    assert r.status_code == 201
    pid = r.json()["post_id"]
    assert draft(c, metadata=meta).json()["post_id"] == pid
    assert draft(c, metadata={**meta, "transcript": "改变"}).status_code == 409
    assert c.get(f"/v1/audio/{pid}", headers=auth("bob")).status_code == 404
    assert c.post(f"/v1/posts/{pid}/confirm", headers=auth("bob"), json={}).status_code == 404
    assert c.get("/v1/inbox", headers=auth("bob")).json()["items"] == []
    for audio in [b"opus", wav(rate=8000), wav(seconds=30.01), wav()[:-10]]:
        assert draft(c, audio=audio).status_code == 415
    assert draft(c, audio=b"x" * (1001 * 1024)).status_code == 413
    assert draft(c, audio=b"x" * (1025 * 1024)).status_code == 413
    assert draft(c, metadata={**meta, "user_id": "bob"}).status_code == 422
    assert draft(c, metadata={**meta, "transcript": " "}).status_code == 422


@pytest.mark.parametrize(
    "change",
    [
        {"mode": "quiet"},
        {"assist_enabled": False},
        {
            "presence": {
                "place_id": "demo_approach",
                "direction": "demo_exit",
                "source": "simulated",
            }
        },
    ],
)
def test_personal_policy_counterexamples(env, change):
    c, _, _, _ = env
    assert set_context(c, **change).status_code == 200
    qid = publish(c)
    assert c.get("/v1/inbox", headers=auth("bob")).json()["items"] == []
    assert c.get(f"/v1/questions/{qid}", headers=auth()).json()["status"] == "waiting"
    assert (
        c.get("/v1/me", headers=auth()).json()["profile"]["presence"]["place_id"] == "demo_approach"
    )


def test_presence_expiry_is_separate_from_question_target(env):
    c, _, now, _ = env
    now[0] += 601
    publish(c)
    assert c.get("/v1/inbox", headers=auth("bob")).json()["items"] == []


@pytest.mark.parametrize("action", ["quiet", "decline", "cancel", "timeout", "presence"])
def test_late_answer_not_published(env, action):
    c, _, now, _ = env
    qid = publish(c)
    d = invitation(c)
    response = draft(
        c,
        "bob",
        {"kind": "answer", "transcript": "答案", "reply_to": qid, "invitation_id": d["id"]},
    )
    pid = response.json()["post_id"]
    if action == "quiet":
        set_context(c, mode="quiet")
    elif action == "decline":
        c.post(
            f"/v1/inbox/{d['id']}/ack",
            headers=auth("bob"),
            json={"event": "declined", "request_id": d["request_id"]},
        )
    elif action == "cancel":
        for _ in range(2):
            assert c.post(f"/v1/questions/{qid}/cancel", headers=auth(), json={}).status_code == 200
    else:
        now[0] += 91 if action == "timeout" else 601
    assert c.post(f"/v1/posts/{pid}/confirm", headers=auth("bob"), json={}).status_code in (
        409,
        410,
    )
    assert not c.get(f"/v1/inbox/{d['id']}", headers=auth("bob")).json()["delivery"]["actionable"]
    assert c.get(f"/v1/audio/{pid}", headers=auth()).status_code == 404


def test_quiet_retains_answer_and_stale_requests_fail(env):
    c, _, _, _ = env
    qid = publish(c)
    d = invitation(c)
    set_context(c, "alice", mode="quiet")
    answer(c, qid, d)
    item = c.get("/v1/inbox", headers=auth()).json()["items"][0]
    assert not item["actionable"] and item["state"] == "pending"
    assert c.get(f"/v1/questions/{qid}", headers=auth()).json()["status"] == "answered"
    set_context(c, "alice", mode="listen")
    latest = c.get(f"/v1/inbox/{item['id']}", headers=auth()).json()["delivery"]
    assert latest["actionable"] and latest["request_id"] != item["request_id"]
    assert (
        c.post(
            f"/v1/inbox/{item['id']}/ack",
            headers=auth(),
            json={"event": "played", "request_id": item["request_id"]},
        ).status_code
        == 409
    )


def test_invitation_context_change_does_not_reinvite_old_question(env):
    c, _, _, _ = env
    qid = publish(c)
    d = invitation(c)
    set_context(c, mode="quiet")
    set_context(c, mode="listen")
    assert c.get("/v1/inbox", headers=auth("bob")).json()["items"] == []
    assert (
        c.get(f"/v1/inbox/{d['id']}", headers=auth("bob")).json()["delivery"]["state"]
        == "cancelled"
    )
    c.post(f"/v1/questions/{qid}/cancel", headers=auth(), json={})
    publish(c)
    assert invitation(c)["id"] != d["id"]


def test_persistence_and_expiry_after_restart(env):
    c, _, now, settings = env
    qid = publish(c)
    d = invitation(c)
    aid = answer(c, qid, d)
    with TestClient(create_app(settings, lambda: now[0])) as restarted:
        assert (
            restarted.get(f"/v1/questions/{qid}", headers=auth()).json()["answer"]["post_id"] == aid
        )
        assert restarted.get(f"/v1/audio/{aid}", headers=auth()).content == wav()
        now[0] += 86401
        assert restarted.get("/v1/inbox", headers=auth()).json()["items"] == []
        assert restarted.get(f"/v1/audio/{aid}", headers=auth()).status_code == 404


def test_concurrent_confirmation_and_single_active_question(env):
    c, _, _, _ = env
    pid = draft(c).json()["post_id"]
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(
            pool.map(
                lambda _: c.post(f"/v1/posts/{pid}/confirm", headers=auth(), json={}), range(2)
            )
        )
    assert all(r.status_code == 200 for r in results)
    assert len(c.get("/v1/inbox", headers=auth("bob")).json()["items"]) == 1
    another = draft(c).json()["post_id"]
    assert c.post(f"/v1/posts/{another}/confirm", headers=auth(), json={}).status_code == 409


def test_question_status_expires_and_owner_permissions(env):
    c, _, now, _ = env
    qid = publish(c)
    d = invitation(c)
    assert c.get(f"/v1/questions/{qid}", headers=auth("bob")).status_code == 404
    assert c.get(f"/v1/inbox/{d['id']}", headers=auth()).status_code == 404
    now[0] += 121
    assert c.get(f"/v1/questions/{qid}", headers=auth()).json()["status"] == "expired"
    assert c.get(f"/v1/audio/{qid}", headers=auth("bob")).status_code == 404


def test_presence_expiry_cancels_a_still_timely_invitation(env):
    c, _, now, settings = env
    settings.presence_ttl = 5
    publish(c)
    d = invitation(c)
    now[0] += 6
    result = c.get(f"/v1/inbox/{d['id']}", headers=auth("bob")).json()["delivery"]
    assert result["state"] == "cancelled"
    assert result["reason_code"] == "PRESENCE_EXPIRED"
