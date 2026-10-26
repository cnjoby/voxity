from datetime import datetime

from conftest import auth, draft, invitation, publish, set_context
from test_voice_first import MODAILY, setup_voice
from test_voice_matching import ack, inbox, locate, setup_place, voice

from app.day_validity import migrate_today
from app.service import stamp


def at(value):
    return datetime.fromisoformat(value).timestamp()


def test_question_and_invitation_last_until_shanghai_midnight(env):
    c, service, now, settings = env
    settings.information_valid_until_day_end = True
    now[0] = at("2026-10-26T23:50:00+08:00")
    set_context(c, "alice")
    set_context(c, "bob")
    pid = publish(c)
    inv = invitation(c)
    end = "2026-10-26T16:00:00Z"
    assert c.get(f"/v1/questions/{pid}", headers=auth()).json()["expires_at"] == end
    assert inv["expires_at"] == end
    now[0] += 180
    set_context(c, "bob")
    assert c.get(f"/v1/inbox/{inv['id']}", headers=auth("bob")).json()["delivery"]["actionable"]
    now[0] = at("2026-10-27T00:00:00+08:00")
    assert c.get(f"/v1/questions/{pid}", headers=auth()).json()["status"] == "expired"


def test_observation_expires_on_publication_day_and_timestamp_is_not_recording_time(env):
    c, service, now = setup_place(env)
    service.settings.information_valid_until_day_end = True
    now[0] = at("2026-10-26T08:05:00+08:00")
    pid = voice(
        env,
        purpose="observation",
        recorded_at=stamp(now[0] - 30),
        valid_from=stamp(now[0] - 30),
        valid_until=stamp(now[0] + 300),
    )
    now[0] += 7200
    with service.scope() as db:
        post = service.post(db, pid)
        assert service.voice_live(db, post)
        payload = service.voice_payload(db, post)
        assert payload["valid_until"] == "2026-10-26T16:00:00Z"
        assert payload["playback_intro"] == "2小时前发布。"
        assert payload["recorded_at"] != payload["published_at"]
    now[0] = at("2026-10-27T00:00:00+08:00")
    with service.scope() as db:
        assert not service.voice_live(db, service.post(db, pid))


def test_unplayed_notice_recovers_after_short_scope_loss_but_played_notice_does_not(env):
    c, service, now = setup_place(env)
    locate(env)
    pid = voice(env, kind="announcement")
    original = next(d for d in inbox(c) if d["payload"]["post_id"] == pid)
    assert ack(c, original, "received").status_code == 200
    locate(env, lat=32.2)
    inbox(c)
    now[0] += 10
    locate(env)
    recovered = next(d for d in inbox(c) if d["payload"]["post_id"] == pid)
    assert recovered["actionable"] and recovered["request_id"] != original["request_id"]
    assert ack(c, recovered).status_code == 200
    locate(env, lat=32.2)
    inbox(c)
    now[0] += 10
    locate(env)
    assert not any(d["payload"]["post_id"] == pid for d in inbox(c))


def test_today_migration_preserves_standing_notice_receipts_and_closed_questions(env):
    c, service, now = setup_place(env)
    set_context(c, "alice")
    set_context(c, "bob")
    old = publish(c)
    now[0] += 121
    latest = publish(c)
    notice = voice(
        env,
        kind="announcement",
        validity_policy="until_withdrawn",
        valid_until=None,
        source_note="测试长期入园须知",
    )
    now[0] += 121
    c.get("/v1/me", headers=auth())
    service.settings.information_valid_until_day_end = True
    result = migrate_today(service)
    assert result["reopened_questions"] == [latest]
    assert notice not in result["updated_posts"]
    with service.scope() as db:
        assert service.post(db, old)["question_status"] == "expired"
        assert service.post(db, latest)["question_status"] == "waiting"
        assert service.post(db, notice)["expires_at"] is None
    assert migrate_today(service)["already_applied"]


def test_migration_retry_recovers_duration_invitation_created_during_rollback(env):
    c, service, now, settings = env
    settings.information_valid_until_day_end = True
    set_context(c, "alice")
    set_context(c, "bob")
    publish(c)
    inv = invitation(c)
    migrate_today(service)
    with service.store.transaction() as db:
        db.execute(
            "UPDATE deliveries SET expires_at=?,state='expired',reason_code='TASK_EXPIRED' WHERE id=?",
            (now[0] - 1, inv["id"]),
        )
    result = migrate_today(service)
    assert result["recovered_invitations"] == [inv["id"]]
    refreshed = c.get(f"/v1/inbox/{inv['id']}", headers=auth("bob")).json()["delivery"]
    assert refreshed["actionable"] and refreshed["request_id"] != inv["request_id"]
    assert migrate_today(service)["recovered_invitations"] == []


def test_later_arrival_can_hear_an_earlier_question_still_valid_today(env):
    c, service, now, settings = env
    settings.information_valid_until_day_end = True
    setup_voice(env, "bob")
    response = draft(
        c,
        "bob",
        {"kind": "question", "transcript": "模力社区现在可以停车吗？", "target_place_id": MODAILY},
    )
    assert response.status_code == 201, response.text
    pid = response.json()["post_id"]
    assert c.post(f"/v1/posts/{pid}/confirm", headers=auth("bob"), json={}).status_code == 200
    now[0] += 300
    _, _, _, session = setup_voice(env)
    response = c.get(f"/v1/exchange/sessions/{session['id']}/inbox", headers=auth())
    assert response.status_code == 200, response.text
    assert any(d["payload"]["post_id"] == pid and d["actionable"] for d in response.json()["items"])
