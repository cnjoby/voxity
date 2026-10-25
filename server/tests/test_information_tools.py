import httpx
import pytest

from app.errors import Problem
from app.geo import AMap

PLACE = {"latitude": 31.18, "longitude": 121.60}


@pytest.mark.parametrize("target", ["2026-10-27", "2026-10-30"])
def test_forecast_matches_date_instead_of_list_position(target):
    calls = []

    def provider(request):
        calls.append(request)
        if request.url.path.endswith("regeo"):
            return httpx.Response(
                200, json={"status": "1", "regeocode": {"addressComponent": {"adcode": "310115"}}}
            )
        assert request.url.params["extensions"] == "all"
        return httpx.Response(
            200,
            json={
                "status": "1",
                "forecasts": [
                    {
                        "city": "浦东新区",
                        "reporttime": "2026-10-26 08:00:00",
                        "casts": [
                            {
                                "date": d,
                                "dayweather": "小雨",
                                "nightweather": "阴",
                                "daytemp": "22",
                                "nighttemp": "17",
                            }
                            for d in ("2026-10-28", "2026-10-27", "2026-10-26")
                        ],
                    }
                ],
            },
        )

    g = AMap("private-key", httpx.MockTransport(provider))
    if target == "2026-10-30":
        with pytest.raises(Problem) as err:
            g.forecast(PLACE, target)
        assert err.value.code == "FORECAST_UNAVAILABLE"
    else:
        result = g.forecast(PLACE, target)
        assert result["date"] == target and result["weather_type"] == "forecast"
        assert result["adcode"] == "310115" and "private-key" not in str(result)
    assert len(calls) == 2


def test_weather_uses_real_district_code_and_preserves_observation_time():
    calls = []

    def provider(request):
        calls.append(request)
        if request.url.path.endswith("regeo"):
            return httpx.Response(
                200, json={"status": "1", "regeocode": {"addressComponent": {"adcode": "310115"}}}
            )
        return httpx.Response(
            200,
            json={
                "status": "1",
                "lives": [
                    {
                        "city": "浦东新区",
                        "weather": "阴",
                        "temperature": "21",
                        "reporttime": "2026-10-25 10:33:04",
                    }
                ],
            },
        )

    result = AMap("private-test-key", httpx.MockTransport(provider)).weather(PLACE)
    assert calls[0].url.params["location"] == "121.600000,31.180000"
    assert calls[1].url.params["city"] == "310115"
    assert result["scope"] == "district" and result["reporttime"] == "2026-10-25 10:33:04"
    assert "private-test-key" not in str(result)


def test_route_retains_unknown_segments_and_never_relabels_them_clear():
    def provider(request):
        assert request.url.params["extensions"] == "all"
        assert request.url.params["origin"] == "121.500000,31.200000"
        return httpx.Response(
            200,
            json={
                "status": "1",
                "route": {
                    "paths": [
                        {
                            "duration": "1200",
                            "distance": "3000",
                            "steps": [
                                {
                                    "tmcs": [
                                        {"distance": "200", "status": "畅通"},
                                        {"distance": "2800", "status": "未知"},
                                    ]
                                }
                            ],
                        }
                    ]
                },
            },
        )

    result = AMap("key", httpx.MockTransport(provider)).driving((31.2, 121.5), PLACE)
    assert result["traffic_m"] == {"畅通": 200, "未知": 2800}
    assert result["duration_seconds"] == 1200


@pytest.mark.parametrize(
    "payload", [None, {}, {"status": "1"}, {"status": "0", "info": "private-key rejected"}]
)
def test_malformed_information_does_not_leak_provider_body_or_fabricate_answer(payload):
    g = AMap("private-key", httpx.MockTransport(lambda request: httpx.Response(200, json=payload)))
    for action in (lambda: g.weather(PLACE), lambda: g.driving((31.2, 121.5), PLACE)):
        with pytest.raises(Problem) as problem:
            action()
        assert "private-key" not in str(problem.value.body())
