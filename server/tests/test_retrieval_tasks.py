import json
import uuid

from conftest import answer, auth, draft, invitation, publish, wav
from fastapi.testclient import TestClient
from test_agent_commands import start_cap, text_turn
from test_agent_network import setup
from test_exchange import stamp, utter
from test_voice_matching import setup_place, voice

from app.main import create_app
from app.storage import digest


def task_action(c, task, action="cancel", user="alice", request_id=None):
    return c.post(
        f"/v1/tasks/{task['id']}/actions",
        headers=auth(user),
        json={
            "expected_revision": task["revision"],
            "client_request_id": request_id or str(uuid.uuid4()),
            "action": action,
        },
    )


def test_pre_network_firmware_keeps_local_original_question_path(env):
    from test_exchange import prepare, start

    c, svc, now = prepare(env)
    legacy = start(c).json()["session"]
    svc.exchange_decider.action = "question"
    result = utter(c, legacy, now, transcript="这里堵不堵？").json()
    assert result["status"] == "published" and result["action"] == "question", result
    with svc.scope() as db:
        assert svc.post(db, result["post_id"])["target_place_id"] == "road"


def add_user(svc, user):
    with svc.scope() as db:
        db.execute("INSERT INTO users VALUES (?,?,?,?)", (user, digest(user[0] * 40), user, user))
        cols = [r[1] for r in db.execute("PRAGMA table_info(profiles)")]
        values = dict(svc.profile_row(db, "bob"))
        values["user_id"] = user
        db.execute(
            f"INSERT INTO profiles({','.join(cols)}) VALUES ({','.join('?' for _ in cols)})",
            tuple(values[k] for k in cols),
        )


def test_shared_retrieval_prioritizes_valid_sources_and_never_turns_knowledge_into_parking(env):
    c, svc, now = setup_place(env)
    notice = voice(env, kind="announcement", transcript="B2已满，请前往B3")
    knowledge = voice(env, kind="knowledge", transcript="停车规则：入场需登记")
    observation = voice(
        env,
        purpose="observation",
        transcript="B2入口还有几个车位",
        valid_from=stamp(now[0]),
        valid_until=stamp(now[0] + 60),
    )
    reflection = voice(env, transcript="这里停车后看风景很漂亮")
    result = c.post(
        "/v1/information", headers=auth(), json={"place_id": "scenic", "purpose": "parking"}
    ).json()
    assert [p["post_id"] for p in result["items"]] == [notice, observation]
    assert result["requires_source_comparison"]
    assert all(p["observed_at"] is None for p in result["items"])
    assert knowledge not in {p["post_id"] for p in result["items"]}
    assert reflection not in {p["post_id"] for p in result["items"]}
    with svc.scope() as db:
        response = svc.information_response(result)
        assert len(response["sources"]) == 2 and "不能合并" in response["text"]
        assert not svc.find_information(db, "alice", "other", purpose="parking")["items"]
    c.post(f"/v1/voices/{notice}/withdraw", headers=auth("bob"), json={})
    now[0] += 181
    empty = c.post(
        "/v1/information", headers=auth(), json={"place_id": "scenic", "purpose": "parking"}
    ).json()
    assert empty["status"] == "no_valid_information" and empty["items"] == []


def test_feed_queue_uses_new_sources_with_old_access_version_and_deduplication_gates(env):
    c, svc, _ = setup_place(env)
    pid = voice(env, kind="announcement", transcript="B2已满")
    with svc.scope() as db:
        db.execute("UPDATE profiles SET place_id='scenic' WHERE user_id='alice'")
        feed = svc.get_feed(db, "alice")
        assert pid in {p["post_id"] for p in feed["candidates"]}
        kw = dict(
            request_id=feed["request_id"], profile_revision=feed["profile_revision"], post_id=pid
        )
        first = svc.queue_voice(db, "alice", **kw)
        assert first == svc.queue_voice(db, "alice", **kw)
        assert (
            db.execute(
                "SELECT count(*) FROM deliveries WHERE post_id=? AND recipient_id='alice'", (pid,)
            ).fetchone()[0]
            == 1
        )
    c.post(f"/v1/voices/{pid}/withdraw", headers=auth("bob"), json={})
    validated = c.post(
        "/v1/information/validate", headers=auth(), json={"message_ids": [pid]}
    ).json()
    assert not validated["valid"]
    with svc.scope() as db:
        assert pid not in {p["post_id"] for p in svc.get_feed(db, "alice")["candidates"]}


def test_private_parking_query_uses_existing_notice_no_public_question_and_rechecks_sources(env):
    c, svc, now, _ = setup(env)
    with svc.scope() as db:
        db.execute("INSERT INTO channels VALUES ('parking','B0FFJSBSGR','停车场','运营方',1)")
        db.execute("INSERT INTO channel_operators VALUES ('parking','bob')")
    payload = {
        "kind": "announcement",
        "target_place_id": "B0FFJSBSGR",
        "channel_id": "parking",
        "purpose": "notice",
        "transcript": "停车场B2已满",
        "valid_from": stamp(now[0]),
        "valid_until": stamp(now[0] + 600),
    }
    pid = draft(c, "bob", payload).json()["post_id"]
    c.post(f"/v1/posts/{pid}/confirm", headers=auth("bob"), json={})
    a = start_cap(c, private_query=True)
    result = text_turn(c, a, now, "七牛还有车位吗？").json()
    assert result["result"]["outcome"] == "information", result
    assert result["agent_response"]["sources"][0]["post_id"] == pid
    with svc.scope() as db:
        assert db.execute("SELECT count(*) FROM posts WHERE kind='question'").fetchone()[0] == 0
    original_plan = svc.plan_agent_turn

    async def withdraw_before_finish(*args, **kwargs):
        plan = await original_plan(*args, **kwargs)
        with svc.scope() as db:
            svc.withdraw_voice(db, "bob", pid)
        return plan

    svc.plan_agent_turn = withdraw_before_finish
    late = text_turn(c, a, now, "七牛还有车位吗？").json()
    assert late["reason"] == "INFORMATION_CHANGED", late
    task = c.get(f"/v1/tasks/{result['task_id']}", headers=auth()).json()
    assert task["status"] == "responded" and not task["sources"][0]["currently_valid"]


def test_request_lookup_survives_restart_without_republish_and_is_owner_only(env):
    c, svc, now, _ = setup(env)
    a = start_cap(c, request_humans=True)
    rid = str(uuid.uuid4())
    result = utter(c, a, now, request_id=rid, transcript="帮我问问张江人工智能岛还有位吗？").json()
    app = create_app(svc.settings, lambda: now[0])
    with TestClient(app) as restarted:
        recovered = restarted.get(f"/v1/requests/{rid}", headers=auth()).json()
        assert recovered["turn"]["post_id"] == result["post_id"]
        assert recovered["task"]["status"] == "waiting"
        assert restarted.get(f"/v1/requests/{rid}", headers=auth("bob")).status_code == 404
        assert (
            restarted.get(f"/v1/tasks/{result['task_id']}", headers=auth("bob")).status_code == 404
        )
        with app.state.service.scope() as db:
            assert db.execute("SELECT count(*) FROM posts WHERE kind='question'").fetchone()[0] == 1


def test_answer_playback_and_resolution_are_separate_and_actions_are_idempotent(env):
    c, svc, _, _ = env
    qid = publish(c)
    inv = invitation(c)
    answered = answer(c, qid, inv)
    task = c.get("/v1/tasks", headers=auth()).json()["items"][0]
    assert task["status"] == "responded"
    with svc.scope() as db:
        peer_task = svc.create_personal_task(db, "bob", uuid.uuid4(), "我已回复")
        db.execute(
            "UPDATE personal_tasks SET conversation_id=?,status='responded' WHERE id=?",
            (qid, peer_task["id"]),
        )
        svc.change_personal_task(
            db, "bob", peer_task["id"], peer_task["revision"], uuid.uuid4(), "cancel"
        )
        assert (
            db.execute(
                "SELECT state FROM deliveries WHERE question_id=? AND recipient_id='alice' AND type='audio'",
                (qid,),
            ).fetchone()[0]
            == "pending"
        )
    delivery = c.get("/v1/inbox", headers=auth()).json()["items"][0]
    c.post(
        f"/v1/inbox/{delivery['id']}/ack",
        headers=auth(),
        json={"request_id": delivery["request_id"], "event": "played"},
    )
    task = c.get(f"/v1/tasks/{task['id']}", headers=auth()).json()
    assert task["status"] == "responded" and task["deliveries"][0]["state"] == "played"
    request_id = str(uuid.uuid4())
    resolved = task_action(c, task, "resolve", request_id=request_id).json()
    assert resolved["status"] == "resolved"
    assert (
        task_action(c, task, "resolve", request_id=request_id).json()["revision"]
        == resolved["revision"]
    )
    assert task_action(c, task, "cancel", request_id=request_id).status_code == 409
    assert c.get(f"/v1/questions/{qid}", headers=auth()).json()["status"] == "answered"
    assert answered


def test_cancel_during_external_work_cannot_publish_late_result(env):
    c, svc, now, _ = setup(env)
    a = start_cap(c, request_humans=True)

    def cancel():
        with svc.scope() as db:
            task = db.execute("SELECT * FROM personal_tasks WHERE user_id='alice'").fetchone()
            svc.change_personal_task(
                db, "alice", task["id"], task["revision"], uuid.uuid4(), "cancel"
            )

    svc.geo.after_search = cancel
    result = utter(c, a, now, transcript="帮我问问上海世纪公园还有车位吗？").json()
    assert result["reason"] == "TASK_CLOSED", result
    task = c.get(f"/v1/tasks/{result['task_id']}", headers=auth()).json()
    assert task["status"] == "cancelled"
    with svc.scope() as db:
        assert db.execute("SELECT count(*) FROM posts").fetchone()[0] == 0


def test_bounded_batches_cooldown_total_budget_and_no_reinviting_same_person(env):
    c, svc, now, _ = env
    svc.settings.invitation_batch_size = 2
    svc.settings.invitation_max_recipients = 3
    svc.settings.invitation_cooldown_seconds = 5
    for user in ("charlie", "david", "eve"):
        add_user(svc, user)
    qid = publish(c)
    with svc.scope() as db:
        first = db.execute(
            "SELECT * FROM deliveries WHERE question_id=? AND type='invitation'", (qid,)
        ).fetchall()
        assert len(first) == 2
        db.execute("UPDATE deliveries SET state='declined' WHERE question_id=?", (qid,))
        svc.route_question(db, svc.post(db, qid))
        assert (
            db.execute("SELECT count(*) FROM deliveries WHERE question_id=?", (qid,)).fetchone()[0]
            == 2
        )
    now[0] += 6
    with svc.scope() as db:
        svc.route_question(db, svc.post(db, qid))
        assert (
            db.execute("SELECT count(*) FROM deliveries WHERE question_id=?", (qid,)).fetchone()[0]
            == 3
        )
        db.execute("UPDATE deliveries SET state='declined' WHERE question_id=?", (qid,))
    now[0] += 6
    with svc.scope() as db:
        svc.route_question(db, svc.post(db, qid))
        assert (
            db.execute("SELECT count(*) FROM deliveries WHERE question_id=?", (qid,)).fetchone()[0]
            == 3
        )
        assert (
            len(
                {
                    r[0]
                    for r in db.execute(
                        "SELECT recipient_id FROM deliveries WHERE question_id=?", (qid,)
                    )
                }
            )
            == 3
        )
    now[0] += 121
    task = c.get("/v1/tasks", headers=auth()).json()["items"][0]
    assert task["status"] == "timed_out"


def test_unknown_request_does_not_create_a_task_and_manual_post_lookup_is_supported(env):
    c, svc, _, _ = env
    missing = c.get(f"/v1/requests/{uuid.uuid4()}", headers=auth())
    assert missing.status_code == 404 and missing.json()["error"]["code"] == "REQUEST_NOT_FOUND"
    rid = str(uuid.uuid4())
    response = c.post(
        "/v1/posts/drafts",
        headers=auth(),
        files={"audio": ("voice.wav", wav(), "audio/wav")},
        data={
            "metadata": json.dumps(
                {
                    "client_request_id": rid,
                    "kind": "question",
                    "transcript": "问一下",
                    "target_place_id": "zhonghuan_jinke",
                    "direction": "demo_exit",
                }
            )
        },
    )
    recovered = c.get(f"/v1/requests/{rid}", headers=auth()).json()
    assert recovered["post"]["post_id"] == response.json()["post_id"]
    with svc.scope() as db:
        assert db.execute("SELECT count(*) FROM personal_tasks").fetchone()[0] == 0


def test_same_place_tasks_remain_independent_and_stale_actions_do_not_mutate(env):
    c, svc, _, _ = env
    add_user(svc, "charlie")
    first = publish(c)
    headers = {"Authorization": "Bearer " + "c" * 40}
    response = c.post(
        "/v1/posts/drafts",
        headers=headers,
        files={"audio": ("voice.wav", wav(), "audio/wav")},
        data={
            "metadata": json.dumps(
                {
                    "client_request_id": str(uuid.uuid4()),
                    "kind": "question",
                    "transcript": "同一地点的另一个问题",
                    "target_place_id": "zhonghuan_jinke",
                    "direction": "demo_exit",
                }
            )
        },
    )
    second = response.json()["post_id"]
    assert c.post(f"/v1/posts/{second}/confirm", headers=headers, json={}).status_code == 200
    task = c.get("/v1/tasks", headers=auth()).json()["items"][0]
    stale = {**task, "revision": task["revision"] + 1}
    assert task_action(c, stale).status_code == 409
    assert task_action(c, task).json()["status"] == "cancelled"
    other = c.get("/v1/tasks", headers=headers).json()["items"][0]
    assert other["question_id"] == second and other["status"] == "waiting"
    assert c.get(f"/v1/tasks/{task['id']}", headers=headers).status_code == 404
    with svc.scope() as db:
        assert svc.post(db, first)["question_status"] == "cancelled"
        assert (
            db.execute(
                "SELECT state FROM deliveries WHERE question_id=? AND recipient_id='bob'", (second,)
            ).fetchone()[0]
            == "pending"
        )
