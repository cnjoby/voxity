"""Spatial Q&A tests use synthetic coordinates and an explicit provider fixture, never real users."""

import json
from datetime import UTC, datetime
from uuid import uuid4

import httpx
import pytest
from conftest import answer, auth, draft

from app.geo import AMap
from app.storage import Database


def fix(now, latitude=31.2, longitude=121.5, **kw):
    return {
        "latitude": latitude,
        "longitude": longitude,
        "accuracy_m": 10,
        "coordinate_system": "gcj02",
        "source": "android",
        "sampled_at": datetime.fromtimestamp(now, UTC).isoformat(),
        **kw,
    }


def provider(request):
    if request.url.path.endswith("/convert"):
        return httpx.Response(200, json={"status": "1", "locations": "121.500000,31.200000"})
    if request.url.path.endswith("/text"):
        return httpx.Response(
            200,
            json={
                "status": "1",
                "pois": [{"id": "fixture-poi", "name": "测试路口", "location": "121.5,31.2"}],
            },
        )
    return httpx.Response(
        200,
        json={
            "status": "1",
            "regeocode": {
                "roads": [{"name": "测试道路"}],
                "pois": [{"id": "fixture-poi", "name": "测试路口", "location": "121.5,31.2"}],
            },
        },
    )


def locate(client, now, user="bob", **changes):
    rev = client.get("/v1/me", headers=auth(user)).json()["profile"]["revision"]
    return client.put(
        "/v1/me/location",
        headers=auth(user),
        json={"expected_revision": rev, "location": fix(now, **changes)},
    )


def prepare(env):
    client, service, now, _ = env
    service.geo = AMap("fixture-key", httpx.MockTransport(provider))
    r = client.get("/v1/places", params={"query": "测试路口"}, headers=auth())
    assert r.status_code == 200
    return client, service, now, r.json()["places"][0]["id"]


def question_at(client, target, recorded=None):
    return draft(
        client,
        metadata={
            "kind": "question",
            "transcript": "这里现在堵不堵？",
            "target_place_id": target,
            "direction": "unknown",
            "recorded_location": recorded,
        },
    )


def publish_at(client, target):
    r = question_at(client, target)
    assert r.status_code == 201, r.text
    qid = r.json()["post_id"]
    r = client.post(f"/v1/posts/{qid}/confirm", headers=auth(), json={})
    assert r.status_code == 200, r.text
    return qid


def test_nearby_loop_keeps_target_separate_and_private(env):
    c, service, now, target = prepare(env)
    # Alice is far away. Asking about a POI must never update her presence.
    assert locate(c, now[0], "alice", latitude=30).status_code == 200
    assert locate(c, now[0], "bob").status_code == 200
    qid = publish_at(c, target)
    inv = c.get("/v1/inbox", headers=auth("bob")).json()["items"][0]
    assert inv["payload"]["location_scope"] == "nearby"
    assert inv["payload"]["place_name"] == "测试路口"
    assert "latitude" not in json.dumps(inv)
    aid = answer(c, qid, inv)
    assert c.get(f"/v1/questions/{qid}", headers=auth()).json()["status"] == "answered"
    assert c.get(f"/v1/audio/{aid}", headers=auth()).status_code == 200
    delivery = c.get("/v1/inbox", headers=auth()).json()["items"][0]
    assert (
        c.post(
            f"/v1/inbox/{delivery['id']}/ack",
            headers=auth(),
            json={"event": "played", "request_id": delivery["request_id"]},
        ).status_code
        == 200
    )
    assert not c.get("/v1/inbox", headers=auth()).json()["items"]
    assert (
        c.get("/v1/me", headers=auth()).json()["profile"]["presence"]["location"]["latitude"] == 30
    )


@pytest.mark.parametrize(
    "kwargs", [dict(latitude=31.3), dict(accuracy_m=500), dict(source="android_mock")]
)
def test_far_imprecise_mock_do_not_receive_invites(env, kwargs):
    c, _, now, target = prepare(env)
    assert locate(c, now[0], **kwargs).status_code == 200
    publish_at(c, target)
    assert not c.get("/v1/inbox", headers=auth("bob")).json()["items"]


def test_location_expiry_uses_sample_time_not_request_time(env):
    c, service, now, target = prepare(env)
    assert locate(c, now[0] - 55).status_code == 200
    publish_at(c, target)
    assert c.get("/v1/inbox", headers=auth("bob")).json()["items"]
    now[0] += 6
    assert not c.get("/v1/inbox", headers=auth("bob")).json()["items"]
    assert locate(c, now[0] - 61).status_code == 422
    assert locate(c, now[0] + 20).status_code == 422


def test_arriving_user_can_receive_unseen_open_question_and_quiet_prevents_it(env):
    c, _, now, target = prepare(env)
    qid = publish_at(c, target)
    assert not c.get("/v1/inbox", headers=auth("bob")).json()["items"]
    p = c.get("/v1/me", headers=auth("bob")).json()["profile"]
    assert (
        c.put(
            "/v1/me/context",
            headers=auth("bob"),
            json={
                "expected_revision": p["revision"],
                "mode": "quiet",
                "assist_enabled": True,
                "focus": "安静",
            },
        ).status_code
        == 200
    )
    assert locate(c, now[0]).status_code == 200
    assert not c.get("/v1/inbox", headers=auth("bob")).json()["items"]
    p = c.get("/v1/me", headers=auth("bob")).json()["profile"]
    assert (
        c.put(
            "/v1/me/context",
            headers=auth("bob"),
            json={
                "expected_revision": p["revision"],
                "mode": "listen",
                "assist_enabled": True,
                "focus": "附近互助",
            },
        ).status_code
        == 200
    )
    items = c.get("/v1/inbox", headers=auth("bob")).json()["items"]
    assert items[0]["question_id"] == qid
    assert c.get("/v1/inbox", headers=auth("bob")).json()["items"] == items


def test_movement_or_permission_revocation_invalidates_invitation(env):
    c, _, now, target = prepare(env)
    locate(c, now[0])
    qid = publish_at(c, target)
    inv = c.get("/v1/inbox", headers=auth("bob")).json()["items"][0]
    locate(c, now[0], latitude=32)
    assert not c.get(f"/v1/inbox/{inv['id']}", headers=auth("bob")).json()["delivery"]["actionable"]
    r = draft(
        c,
        "bob",
        {"kind": "answer", "transcript": "现在堵", "reply_to": qid, "invitation_id": inv["id"]},
    )
    assert r.status_code == 409
    rev = c.get("/v1/me", headers=auth("bob")).json()["profile"]["revision"]
    r = c.put(
        "/v1/me/location", headers=auth("bob"), json={"expected_revision": rev, "location": None}
    )
    assert r.json()["profile"]["presence"]["source"] == "unavailable"


def test_recording_snapshot_survives_retry_and_presence_moves(env):
    c, service, now, target = prepare(env)
    point = fix(now[0])
    request = {
        "client_request_id": str(uuid4()),
        "kind": "question",
        "transcript": "堵不堵？",
        "target_place_id": target,
        "recorded_location": point,
    }
    r = draft(c, metadata=request)
    assert r.status_code == 201
    assert draft(c, metadata=request).status_code == 200
    request["recorded_location"] = fix(now[0], latitude=30)
    assert draft(c, metadata=request).status_code == 409
    with service.scope() as db:
        p = service.post(db, r.json()["post_id"])
        assert json.loads(p["recorded_location"])["latitude"] == point["latitude"]


def test_provider_error_is_actionable_and_never_leaks_key(env):
    c, s, now, _ = env
    s.geo = AMap(
        "private-key",
        httpx.MockTransport(
            lambda r: httpx.Response(200, json={"status": "0", "info": "private-key"})
        ),
    )
    response = locate(c, now[0])
    assert response.status_code == 503
    assert response.json()["error"]["retryable"]
    assert "private-key" not in response.text
    s.geo = AMap("")
    assert locate(c, now[0]).json()["error"]["code"] == "POI_NOT_CONFIGURED"
    assert c.get("/v1/places", params={"query": "测试路口"}).status_code == 401


def test_wgs_conversion_happens_once_before_reverse_and_direction_not_invented(env):
    c, s, now, _ = env
    calls = []

    def capture(request):
        calls.append(request)
        return provider(request)

    s.geo = AMap("fixture", httpx.MockTransport(capture))
    r = locate(c, now[0], coordinate_system="wgs84", latitude=31.198, longitude=121.496)
    assert r.status_code == 200
    assert calls[0].url.params["coordsys"] == "gps"
    assert calls[1].url.params["location"] == "121.500000,31.200000"
    assert r.json()["profile"]["presence"]["direction"] == "unknown"
    assert r.json()["places"][0]["label"] == "测试道路附近"
    assert r.json()["profile"]["presence"]["location"]["coordinate_system"] == "wgs84"


def test_additive_migration_is_idempotent_and_preserves_rows(env):
    c, s, _, _ = env
    before = c.get("/v1/me", headers=auth()).json()["profile"]
    Database(s.settings.data_dir)
    Database(s.settings.data_dir)
    assert c.get("/v1/me", headers=auth()).json()["profile"] == before


def test_prompted_invitation_remains_available_for_voluntary_reply(env):
    c, _, now, target = prepare(env)
    locate(c, now[0])
    qid = publish_at(c, target)
    inv = c.get("/v1/inbox", headers=auth("bob")).json()["items"][0]
    c.post(
        f"/v1/inbox/{inv['id']}/ack",
        headers=auth("bob"),
        json={"event": "alerted", "request_id": inv["request_id"]},
    )
    still = c.get("/v1/inbox", headers=auth("bob")).json()["items"][0]
    assert still["state"] == "prompted" and still["actionable"]
    answer(c, qid, still)


def test_legacy_draft_retry_fingerprint_does_not_change_when_optional_location_added(env):
    from conftest import wav

    from app.storage import digest

    c, s, _, _ = env
    request = {
        "client_request_id": str(uuid4()),
        "kind": "question",
        "transcript": "堵不堵？",
        "target_place_id": "zhonghuan_jinke",
        "direction": "demo_exit",
    }
    r = draft(c, metadata=request)
    assert r.status_code == 201
    old_hash = digest(json.dumps(request, sort_keys=True, ensure_ascii=False).encode() + wav())
    with s.scope() as db:
        assert s.post(db, r.json()["post_id"])["fingerprint"] == old_hash
    assert draft(c, metadata=request).status_code == 200
