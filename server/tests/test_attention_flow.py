import asyncio
import json

import httpx
import pytest
from conftest import answer, auth, draft, publish, set_context

from app.attention_worker import AttentionWorker
from app.decision import Attention
from app.service import Service


class Decider:
    def __init__(self, action="notify"):
        self.action = action
        self.calls = []

    async def evaluate_async(self, focus, text, context=None):
        self.calls.append((focus, text))
        return Attention(self.action, "MODEL_DECISION", 0.9, 0.01)


def enable(env, action="notify"):
    c, s, now, settings = env
    settings.decision_enabled = True
    s.decider = Decider(action)
    return c, s, now, settings


def step(s):
    return asyncio.run(AttentionWorker(s).once())


def row(s):
    with s.scope() as db:
        return dict(
            db.execute(
                "SELECT * FROM deliveries WHERE type='invitation' ORDER BY created_at DESC LIMIT 1"
            ).fetchone()
        )


def test_notification_requires_decision_and_closes_full_reply_loop(env):
    c, s, _, _ = enable(env)
    qid = publish(c)
    inv = row(s)
    assert c.get("/v1/inbox", headers=auth("bob")).json()["items"] == []
    assert not c.get(f"/v1/inbox/{inv['id']}", headers=auth("bob")).json()["delivery"]["actionable"]
    assert c.get(f"/v1/audio/{qid}", headers=auth("bob")).status_code == 404
    assert c.get("/v1/inbox/" + inv["id"], headers=auth()).status_code == 404
    assert (
        c.post(
            f"/v1/inbox/{inv['id']}/ack",
            headers=auth("bob"),
            json={"request_id": inv["request_id"], "event": "alerted"},
        ).status_code
        == 409
    )
    r = draft(
        c,
        "bob",
        {"kind": "answer", "transcript": "这里很堵", "reply_to": qid, "invitation_id": inv["id"]},
    )
    assert r.status_code == 409
    assert step(s)
    received = c.get("/v1/inbox", headers=auth("bob")).json()["items"][0]
    assert received["payload"]["attention"]["provider"] == "bamboo"
    assert received["payload"]["attention"]["action"] == "notify"
    assert c.get(f"/v1/audio/{qid}", headers=auth("bob")).status_code == 200
    c.post(
        f"/v1/inbox/{inv['id']}/ack",
        headers=auth("bob"),
        json={"request_id": inv["request_id"], "event": "alerted"},
    )
    assert c.get("/v1/inbox", headers=auth("bob")).json()["items"][0]["state"] == "prompted"
    aid = answer(c, qid, received)
    s.decider.action = "ignore"  # Own requested answers do not undergo unrelated-message filtering.
    deliveries = c.get("/v1/inbox", headers=auth()).json()["items"]
    assert deliveries[0]["type"] == "audio"
    assert c.get(f"/v1/audio/{aid}", headers=auth()).status_code == 200
    c.post(
        f"/v1/inbox/{deliveries[0]['id']}/ack",
        headers=auth(),
        json={"request_id": deliveries[0]["request_id"], "event": "played"},
    )
    assert not c.get("/v1/inbox", headers=auth()).json()["items"]
    assert len(s.decider.calls) == 1


@pytest.mark.parametrize("action", ["notify", "ignore", "defer"])
def test_decisions_are_cached_per_delivery_and_survive_service_restart(env, action):
    c, s, _, settings = enable(env, action)
    publish(c)
    assert step(s)
    for _ in range(10):
        items = c.get("/v1/inbox", headers=auth("bob")).json()["items"]
        assert bool(items) == (action == "notify")
        assert not step(s)
    assert len(s.decider.calls) == 1
    restarted = Service(settings, s.clock)
    restarted.decider = Decider()
    assert not step(restarted)
    assert restarted.decider.calls == []
    with restarted.scope() as db:
        items = restarted.inbox(db, "bob")["items"]
        assert bool(items) == (action == "notify")


@pytest.mark.parametrize("change", ["quiet", "disabled", "place", "expired"])
def test_hard_rules_run_before_model(env, change):
    c, s, now, settings = enable(env)
    if change == "quiet":
        set_context(c, mode="quiet")
    if change == "disabled":
        set_context(c, assist_enabled=False)
    if change == "place":
        set_context(
            c,
            presence={"place_id": "demo_approach", "direction": "demo_exit", "source": "simulated"},
        )
    if change == "expired":
        now[0] += settings.presence_ttl + 1
    publish(c)
    assert not step(s)
    assert not s.decider.calls
    assert not c.get("/v1/inbox", headers=auth("bob")).json()["items"]


@pytest.mark.parametrize(
    "change",
    [
        "quiet",
        "disabled",
        "place",
        "focus",
        "cancel",
        "question_expiry",
        "invitation_expiry",
        "presence_expiry",
    ],
)
def test_late_notify_cannot_override_current_rules(env, change):
    c, s, now, settings = enable(env)
    qid = publish(c)
    snapshot = s.next_attention()
    if change == "quiet":
        set_context(c, mode="quiet")
    if change == "disabled":
        set_context(c, assist_enabled=False)
    if change == "place":
        set_context(
            c,
            presence={"place_id": "demo_approach", "direction": "demo_exit", "source": "simulated"},
        )
    if change == "focus":
        set_context(c, focus="只关注景区停车")
    if change == "cancel":
        c.post(f"/v1/questions/{qid}/cancel", headers=auth(), json={})
    if change == "question_expiry":
        now[0] += settings.question_ttl + 1
    if change == "invitation_expiry":
        now[0] += settings.invitation_ttl + 1
    if change == "presence_expiry":
        now[0] += settings.presence_ttl + 1
    assert not s.finish_attention(snapshot, Attention("notify", "MODEL_DECISION", 0.99, 0.001))
    assert not c.get("/v1/inbox", headers=auth("bob")).json()["items"]
    assert c.get(f"/v1/audio/{qid}", headers=auth("bob")).status_code == 404


def test_inference_does_not_hold_database_lock_and_quiet_wins_in_flight(env):
    c, s, _, _ = enable(env)
    publish(c)

    async def scenario():
        started, release = asyncio.Event(), asyncio.Event()

        class Slow:
            async def evaluate_async(self, focus, text, context=None):
                started.set()
                await release.wait()
                return Attention("notify", "MODEL_DECISION", 0.9, 0.01)

        s.decider = Slow()
        task = asyncio.create_task(AttentionWorker(s).once())
        await started.wait()
        # This would deadlock/timeout if the model call held BEGIN IMMEDIATE.
        response = await asyncio.wait_for(
            asyncio.to_thread(set_context, c, mode="quiet"), timeout=1
        )
        assert response.status_code == 200
        release.set()
        await task

    asyncio.run(scenario())
    assert not c.get("/v1/inbox", headers=auth("bob")).json()["items"]
    assert row(s)["attention_json"] is None


def test_timeout_is_bounded_cached_defer_and_does_not_stop_other_deliveries(env):
    c, s, _, settings = enable(env)
    settings.decision_timeout = 0.05

    class Hangs:
        calls = 0

        async def evaluate_async(self, focus, text, context=None):
            self.calls += 1
            await asyncio.sleep(10)

    s.decider = Hangs()
    publish(c)
    assert step(s)
    assert json.loads(row(s)["attention_json"])["reason"] == "MODEL_TIMEOUT"
    assert not step(s) and s.decider.calls == 1
    assert c.get("/healthz").status_code == 200
    assert c.get("/v1/me", headers=auth()).status_code == 200


def test_lifespan_runs_real_worker_with_mocked_transport_and_shuts_it_down(tmp_path):
    import time

    from conftest import ALICE, BOB
    from fastapi.testclient import TestClient

    from app.config import Settings
    from app.decision import BambooDecision
    from app.main import create_app

    calls = []

    def provider(request):
        calls.append(request)
        return httpx.Response(
            200,
            json={
                "answers": {
                    "attention": {
                        "type": "choice",
                        "choice": "notify",
                        "confidence": 0.9,
                        "none_probability": 0.01,
                    }
                }
            },
        )

    app = create_app(
        Settings(
            _env_file=None,
            data_dir=tmp_path,
            alice_token=ALICE,
            bob_token=BOB,
            decision_enabled=True,
        )
    )
    app.state.service.seed()
    app.state.service.decider = BambooDecision("fixture", transport=httpx.MockTransport(provider))
    with TestClient(app) as c:
        publish(c)
        end = time.monotonic() + 3
        while not c.get("/v1/inbox", headers=auth("bob")).json()["items"]:
            assert time.monotonic() < end
            time.sleep(0.02)
        assert len(calls) == 1
        task = app.state.attention_task
    assert task.done()


@pytest.mark.parametrize("moment", ["in_flight", "after_notify"])
def test_real_location_movement_still_overrules_model(env, moment):
    from test_location import locate, prepare, publish_at

    c, s, now, _ = enable(env)
    _, _, _, target = prepare(env)
    assert locate(c, now[0]).status_code == 200
    qid = publish_at(c, target)
    snapshot = s.next_attention()
    if moment == "after_notify":
        assert s.finish_attention(snapshot, Attention("notify", "MODEL_DECISION", 0.9, 0.01))
        assert c.get("/v1/inbox", headers=auth("bob")).json()["items"]
    assert locate(c, now[0], latitude=32).status_code == 200
    assert not s.finish_attention(snapshot, Attention("notify", "MODEL_DECISION", 0.99, 0.001))
    assert not c.get("/v1/inbox", headers=auth("bob")).json()["items"]
    assert c.get(f"/v1/audio/{qid}", headers=auth("bob")).status_code == 404


def test_shared_model_keeps_each_recipients_focus_and_result_isolated(env):
    from app.storage import digest

    c, s, _, _ = enable(env)
    with s.scope() as db:
        db.execute(
            "INSERT INTO users VALUES (?,?,?,?)", ("carol", digest("c" * 40), "carol-device", "")
        )
        db.execute(
            "INSERT INTO profiles (user_id,place_id,direction,source,updated_at,assist_enabled,mode,focus,revision) VALUES (?,?,?,?,?,?,?,?,?)",
            (
                "carol",
                "zhonghuan_jinke",
                "demo_exit",
                "simulated",
                s.clock(),
                1,
                "listen",
                "只关注景区停车",
                1,
            ),
        )

    class Personal:
        calls = []

        async def evaluate_async(self, focus, transcript, context=None):
            self.calls.append(focus)
            return Attention("ignore" if "景区" in focus else "notify", "MODEL_DECISION", 0.9, 0.01)

    s.decider = Personal()
    publish(c)
    assert step(s) and step(s)
    assert len(s.decider.calls) == 2
    assert c.get("/v1/inbox", headers=auth("bob")).json()["items"]
    assert not c.get("/v1/inbox", headers={"Authorization": "Bearer " + "c" * 40}).json()["items"]


def test_old_quiet_status_is_repaired_once_without_unmuting_or_changing_new_preferences(env):
    c, s, _, settings = env
    with s.scope() as db:
        db.execute("DELETE FROM schema_migrations WHERE id='attention_legacy_quiet_focus_v1'")
        db.execute("UPDATE profiles SET focus='现在安静',mode='quiet' WHERE user_id='bob'")
        db.execute("UPDATE profiles SET focus='只关注景区停车' WHERE user_id='alice'")
    restarted = Service(settings, s.clock)
    with restarted.scope() as db:
        bob = restarted.profile(db, "bob")
        assert bob["mode"] == "quiet"
        assert bob["focus"] == "愿意回答附近路口的拥堵问题"
        assert restarted.profile(db, "alice")["focus"] == "只关注景区停车"
        # A preference explicitly saved after this migration must remain user-owned.
        db.execute("UPDATE profiles SET focus='现在安静' WHERE user_id='bob'")
    again = Service(settings, s.clock)
    with again.scope() as db:
        assert again.profile(db, "bob")["focus"] == "现在安静"
