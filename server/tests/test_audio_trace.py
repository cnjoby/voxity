import logging

from conftest import auth, publish, wav


def test_audio_trace_records_byte_count_without_auth_or_body(env, caplog):
    c, _, _, _ = env
    post = publish(c)
    with caplog.at_level(logging.INFO, logger="sonar.voice"):
        response = c.get(f"/v1/audio/{post}", headers={**auth(), "User-Agent": "ESP32-audio-debug"})
    assert response.status_code == 200 and response.content == wav()
    assert f"bytes={len(wav())}" in caplog.text
    assert "ESP32-audio-debug" in caplog.text
    assert "Bearer" not in caplog.text and "a" * 40 not in caplog.text
