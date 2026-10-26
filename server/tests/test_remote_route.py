import pytest
from test_agent_network import Maps
from test_exchange import utter
from test_voice_first import MODAILY, setup_voice


def prepare_route(env):
    client, service, now, session = setup_voice(env)
    service.geo = Maps(now)
    calls = []
    driving = service.geo.driving

    def record(origin, destination):
        calls.append((origin, destination["id"]))
        return driving(origin, destination)

    service.geo.driving = record
    return client, service, now, session, calls


def route(client, session, now):
    response = utter(client, session, now, transcript="去金科路与高科中路交叉口路况怎样？")
    assert response.status_code == 200, response.text
    return response.json()["agent_response"]


def test_selected_remote_place_is_route_origin_without_claiming_phone_presence(env):
    client, service, now, session, calls = prepare_route(env)
    with service.scope() as db:
        before = dict(service.profile_row(db, "alice"))
        before_bob = dict(service.profile_row(db, "bob"))
    response = route(client, session, now)
    assert response["kind"] == "route", response
    assert calls == [((before["latitude"], before["longitude"]), "B0FFHPL61V")]
    assert "从你选择的远程地点模力社区出发" in response["text"]
    source = response["sources"][0]
    assert source["origin_place_id"] == MODAILY
    assert source["origin_source"] == "remote_place"
    assert "origin" not in source and "latitude" not in source and "longitude" not in source
    with service.scope() as db:
        assert dict(service.profile_row(db, "alice")) == before
        assert dict(service.profile_row(db, "bob")) == before_bob
        assert not db.execute("SELECT 1 FROM posts").fetchone()


@pytest.mark.parametrize(
    "changes",
    [
        {"sampled_at": 0},
        {"sampled_at": 1792720900},
        {"accuracy_m": 1000},
        {"latitude": None},
        {"source": "simulated"},
    ],
)
def test_invalid_or_stale_remote_origin_never_calls_route_provider(env, changes):
    client, service, now, session, calls = prepare_route(env)
    with service.scope() as db:
        for key, value in changes.items():
            db.execute(f"UPDATE profiles SET {key}=? WHERE user_id='alice'", (value,))
    response = route(client, session, now)
    assert response["kind"] == "location_required", response
    assert calls == []


def test_another_users_remote_place_cannot_supply_missing_origin(env):
    client, service, now, session, calls = prepare_route(env)
    with service.scope() as db:
        db.execute(
            "UPDATE profiles SET place_id=?,source='remote_place',latitude=31.1,longitude=121.1,"
            "sampled_at=?,accuracy_m=30 WHERE user_id='bob'",
            (MODAILY, now[0]),
        )
        db.execute("UPDATE profiles SET source='unavailable',latitude=NULL WHERE user_id='alice'")
    assert route(client, session, now)["kind"] == "location_required"
    assert calls == []
