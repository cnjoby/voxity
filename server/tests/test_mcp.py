import asyncio
import socket
import threading
import time

import httpx
import uvicorn
from conftest import answer, auth, invitation, publish, set_context
from mcp import ClientSession
from mcp.client.sse import sse_client

from app.errors import Problem


def test_mcp_fail_closed_and_binding(env):
    c, service, _, settings = env
    assert c.get("/mcp").status_code == 503
    settings.mcp_enabled = True
    settings.mcp_secret = "m" * 40
    assert c.get("/mcp", headers={"X-Linx-Device-Id": "device_alice_demo"}).status_code == 401
    headers = {
        "Authorization": "Bearer " + settings.mcp_secret,
        "X-Linx-Device-Id": "device_alice_demo",
        "X-Linx-Agent-Id": "not-bound",
        "X-Linx-Session-Id": "test",
    }
    assert c.get("/mcp", headers=headers).status_code == 403


def test_feed_queue_revision_scope_expiry_and_restart(env):
    c, service, now, settings = env
    qid = publish(c)
    d = invitation(c)
    answer(c, qid, d)
    with service.scope() as db:
        feed = service.get_feed(db, "alice")
        post = feed["candidates"][0]["post_id"]
        kwargs = {
            "request_id": feed["request_id"],
            "profile_revision": feed["profile_revision"],
            "post_id": post,
        }
        first = service.queue_voice(db, "alice", **kwargs)
        assert first == service.queue_voice(db, "alice", **kwargs)
        try:
            service.queue_voice(db, "bob", **kwargs)
            raise AssertionError("expected rejection")
        except Problem as exc:
            assert exc.status == 403
    assert len(c.get("/v1/inbox", headers=auth()).json()["items"]) == 1
    set_context(c, "alice", mode="quiet")
    with service.scope() as db:
        try:
            service.queue_voice(db, "alice", **kwargs)
            raise AssertionError("expected rejection")
        except Problem as exc:
            assert exc.code == "REVISION_CONFLICT"
    now[0] += 31
    with service.scope() as db:
        try:
            service.queue_voice(db, "alice", **kwargs)
            raise AssertionError("expected rejection")
        except Problem as exc:
            assert exc.status == 410


def test_voice_setting_does_not_refresh_presence(env):
    _, service, now, _ = env
    now[0] += 601
    with service.scope() as db:
        previous = service.profile_row(db, "bob")["updated_at"]
        service.set_focus(db, "bob", expected_revision=1, mode="quiet")
        assert service.profile_row(db, "bob")["updated_at"] == previous


def test_official_sdk_over_real_sse_and_session_isolation(env):
    _, service, _, settings = env
    settings.mcp_enabled = True
    settings.mcp_secret = "m" * 40
    settings.alice_agent_id = "agent_alice_test"
    settings.bob_agent_id = "agent_bob_test"
    service.seed()
    from app.main import create_app

    # Bind a real loopback TCP socket; this tests HTTP/SSE framing, not Linx cloud.
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    server = uvicorn.Server(
        uvicorn.Config(create_app(settings, service.clock), log_level="critical")
    )
    thread = threading.Thread(target=lambda: server.run(sockets=[sock]), daemon=True)
    thread.start()
    deadline = time.monotonic() + 5
    try:
        while not server.started:
            if time.monotonic() > deadline:
                raise RuntimeError("server start timeout")
            time.sleep(0.01)
        base = f"http://127.0.0.1:{port}"
        headers = {
            "Authorization": "Bearer " + settings.mcp_secret,
            "X-Linx-Device-Id": settings.alice_device_id,
            "X-Linx-Agent-Id": settings.alice_agent_id,
            "X-Linx-Session-Id": "session-test",
        }

        async def run():
            async with sse_client(base + "/mcp", headers=headers) as streams:
                async with ClientSession(*streams) as session:
                    await session.initialize()
                    tools = await session.list_tools()
                    assert {t.name for t in tools.tools} == {
                        "probe",
                        "get_feed",
                        "set_focus",
                        "queue_voice",
                    }
                    probe = await session.call_tool("probe", {})
                    assert probe.structuredContent["user_id"] == "alice"
                    result = await session.call_tool(
                        "set_focus", {"expected_revision": 1, "mode": "quiet"}
                    )
                    assert not result.isError
                    assert result.structuredContent["profile"]["mode"] == "quiet"
                    result = await session.call_tool("get_feed", {})
                    assert result.structuredContent["profile"]["user_id"] == "alice"

        asyncio.run(run())
        with httpx.Client() as client:
            # Capture a real Alice transport endpoint. Bob's valid headers must not inject into it.
            with client.stream("GET", base + "/mcp", headers=headers, timeout=5) as stream:
                endpoint = next(
                    line[6:] for line in stream.iter_lines() if line.startswith("data: ")
                )
                bob = {
                    **headers,
                    "X-Linx-Device-Id": settings.bob_device_id,
                    "X-Linx-Agent-Id": settings.bob_agent_id,
                }
                response = client.post(
                    base + endpoint,
                    headers=bob,
                    json={"jsonrpc": "2.0", "id": 1, "method": "tools/list"},
                )
                assert response.status_code == 404
            assert client.get(base + "/v1/me", headers=auth()).json()["profile"]["mode"] == "quiet"
            assert (
                client.get(base + "/v1/me", headers=auth("bob")).json()["profile"]["mode"]
                == "listen"
            )
    finally:
        server.should_exit = True
        thread.join(timeout=5)
        sock.close()
        assert not thread.is_alive()


def test_queued_question_audio_follows_invitation_authorization(env):
    c, service, _, _ = env
    publish(c)
    with service.scope() as db:
        feed = service.get_feed(db, "bob")
        queued = service.queue_voice(
            db,
            "bob",
            feed["request_id"],
            feed["profile_revision"],
            feed["candidates"][0]["post_id"],
        )
    set_context(c, mode="quiet")
    set_context(c, mode="listen")
    delivery = c.get(f"/v1/inbox/{queued['delivery_id']}", headers=auth("bob")).json()["delivery"]
    assert delivery["state"] == "cancelled"
    assert not delivery["actionable"]


def test_restart_invalidates_feed_handles(env):
    _, service, _, settings = env
    from app.service import Service

    with service.scope() as db:
        feed = service.get_feed(db, "alice")
    restarted = Service(settings, service.clock)
    with restarted.scope() as db:
        try:
            restarted.queue_voice(db, "alice", feed["request_id"], 1, "not-needed")
            raise AssertionError("old feed survived restart")
        except Problem as exc:
            assert exc.code == "FEED_EXPIRED"
