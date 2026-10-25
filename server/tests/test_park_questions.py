import pytest
from conftest import auth, wav
from test_exchange import utter
from test_voice_first import heard, setup_voice

PARK = "B0FFJEOSPR"
QINIU = "B0FFJSBSGR"
MODAILY = "B0K2KHYN77"


def relocate(client, session, user, place_id):
    p = client.get("/v1/me", headers=auth(user)).json()["profile"]
    r = client.put(
        "/v1/me/remote-place",
        headers=auth(user),
        json={"expected_revision": p["revision"], "place_id": place_id},
    )
    assert r.status_code == 200, r.text
    return client.get(f"/v1/exchange/sessions/{session['id']}", headers=auth(user)).json()[
        "session"
    ]


@pytest.mark.parametrize(
    "question,origin,recipient",
    [
        ("帮我问问人工智能岛，现在还有车位吗？", PARK, QINIU),
        ("帮我问问七牛，现在还有车位吗？", QINIU, PARK),
    ],
)
def test_park_and_child_keep_distinct_ids_but_exchange_actual_question_audio(
    env, question, origin, recipient
):
    c, s, n, alice = setup_voice(env)
    _, _, _, bob = setup_voice(env, "bob")
    alice = relocate(c, alice, "alice", origin)
    bob = relocate(c, bob, "bob", recipient)
    q = utter(c, alice, n, transcript=question).json()
    assert q["status"] == "published", q
    expected = PARK if "人工智能岛" in question else QINIU
    assert q["target_place"]["id"] == expected
    assert q["match_scope"] == {"kind": "park", "place_id": PARK, "label": "张江人工智能岛"}
    assert q["dispatch"]["device_received"] == 0
    feed = c.get(f"/v1/exchange/sessions/{bob['id']}/inbox", headers=auth("bob"))
    assert feed.status_code == 200, feed.text
    item = next(i for i in feed.json()["items"] if i["payload"]["post_id"] == q["post_id"])
    assert c.get(item["payload"]["audio_url"], headers=auth("bob")).content == wav()
    heard(c, bob, q["post_id"], "bob")
    answer = utter(c, bob, n, "bob", transcript="东门这边还有三个车位。").json()
    assert answer["status"] == "published", answer
    assert c.get("/v1/audio/" + answer["post_id"], headers=auth()).content == wav()
    with s.scope() as db:
        assert s.post(db, q["post_id"])["target_place_id"] == expected
        assert s.profile_row(db, "alice")["place_id"] == origin
        assert s.profile_row(db, "bob")["place_id"] == recipient


def test_park_relation_does_not_include_modaily_or_bypass_quiet_or_freshness(env):
    c, s, n, alice = setup_voice(env)
    _, _, _, bob = setup_voice(env, "bob")
    alice = relocate(c, alice, "alice", QINIU)
    q = utter(c, alice, n, transcript="帮我问问人工智能岛还有车位吗？").json()
    assert q["status"] == "published"
    with s.scope() as db:
        question = s.post(db, q["post_id"])
        assert s.eligibility(s.profile_row(db, "bob"), question, db) == "LOCATION_MISMATCH"
        assert not s.shared_park(db, MODAILY, QINIU)
        assert s.information_place_ids(db, QINIU) == [
            QINIU
        ]  # unrelated content scope stays precise
    bob = relocate(c, bob, "bob", PARK)
    with s.scope() as db:
        db.execute("UPDATE profiles SET mode='quiet' WHERE user_id='bob'")
        assert s.eligibility(s.profile_row(db, "bob"), s.post(db, q["post_id"]), db) == "MODE_QUIET"
        db.execute(
            "UPDATE profiles SET mode='listen',sampled_at=? WHERE user_id='bob'", (n[0] - 1000,)
        )
        assert (
            s.eligibility(s.profile_row(db, "bob"), s.post(db, q["post_id"]), db)
            == "PRESENCE_EXPIRED"
        )


@pytest.mark.parametrize("yes", ["好，你帮我问一下。", "好啊，你帮我问一问。", "请帮我问问吧"])
def test_natural_confirmation_uses_original_place_and_original_audio(env, yes):
    c, s, n, alice = setup_voice(env)
    first = utter(c, alice, n, transcript="模力社区现在还能停车吗？").json()
    assert first["agent_response"]["kind"] == "human_offer"
    result = utter(c, alice, n, transcript=yes).json()
    assert result["status"] == "published", result
    assert result["target_place"]["id"] == MODAILY
    with s.scope() as db:
        assert s.post(db, result["post_id"])["transcript"] == "模力社区现在还能停车吗？"
