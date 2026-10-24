"""Requirements AC-01..12: deterministic HTTP rules, not physical arrival evidence."""

import json

import pytest
from conftest import answer, auth, draft, invitation, publish, set_context

from app.service import stamp
from app.storage import digest


def setup_place(env):
    c, s, now, _ = env
    with s.scope() as db:
        for pid, lat in [("scenic", 31.2), ("other", 32.2)]:
            db.execute(
                "INSERT INTO places VALUES (?,?,?,?,?,?)", (pid, pid, lat, 121.4, "poi", now[0])
            )
        db.execute("INSERT INTO channels VALUES ('parking','scenic','停车场','停车场运营方',1)")
        db.execute("INSERT INTO channel_operators VALUES ('parking','bob')")
    return c, s, now


def locate(env, user="alice", lat=31.2):
    c, s, now, _ = env
    from app.models import LocationUpdate

    p = c.get("/v1/me", headers=auth(user)).json()["profile"]
    data = LocationUpdate.model_validate(
        {
            "expected_revision": p["revision"],
            "location": {
                "latitude": lat,
                "longitude": 121.4,
                "accuracy_m": 5,
                "sampled_at": stamp(now[0]),
                "source": "android",
            },
        }
    )
    with s.scope() as db:
        s.update_location(
            db,
            user,
            data,
            {
                "latitude": lat,
                "longitude": 121.4,
                "places": [
                    {
                        "id": "scenic",
                        "label": "观景点",
                        "latitude": 31.2,
                        "longitude": 121.4,
                        "kind": "poi",
                    }
                ],
            },
        )


def preferences(c, user="alice", **changes):
    p = c.get("/v1/me", headers=auth(user)).json()["profile"]
    result = c.put(
        "/v1/me/listening",
        headers=auth(user),
        json={"expected_revision": p["revision"], **p["listening"], **changes},
    )
    assert result.status_code == 200, result.text
    return result


def voice(env, user="bob", kind="scene", **changes):
    c, _, now, _ = env
    fields = {
        "kind": kind,
        "transcript": "从这里看落日特别漂亮",
        "target_place_id": "scenic",
        "purpose": "reflection",
        "topics": ["风景"],
    }
    if kind == "announcement":
        fields.update(
            channel_id="parking",
            purpose="notice",
            valid_from=stamp(now[0]),
            valid_until=stamp(now[0] + 600),
        )
    if kind == "knowledge":
        fields.update(purpose="explanation", source_note="现场指示牌，2026-10-24 核对")
    result = draft(c, user, {**fields, **changes})
    assert result.status_code == 201, result.text
    pid = result.json()["post_id"]
    result = c.post(f"/v1/posts/{pid}/confirm", headers=auth(user), json={})
    assert result.status_code == 200, result.text
    return pid


def inbox(c, user="alice"):
    return c.get("/v1/inbox", headers=auth(user)).json()["items"]


def ack(c, d, event="played", user="alice"):
    return c.post(
        f"/v1/inbox/{d['id']}/ack",
        headers=auth(user),
        json={"event": event, "request_id": d["request_id"]},
    )


def test_ac01_explicit_relation_and_ac03_directed_rounds(env):
    c, s, now, _ = env
    with s.scope() as db:
        db.execute(
            "INSERT INTO users VALUES ('charlie',?,'device_c','agent_c')", (digest("c" * 40),)
        )
        db.execute(
            "INSERT INTO profiles SELECT 'charlie',place_id,direction,source,updated_at,assist_enabled,mode,focus,revision,location_json,latitude,longitude,sampled_at,accuracy_m FROM profiles WHERE user_id='alice'"
        )
    q = publish(c)
    inv = invitation(c)
    other = c.post(
        "/v1/posts/drafts",
        headers={"Authorization": "Bearer " + "c" * 40},
        files={"audio": ("voice.wav", __import__("conftest").wav(), "audio/wav")},
        data={
            "metadata": json.dumps(
                {
                    "client_request_id": "11111111-1111-4111-8111-111111111112",
                    "kind": "question",
                    "transcript": "另一个问题",
                    "target_place_id": "zhonghuan_jinke",
                    "direction": "demo_exit",
                }
            )
        },
    ).json()["post_id"]
    assert (
        c.post(
            f"/v1/posts/{other}/confirm", headers={"Authorization": "Bearer " + "c" * 40}, json={}
        ).status_code
        == 200
    )
    bad = draft(
        c,
        "bob",
        {"kind": "answer", "transcript": "错配", "reply_to": other, "invitation_id": inv["id"]},
    )
    assert bad.status_code == 409
    a = answer(c, q, inv)
    assert (
        c.get(f"/v1/questions/{other}", headers={"Authorization": "Bearer " + "c" * 40}).json()[
            "answer"
        ]
        is None
    )
    fields = {
        "kind": "reply",
        "transcript": "要等多久？",
        "reply_to": a,
        "root_question_id": q,
        "recipient_id": "bob",
    }
    assert draft(c, metadata={**fields, "recipient_id": "charlie"}).status_code == 403
    assert draft(c, metadata={**fields, "visibility": "private"}).status_code == 422
    r = draft(c, metadata=fields).json()["post_id"]
    set_context(
        c,
        user="alice",
        presence={"place_id": "demo_approach", "direction": "demo_exit", "source": "simulated"},
    )
    assert c.post(f"/v1/posts/{r}/confirm", headers=auth(), json={}).status_code == 200
    reply = next(d for d in inbox(c, "bob") if d["payload"].get("post_id") == r)
    assert reply["payload"]["recipient_id"] == "bob" and reply["question_id"] == q
    assert c.get(f"/v1/conversations/{q}", headers=auth("bob")).json()["visibility"] == "public"
    now[0] += 86401
    assert draft(c, "bob", {**fields, "reply_to": r, "recipient_id": "alice"}).status_code == 410


def test_ac02_moving_waiter_quiet_and_cancel(env):
    c, _, _, _ = env
    q = publish(c)
    inv = invitation(c)
    set_context(
        c,
        user="alice",
        presence={"place_id": "zhonghuan_jinke", "direction": "demo_exit", "source": "simulated"},
        mode="quiet",
    )
    a = answer(c, q, inv)
    d = inbox(c)[0]
    assert d["payload"]["post_id"] == a and not d["actionable"]
    set_context(c, user="alice", mode="listen", assist_enabled=False)
    assert inbox(c)[0]["actionable"]
    q2 = publish(c)
    inv2 = invitation(c)
    pending = draft(
        c,
        "bob",
        {"kind": "answer", "transcript": "迟到回复", "reply_to": q2, "invitation_id": inv2["id"]},
    ).json()["post_id"]
    c.post(f"/v1/questions/{q2}/cancel", headers=auth(), json={})
    assert c.post(f"/v1/posts/{pending}/confirm", headers=auth("bob"), json={}).status_code == 409


def test_ac04_history_and_expired_observation(env):
    c, _, now = setup_place(env)
    history = voice(env)
    observation = voice(
        env, purpose="observation", valid_from=stamp(now[0]), valid_until=stamp(now[0] + 5)
    )
    preferences(c, scene_enabled=True)
    now[0] += 6
    locate(env)
    items = inbox(c)
    assert [d["payload"]["post_id"] for d in items] == [history]
    assert "历史" in items[0]["payload"]["context_note"]
    assert c.get(f"/v1/audio/{observation}", headers=auth()).status_code == 404
    assert (
        draft(
            c,
            "bob",
            {
                "kind": "scene",
                "transcript": "实时观察",
                "target_place_id": "scenic",
                "purpose": "observation",
            },
        ).status_code
        == 422
    )


def test_ac05_remote_query_and_unknown_conditions(env):
    c, _, _ = setup_place(env)
    p = voice(env, applicability="unknown")
    preferences(c, scene_enabled=True, subscribed_places=["scenic"])
    assert len(c.get("/v1/voices?place_id=scenic", headers=auth()).json()["items"]) == 1
    assert inbox(c) == []
    locate(env)
    assert inbox(c) == []
    manual = c.post(f"/v1/voices/{p}/queue", headers=auth(), json={}).json()["delivery"]
    assert manual["actionable"] and manual["payload"]["priority"] == 0
    assert (
        c.post(f"/v1/voices/{p}/queue", headers=auth(), json={}).json()["delivery"]["id"]
        == manual["id"]
    )


def test_ac06_announcements_independent_of_interest_assist_mute(env):
    c, _, _ = setup_place(env)
    p = voice(env, kind="announcement")
    for user in ("alice", "bob"):
        preferences(c, user, interests=[user], channel_enabled=user == "alice")
        set_context(
            c, user=user, assist_enabled=False, mode="listen" if user == "alice" else "quiet"
        )
        locate(env, user)
        d = inbox(c, user)[0]
        assert d["payload"]["post_id"] == p and d["actionable"] == (user == "alice")
        assert d["payload"]["author_label"] == "停车场运营方"
        assert c.get("/v1/voices?place_id=scenic", headers=auth(user)).json()["items"]


@pytest.mark.parametrize("change", ["replace", "withdraw", "expire", "revoke"])
def test_ac07_stale_version_never_starts(env, change):
    c, s, now = setup_place(env)
    p = voice(env, kind="announcement")
    locate(env)
    d = inbox(c)[0]
    if change == "replace":
        current = voice(env, kind="announcement", replaces=p, transcript="B2 已恢复")
    elif change == "withdraw":
        assert c.post(f"/v1/voices/{p}/withdraw", headers=auth("bob"), json={}).status_code == 200
    elif change == "revoke":
        with s.scope() as db:
            db.execute("DELETE FROM channel_operators WHERE channel_id='parking'")
    else:
        now[0] += 601
    assert not c.get(f"/v1/inbox/{d['id']}", headers=auth()).json()["delivery"]["actionable"]
    assert c.get(f"/v1/audio/{p}", headers=auth()).status_code == 404
    catalog = c.get("/v1/voices?place_id=scenic", headers=auth()).json()
    if change == "replace":
        assert [x["post_id"] for x in catalog["items"]] == [current]
    else:
        assert catalog["announcement_status"] == "暂无有效运营公告"


def test_ac08_jitter_floor_unknown_and_visit_reentry(env):
    c, _, now = setup_place(env)
    voice(env, kind="announcement", scope="floor", scope_label="B2")
    p = voice(env, kind="announcement")
    locate(env)
    d = inbox(c)[0]
    assert "附近" in d["payload"]["location_scope"]
    assert ack(c, d).status_code == 200
    locate(env, lat=31.3)
    assert inbox(c) == []
    now[0] += 3
    locate(env)
    assert inbox(c) == []
    locate(env, lat=31.3)
    inbox(c)
    now[0] += 121
    locate(env)
    next_visit = inbox(c)[0]
    assert next_visit["payload"]["post_id"] == p and next_visit["request_id"] != d["request_id"]
    assert len(c.get("/v1/voices?place_id=scenic", headers=auth()).json()["items"]) == 2


def test_ac09_operators_authorized_per_poi_and_confirm_rechecks(env):
    c, s, now = setup_place(env)
    data = {
        "kind": "announcement",
        "transcript": "B2 满了",
        "target_place_id": "scenic",
        "channel_id": "parking",
        "purpose": "notice",
        "valid_from": stamp(now[0]),
        "valid_until": stamp(now[0] + 600),
    }
    assert draft(c, metadata=data).status_code == 403
    assert draft(c, "bob", {**data, "target_place_id": "other"}).status_code == 403
    p = draft(c, "bob", data).json()["post_id"]
    with s.scope() as db:
        db.execute("DELETE FROM channel_operators")
    assert c.post(f"/v1/posts/{p}/confirm", headers=auth("bob"), json={}).status_code == 403


def test_ac10_reasons_dedup_and_separate_receipts_persist(env):
    c, s, _ = setup_place(env)
    p = voice(env)
    preferences(c, scene_enabled=True, subscribed_places=["scenic"])
    locate(env)
    d = inbox(c)[0]
    assert len(d["payload"]["reasons"]) == 2
    manual = c.post(f"/v1/voices/{p}/queue", headers=auth(), json={}).json()["delivery"]
    assert manual["id"] == d["id"] and len(manual["payload"]["reasons"]) == 3
    for event in ("received", "seen", "played", "played"):
        assert ack(c, d, event).status_code == 200
    assert inbox(c) == []
    with s.scope() as db:
        row = s.delivery_row(db, "alice", d["id"])
        assert row["received_at"] and row["seen_at"] and row["played_at"]
    from fastapi.testclient import TestClient

    from app.main import create_app

    with TestClient(create_app(s.settings, s.clock)) as restarted:
        assert inbox(restarted) == []


def test_ac11_recheck_after_delay_mute_departure_and_reconnect(env):
    c, _, now = setup_place(env)
    voice(env, kind="announcement")
    locate(env)
    d = inbox(c)[0]
    set_context(c, user="alice", mode="quiet", presence=None)
    assert not c.get(f"/v1/inbox/{d['id']}", headers=auth()).json()["delivery"]["actionable"]
    set_context(c, user="alice", mode="listen", presence=None)
    locate(env, lat=31.3)
    assert not c.get(f"/v1/inbox/{d['id']}", headers=auth()).json()["delivery"]["actionable"]
    now[0] += 601
    locate(env)
    assert not any(x["actionable"] for x in inbox(c))


def test_ac12_knowledge_on_demand_and_priority(env):
    c, _, _ = setup_place(env)
    for i in range(10):
        voice(env, kind="knowledge", transcript=f"规则说明 {i}")
    announcement = voice(env, kind="announcement")
    voice(env)
    preferences(c, scene_enabled=True)
    locate(env)
    items = inbox(c)
    assert len(items) == 2 and items[0]["payload"]["post_id"] == announcement
    assert len(c.get("/v1/voices?place_id=scenic", headers=auth()).json()["items"]) == 12


def test_manual_relisten_is_a_new_command_and_retries_do_not_restart(env):
    import uuid

    c, _, _ = setup_place(env)
    p = voice(env, kind="announcement")
    locate(env)
    initial = inbox(c)[0]
    ack(c, initial)
    command = {"client_request_id": str(uuid.uuid4())}
    again = c.post(f"/v1/voices/{p}/queue", headers=auth(), json=command).json()["delivery"]
    assert again["actionable"] and again["request_id"] != initial["request_id"]
    ack(c, again)
    retry = c.post(f"/v1/voices/{p}/queue", headers=auth(), json=command).json()["delivery"]
    assert retry["state"] == "played" and retry["request_id"] == again["request_id"]
    assert inbox(c) == []


def test_reply_preference_only_gates_automatic_playback(env):
    c, _, _, _ = env
    q = publish(c)
    a = answer(c, q, invitation(c))
    preferences(c, replies_enabled=False)
    assert not inbox(c)[0]["actionable"]
    manual = c.post(f"/v1/voices/{a}/queue", headers=auth(), json={}).json()["delivery"]
    assert manual["actionable"]


def test_voice_expiry_unknown_scopes_and_personal_observation_do_not_gain_authority(env):
    c, _, now = setup_place(env)
    p = voice(env, purpose="observation", valid_from=stamp(now[0]), valid_until=stamp(now[0] + 20))
    data = c.get("/v1/voices?place_id=scenic", headers=auth()).json()["items"][0]
    assert data["source_type"] == "personal"
    assert data["post_id"] == p
    assert not data.get("channel_label")
    too_long = draft(
        c,
        "bob",
        {
            "kind": "announcement",
            "transcript": "长期动态",
            "purpose": "notice",
            "target_place_id": "scenic",
            "channel_id": "parking",
            "valid_from": stamp(now[0]),
            "valid_until": stamp(now[0] + 86401),
        },
    )
    assert too_long.status_code == 422
    assert c.post(f"/v1/voices/{p}/withdraw", headers=auth(), json={}).status_code == 403
    assert c.get(f"/v1/audio/{p}").status_code == 401


def test_listening_config_isolated_and_scene_frequency_bounded(env):
    c, _, _ = setup_place(env)
    for i in range(5):
        voice(env, transcript=f"景色 {i}")
    preferences(c, scene_enabled=True, interests=["风景"])
    assert (
        c.get("/v1/me", headers=auth("bob")).json()["profile"]["listening"]["scene_enabled"]
        is False
    )
    locate(env)
    items = inbox(c)
    assert len(items) == 1
    ack(c, items[0], "skipped")
    assert inbox(c) == []
    preferences(c, muted_topics=["风景"])
    assert inbox(c) == []


def test_old_receipts_cannot_ack_new_visit_and_receipt_history_survives(env):
    c, s, now = setup_place(env)
    voice(env, kind="announcement")
    locate(env)
    d = inbox(c)[0]
    ack(c, d)
    locate(env, lat=31.3)
    inbox(c)
    now[0] += 121
    locate(env)
    new = inbox(c)[0]
    assert ack(c, d).status_code == 409
    assert ack(c, new).status_code == 200
    with s.scope() as db:
        receipts = db.execute(
            "SELECT visit_id FROM delivery_receipts WHERE recipient_id='alice' AND event='played'"
        ).fetchall()
        assert len(receipts) == 2 and receipts[0][0] != receipts[1][0]


def test_recording_date_is_preserved_or_explicitly_unknown(env):
    c, _, now = setup_place(env)
    old_recording = stamp(now[0] - 86400 * 30)
    known = voice(env, recorded_at=old_recording)
    unknown = voice(env)
    catalog = c.get("/v1/voices?place_id=scenic", headers=auth()).json()["items"]
    assert next(x for x in catalog if x["post_id"] == known)["recorded_at"] == old_recording
    without_date = next(x for x in catalog if x["post_id"] == unknown)
    assert without_date["recorded_at"] is None and without_date["collected_at"]
    assert "未提供" in without_date["context_note"]
    assert (
        draft(
            c,
            "bob",
            {
                "kind": "scene",
                "transcript": "未来录音",
                "target_place_id": "scenic",
                "purpose": "reflection",
                "recorded_at": stamp(now[0] + 60),
            },
        ).status_code
        == 422
    )


def test_pending_feed_cannot_queue_closed_invitation_context(env):
    c, s, _, _ = env
    q = publish(c)
    with s.scope() as db:
        feed = s.get_feed(db, "bob")
    answer(c, q, invitation(c))
    from app.errors import Problem

    with pytest.raises(Problem) as failure, s.scope() as db:
        s.queue_voice(db, "bob", feed["request_id"], feed["profile_revision"], q)
    assert failure.value.code == "CANDIDATE_EXPIRED"


def test_channel_questions_and_operator_replies_keep_source_and_do_not_broadcast(env):
    c, s, now = setup_place(env)
    locate(env, "bob")
    q = draft(
        c,
        metadata={
            "kind": "question",
            "transcript": "频道入口在哪？",
            "target_place_id": "scenic",
            "channel_id": "parking",
        },
    ).json()["post_id"]
    assert c.post(f"/v1/posts/{q}/confirm", headers=auth(), json={}).status_code == 200
    inv = invitation(c)
    assert inv["payload"]["operator_reply_channel"] == "parking"
    a = draft(
        c,
        "bob",
        {
            "kind": "answer",
            "transcript": "运营人员回答",
            "reply_to": q,
            "invitation_id": inv["id"],
            "channel_id": "parking",
        },
    ).json()["post_id"]
    assert c.post(f"/v1/posts/{a}/confirm", headers=auth("bob"), json={}).status_code == 200
    d = next(d for d in inbox(c) if d["payload"]["post_id"] == a)
    assert (
        d["payload"]["source_type"] == "operator" and d["payload"]["author_label"] == "停车场运营方"
    )
    assert d["payload"]["recipient_id"] == "alice" and d["payload"]["root_question_id"] == q
    assert c.get("/v1/voices?place_id=scenic", headers=auth()).json()["items"] == []
    fields = {
        "kind": "reply",
        "transcript": "访客追问",
        "reply_to": a,
        "root_question_id": q,
        "recipient_id": "bob",
    }
    assert draft(c, metadata={**fields, "channel_id": "parking"}).status_code == 403
    r = draft(c, metadata=fields).json()["post_id"]
    c.post(f"/v1/posts/{r}/confirm", headers=auth(), json={})
    final = draft(
        c,
        "bob",
        {
            "kind": "reply",
            "transcript": "运营继续回答",
            "reply_to": r,
            "root_question_id": q,
            "recipient_id": "alice",
            "channel_id": "parking",
        },
    ).json()["post_id"]
    assert c.post(f"/v1/posts/{final}/confirm", headers=auth("bob"), json={}).status_code == 200
    assert (
        next(d for d in inbox(c) if d["payload"]["post_id"] == final)["payload"]["source_type"]
        == "operator"
    )
    with s.scope() as db:
        db.execute("DELETE FROM channel_operators")
    assert not c.get(f"/v1/inbox/{d['id']}", headers=auth()).json()["delivery"]["actionable"]
    assert c.get(f"/v1/audio/{a}", headers=auth()).status_code == 404


def test_channel_question_scope_and_operator_answer_confirm_permission(env):
    c, s, _ = setup_place(env)
    assert (
        draft(
            c,
            metadata={
                "kind": "question",
                "transcript": "越界",
                "target_place_id": "other",
                "channel_id": "parking",
            },
        ).status_code
        == 403
    )
    locate(env, "bob")
    q = draft(
        c,
        metadata={
            "kind": "question",
            "transcript": "问题",
            "target_place_id": "scenic",
            "channel_id": "parking",
        },
    ).json()["post_id"]
    c.post(f"/v1/posts/{q}/confirm", headers=auth(), json={})
    inv = invitation(c)
    a = draft(
        c,
        "bob",
        {
            "kind": "answer",
            "transcript": "运营回复草稿",
            "reply_to": q,
            "invitation_id": inv["id"],
            "channel_id": "parking",
        },
    ).json()["post_id"]
    with s.scope() as db:
        db.execute("DELETE FROM channel_operators")
    assert c.post(f"/v1/posts/{a}/confirm", headers=auth("bob"), json={}).status_code == 403
