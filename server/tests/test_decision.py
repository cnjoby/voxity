import httpx
import pytest

from app.decision import BambooDecision


def result(**overrides):
    return {
        "answers": {
            "attention": {
                "type": "choice",
                "choice": "notify",
                "confidence": 0.9,
                "none_probability": 0.01,
                **overrides,
            }
        }
    }


def test_native_decision_request_contains_only_focus_and_transcript():
    calls = []

    def reply(request):
        import json

        calls.append(json.loads(request.content))
        assert request.url.path == "/v1/decisions"
        assert request.headers["Authorization"] == "Bearer test-key"
        return httpx.Response(200, json=result())

    model = BambooDecision("test-key", transport=httpx.MockTransport(reply))
    decision = model.evaluate("关注路况", "这里堵不堵？")
    assert decision.action == "notify"
    assert calls[0]["think_mode"] == "never"
    assert set(calls[0]["state"]) == {"user_focus", "candidate_transcript"}
    assert "audio" not in calls[0] and "location" not in calls[0]


@pytest.mark.parametrize(
    "overrides",
    [
        {"choice": None},
        {"none_probability": 0.2},
        {"confidence": 0.6},
    ],
)
def test_ambiguous_decision_abstains(overrides):
    model = BambooDecision(
        "test",
        transport=httpx.MockTransport(lambda _: httpx.Response(200, json=result(**overrides))),
    )
    decision = model.evaluate("路况", "天气")
    assert decision.action == "defer" and decision.reason == "MODEL_UNCERTAIN"


@pytest.mark.parametrize(
    "data",
    [
        {},
        {"answers": {"attention": []}},
        result(choice="publish_audio"),
        result(confidence=True),
        result(none_probability="0.1"),
        result(confidence=1.1),
    ],
)
def test_malformed_decision_cannot_trigger_action(data):
    model = BambooDecision(
        "test", transport=httpx.MockTransport(lambda _: httpx.Response(200, json=data))
    )
    assert model.evaluate("focus", "text").action == "defer"


def test_missing_key_and_provider_error_are_not_notifications():
    assert BambooDecision("").evaluate("focus", "text").reason == "MODEL_NOT_CONFIGURED"
    model = BambooDecision(
        "secret", transport=httpx.MockTransport(lambda _: httpx.Response(401, text="secret"))
    )
    assert model.evaluate("focus", "text").reason == "MODEL_UNAVAILABLE"
