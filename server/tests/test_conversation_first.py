"""Owner dialogue and device delivery must survive uncertain relevance labels."""

import asyncio
import json
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import pytest
from conftest import auth
from test_exchange import utter
from test_voice_first import MODAILY, heard, setup_voice

from app.errors import Problem

CITY = {
    "id": "B00156EAZH",
    "label": "上海市",
    "latitude": 31.23,
    "longitude": 121.47,
    "kind": "poi",
}


class Forecasts:
    def __init__(self, now):
        self.now, self.searches, self.forecasts = now, [], []
        self.fail = False

    def search(self, query):
        self.searches.append(query)
        return [CITY] if query in ("上海", "上海市") else []

    def forecast(self, place, date):
        self.forecasts.append((place["id"], date))
        if self.fail:
            raise Problem(503, "FORECAST_UNAVAILABLE", "unavailable")
        return {
            "city": "浦东新区",
            "date": date,
            "dayweather": "小雨",
            "nightweather": "阴",
            "daytemp": "22",
            "nighttemp": "17",
            "provider": "amap",
            "weather_type": "forecast",
            "scope": "district",
            "reporttime": datetime.fromtimestamp(
                self.now[0], ZoneInfo("Asia/Shanghai")
            ).isoformat(),
        }


def day(now, offset):
    return (
        datetime.fromtimestamp(now[0], ZoneInfo("Asia/Shanghai")).date() + timedelta(days=offset)
    ).isoformat()


def test_weather_dates_use_current_place_then_keep_explicit_city_across_followups(env):
    c, s, n, a = setup_voice(env)
    s.geo = g = Forecasts(n)
    for text, place, offset in [
        ("明天天气怎样", MODAILY, 1),
        ("上海明天会下雨吗？", CITY["id"], 1),
        ("那后天呢？", CITY["id"], 2),
        ("那会下雨吗？", CITY["id"], 2),
    ]:
        result = utter(c, a, n, transcript=text).json()
        assert result["status"] == "responded" and result["action"] == "agent", result
        source = result["agent_response"]["sources"][0]
        assert source["date"] == day(n, offset) and source["place_id"] == place
        assert g.forecasts[-1] == (place, day(n, offset))
    assert g.searches == ["上海"]
    with s.scope() as db:
        assert not db.execute("SELECT 1 FROM posts").fetchone()


def test_weather_city_clarification_preserves_tomorrow_and_never_uses_today(env):
    c, s, n, a = setup_voice(env)
    s.geo = g = Forecasts(n)
    switched = utter(c, a, n, transcript="换回手机真实位置").json()
    a = switched["session"]
    first = utter(c, a, n, transcript="明天天气怎样").json()
    assert first["status"] == "clarifying" and "哪个城市" in first["agent_response"]["text"]
    s.voice_intent.force = "query"
    result = utter(c, a, n, transcript="上海").json()
    assert result["agent_response"]["sources"][0]["date"] == day(n, 1), result
    assert g.searches == ["上海"]


def test_chat_and_weather_escape_old_poi_clarification_without_publishing(env):
    c, s, n, a = setup_voice(env)
    s.geo = g = Forecasts(n)
    utter(c, a, n, transcript="帮我问问某个未知公园还有停车位吗")
    for text in ("我想闲聊", "好啊，我想听你聊", "你会什么", "太阳为什么发光"):
        s.voice_intent.force = "query"
        result = utter(c, a, n, transcript=text).json()
        assert result["action"] == "private_chat", result
    result = utter(c, a, n, transcript="上海明天会下雨吗").json()
    assert result["agent_response"]["kind"] == "weather", result
    g.fail = True
    result = utter(c, a, n, transcript="那后天呢").json()
    assert result["status"] == "responded" and result["agent_response"]["kind"] == "unavailable"
    with s.scope() as db:
        for table in ("posts", "deliveries", "chat_requests", "agent_pending"):
            assert db.execute(f"SELECT count(*) FROM {table}").fetchone()[0] == 0


def test_explicit_reply_does_not_wait_for_intent_model(env):
    c, s, n, a = setup_voice(env)
    _, _, _, b = setup_voice(env, "bob")
    question = utter(c, b, n, "bob", transcript="帮我问问模力社区现在有车位吗").json()
    c.get(f"/v1/exchange/sessions/{a['id']}/inbox", headers=auth())
    heard(c, a, question["post_id"])

    class Unavailable:
        async def route(self, state):
            raise AssertionError("Explicit reply must not wait for a model")

    s.voice_intent = Unavailable()
    result = utter(c, a, n, transcript="回复他，有，东门还有三个车位").json()
    assert result["status"] == "published" and result["action"] == "answer", result


def test_weather_after_hearing_question_is_private_but_weather_observation_can_reply(env):
    c, s, n, a = setup_voice(env)
    _, _, _, b = setup_voice(env, "bob")
    s.geo = Forecasts(n)
    q = utter(c, b, n, "bob", transcript="帮我问问模力社区还有车位吗").json()
    c.get(f"/v1/exchange/sessions/{a['id']}/inbox", headers=auth())
    heard(c, a, q["post_id"])
    reply = utter(c, a, n, transcript="还有几个车位，天气也还好。").json()
    assert reply["status"] == "published" and reply["action"] == "answer", reply
    result = utter(c, a, n, transcript="明天天气怎样").json()
    assert result["agent_response"]["sources"][0]["date"] == day(n, 1), result


@pytest.mark.parametrize("model_action", ["ignore", "defer"])
def test_two_questions_in_one_minute_play_without_attention_or_listening_approval(
    env, model_action
):
    from test_attention_flow import Decider

    from app.attention_worker import AttentionWorker

    c, s, n, a = setup_voice(env)
    _, _, _, b = setup_voice(env, "bob")
    s.settings.decision_enabled = True
    s.decider = Decider(model_action)

    class NoListeningApproval:
        async def decide(self, state, listening=False):
            raise AssertionError("A valid question must not wait for another model")

    s.exchange_decider = NoListeningApproval()
    for i in range(2):
        q = utter(c, a, n, transcript="帮我问问模力社区现在有车位吗").json()
        assert q["status"] == "published", q
        assert asyncio.run(AttentionWorker(s).once())
        invitation = c.get("/v1/inbox", headers=auth("bob")).json()["items"][0]
        assert invitation["payload"]["attention"]["model_action"] == model_action
        received = c.get(f"/v1/exchange/sessions/{b['id']}/inbox", headers=auth("bob")).json()[
            "items"
        ][0]
        assert received["payload"]["post_id"] == q["post_id"]
        assert c.get(received["payload"]["audio_url"], headers=auth("bob")).status_code == 200
        c.post(
            f"/v1/inbox/{received['id']}/ack",
            headers=auth("bob"),
            json={"request_id": received["request_id"], "event": "played"},
        )
        heard(c, b, q["post_id"], "bob")
        answer = utter(c, b, n, "bob", transcript="有，东门还有三个车位").json()
        assert answer["status"] == "published", answer
        if i == 0:
            utter(c, a, n, transcript="已经解决了")
    assert len(s.decider.calls) == 2
    with s.scope() as db:
        assert all(
            json.loads(r[0])["decision_role"] == "annotation"
            for r in db.execute(
                "SELECT voice_json FROM deliveries WHERE recipient_id='bob' AND type='audio'"
            )
        )
