import json
from datetime import UTC, datetime

from conftest import answer, auth, draft


def test_three_current_shortcuts_switch_to_device_fix_and_back_without_moving_peer(env):
    c, service, now, settings = env
    settings.remote_place_users = {"bob": "B0K2KHYN77"}
    before_peer = c.get("/v1/me", headers=auth("bob")).json()["profile"]
    me = c.get("/v1/me", headers=auth()).json()
    assert [p["id"] for p in me["place_shortcuts"]] == ["B0FFJSBSGR", "B0FFHPL61V", "B0K2KHYN77"]
    profile = me["profile"]
    for place in me["place_shortcuts"]:
        r = c.put(
            "/v1/me/remote-place",
            headers=auth(),
            json={"expected_revision": profile["revision"], "place_id": place["id"]},
        )
        assert r.status_code == 200, r.text
        profile = r.json()["profile"]
        assert profile["presence"]["place_id"] == place["id"]
        assert profile["presence"]["source"] == "remote_place"
        assert profile["presence"]["location"]["latitude"] == place["latitude"]
    cleared = c.put(
        "/v1/me/location",
        headers=auth(),
        json={"expected_revision": profile["revision"], "location": None},
    )
    assert cleared.status_code == 200
    profile = cleared.json()["profile"]
    assert profile["presence"]["source"] == "unavailable"
    device_place = me["place_shortcuts"][1]
    service.geo.resolve = lambda fix: {
        "latitude": fix.latitude,
        "longitude": fix.longitude,
        "places": [device_place],
    }
    located = c.put(
        "/v1/me/location",
        headers=auth(),
        json={
            "expected_revision": profile["revision"],
            "location": {
                "latitude": device_place["latitude"],
                "longitude": device_place["longitude"],
                "accuracy_m": 10,
                "coordinate_system": "gcj02",
                "source": "android",
                "sampled_at": datetime.fromtimestamp(now[0], UTC).isoformat(),
            },
        },
    )
    assert located.status_code == 200, located.text
    profile = located.json()["profile"]
    assert profile["presence"]["source"] == "android" and not profile["presence"].get(
        "remote", False
    )
    back = c.put(
        "/v1/me/remote-place",
        headers=auth(),
        json={"expected_revision": profile["revision"], "place_id": "B0K2KHYN77"},
    )
    assert back.status_code == 200 and back.json()["profile"]["presence"]["remote"]
    assert c.get("/v1/me", headers=auth("bob")).json()["profile"] == before_peer


def test_pinned_registered_remote_places_exchange_audio_without_fake_gps(env):
    c, service, _, settings = env
    settings.remote_place_users = {"alice": "B0K2KHYN77", "bob": "B0K2KHYN77"}
    alice = c.get("/v1/me", headers=auth()).json()["profile"]
    bob = c.get("/v1/me", headers=auth("bob")).json()["profile"]
    assert alice["presence"]["remote"]
    assert alice["presence"]["location"]["latitude"] == 31.184698
    assert not settings.allow_mock_location
    # A server pin is a remote place, even when the device has no physical fix.
    r = c.put(
        "/v1/me/location",
        headers=auth(),
        json={"expected_revision": alice["revision"], "location": None},
    )
    assert r.status_code == 200
    assert r.json()["profile"]["presence"]["source"] == "remote_place"
    q = draft(
        c,
        metadata={
            "kind": "question",
            "transcript": "模力社区可以停车吗？",
            "target_place_id": "B0K2KHYN77",
            "direction": "unknown",
        },
    ).json()["post_id"]
    assert c.post(f"/v1/posts/{q}/confirm", headers=auth(), json={}).status_code == 200
    inv = c.get("/v1/inbox", headers=auth("bob")).json()["items"][0]
    assert inv["actionable"] and "远程地点" in inv["payload"]["author_label"]
    aid = answer(c, q, inv)
    assert c.get(f"/v1/questions/{q}", headers=auth()).json()["status"] == "answered"
    assert c.get(f"/v1/audio/{aid}", headers=auth()).status_code == 200
    assert c.get("/v1/me", headers=auth("bob")).json()["profile"]["focus"] == bob["focus"]
    with service.scope() as db:
        assert json.loads(service.post(db, aid)["recorded_location"])["source"] == "remote_place"


def test_explicit_point_selection_is_per_user_and_preserves_other_settings(env):
    c, _, _, _ = env
    before = c.get("/v1/me", headers=auth()).json()["profile"]
    bob = c.get("/v1/me", headers=auth("bob")).json()["profile"]
    r = c.put(
        "/v1/me/remote-place",
        headers=auth(),
        json={"expected_revision": before["revision"], "place_id": "B0K2KHYN77"},
    )
    assert r.status_code == 200
    after = r.json()["profile"]
    assert after["presence"]["source"] == "remote_place"
    for key in ["mode", "focus", "assist_enabled", "listening"]:
        assert before[key] == after[key]
    assert c.get("/v1/me", headers=auth("bob")).json()["profile"] == bob
    assert (
        c.put(
            "/v1/me/remote-place",
            headers=auth(),
            json={"expected_revision": after["revision"], "place_id": "unregistered"},
        ).status_code
        == 422
    )
    assert (
        c.put(
            "/v1/me/remote-place",
            headers=auth(),
            json={"expected_revision": after["revision"], "place_id": "demo:modaily"},
        ).status_code
        == 422
    )
    assert (
        c.put(
            "/v1/me/simulated-place",
            headers=auth(),
            json={"expected_revision": after["revision"], "place_id": "demo:modaily"},
        ).status_code
        == 404
    )


def test_explicit_client_selection_and_exit_supersede_temporary_live_pin(env):
    c, _, _, settings = env
    settings.remote_place_users = {"alice": "B0K2KHYN77"}
    p = c.get("/v1/me", headers=auth()).json()["profile"]
    r = c.put(
        "/v1/me/remote-place",
        headers=auth(),
        json={"expected_revision": p["revision"], "place_id": "B0FFJSBSGR"},
    )
    assert r.status_code == 200
    p = c.get("/v1/me", headers=auth()).json()["profile"]
    assert p["presence"]["place_id"] == "B0FFJSBSGR"
    r = c.put(
        "/v1/me/location",
        headers=auth(),
        json={"expected_revision": p["revision"], "location": None},
    )
    assert r.status_code == 200
    assert c.get("/v1/me", headers=auth()).json()["profile"]["presence"]["source"] == "unavailable"
    assert (
        c.put(
            "/v1/me/remote-place", json={"expected_revision": 1, "place_id": "B0K2KHYN77"}
        ).status_code
        == 401
    )


def test_remote_source_cannot_be_hidden_by_a_client_recording_location(env):
    c, service, now, settings = env
    settings.remote_place_users = {"alice": "B0K2KHYN77"}
    c.get("/v1/me", headers=auth())
    r = draft(
        c,
        metadata={
            "kind": "question",
            "transcript": "模力社区测试",
            "target_place_id": "B0K2KHYN77",
            "recorded_location": {
                "latitude": 31.2,
                "longitude": 121.5,
                "accuracy_m": 30,
                "source": "android",
                "sampled_at": datetime.fromtimestamp(now[0], UTC).isoformat(),
            },
        },
    )
    assert r.status_code == 201
    with service.scope() as db:
        post = service.post(db, r.json()["post_id"])
        assert json.loads(post["recorded_location"])["source"] == "remote_place"
        assert "远程地点" in service.audio_payload(post)["author_label"]
