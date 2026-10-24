"""SSE MCP: per-user transports prevent cross-identity session injection."""

import json
import secrets
from typing import Literal

from mcp.server.fastmcp import FastMCP
from mcp.server.transport_security import TransportSecuritySettings
from mcp.types import CallToolResult, TextContent
from starlette.requests import Request
from starlette.responses import JSONResponse

from .errors import Problem


class ProtectedMCP:
    def __init__(self, service):
        self.service = service
        self.apps = {}

    def user_app(self, uid, streamable=False):
        if not streamable and uid in self.apps:
            return self.apps[uid]
        # Host checking is done by the HTTPS proxy allowlist; bearer+binding are mandatory here.
        mcp = FastMCP(
            "Sonar",
            sse_path="/mcp",
            message_path="/mcp/message/",
            streamable_http_path="/mcp/http",
            stateless_http=True,
            json_response=True,
            transport_security=TransportSecuritySettings(enable_dns_rebinding_protection=False),
        )

        def invoke(method, **kwargs):
            try:
                if uid is None:
                    raise Problem(401, "MCP_IDENTITY_MISSING", "缺少平台身份，仅可发现工具")
                with self.service.scope() as db:
                    result = method(db, uid, **kwargs)
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(result, ensure_ascii=False))],
                    structuredContent=result,
                )
            except Problem as exc:
                return CallToolResult(
                    content=[
                        TextContent(type="text", text=json.dumps(exc.body(), ensure_ascii=False))
                    ],
                    isError=True,
                )

        @mcp.tool()
        def probe():
            """只读验证 Sonar 可信用户映射，不公开凭证。"""
            if uid is None:
                return CallToolResult(
                    content=[
                        TextContent(
                            type="text",
                            text=json.dumps(
                                {"code": "MCP_IDENTITY_MISSING", "message": "缺少平台身份"}
                            ),
                        )
                    ],
                    isError=True,
                )
            result = {"status": "ok", "user_id": uid}
            return CallToolResult(
                content=[TextContent(type="text", text=json.dumps(result))],
                structuredContent=result,
            )

        @mcp.tool()
        def get_feed():
            """获取本人的配置、任务及合法声音候选。"""
            return invoke(self.service.get_feed)

        @mcp.tool()
        def set_focus(
            expected_revision: int,
            mode: Literal["listen", "quiet"] | None = None,
            assist_enabled: bool | None = None,
            focus: str | None = None,
        ):
            """更新本人的收听意愿。安静须设置 mode=quiet；此工具不发布声音。"""
            return invoke(
                self.service.set_focus,
                expected_revision=expected_revision,
                mode=mode,
                assist_enabled=assist_enabled,
                focus=focus,
            )

        @mcp.tool()
        def queue_voice(request_id: str, profile_revision: int, post_id: str):
            """从本轮候选请求播放一条真人原声。"""
            return invoke(
                self.service.queue_voice,
                request_id=request_id,
                profile_revision=profile_revision,
                post_id=post_id,
            )

        if streamable:
            # A fresh stateless transport per HTTP request binds every call to that request's
            # verified identity. Its lifespan never shares a task group with another request.
            return mcp.streamable_http_app()
        self.apps[uid] = mcp.sse_app()
        return self.apps[uid]

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return
        settings = self.service.settings
        try:
            if scope["path"] not in ("/mcp", "/mcp/message", "/mcp/message/", "/mcp/http"):
                raise Problem(404, "NOT_FOUND", "接口不存在")
            if not settings.mcp_enabled or len(settings.mcp_secret) < 32:
                raise Problem(503, "MCP_DISABLED", "MCP 尚未配置可信凭证")
            request = Request(scope)
            bearer = request.headers.get("authorization", "")
            if not secrets.compare_digest(bearer, "Bearer " + settings.mcp_secret):
                raise Problem(401, "UNAUTHORIZED", "MCP 凭证无效")
            device = request.headers.get("x-linx-device-id", "")
            agent = request.headers.get("x-linx-agent-id", "")
            session = request.headers.get("x-linx-session-id", "")
            if not any((device, agent, session)):
                # Console discovery has no active device. This transport exposes schemas only;
                # every tool call fails closed and cannot target a user's transport session.
                uid = None
            elif not all((device, agent, session)):
                raise Problem(401, "MCP_IDENTITY_MISSING", "缺少平台身份")
            else:
                with self.service.scope() as db:
                    row = db.execute(
                        "SELECT id FROM users WHERE device_id=? AND agent_id=? AND agent_id!=''",
                        (device, agent),
                    ).fetchone()
                if not row:
                    raise Problem(403, "MCP_BINDING_INVALID", "平台绑定不匹配")
                uid = row["id"]
            # Each user has a distinct SDK session store. A valid Bob POST cannot target Alice SSE.
            streamable = scope["path"] == "/mcp/http"
            app = self.user_app(uid, streamable=streamable)
            if scope["path"] == "/mcp/message":
                scope = {**scope, "path": "/mcp/message/", "raw_path": b"/mcp/message/"}
            if streamable:
                async with app.router.lifespan_context(app):
                    await app(scope, receive, send)
            else:
                await app(scope, receive, send)
        except Problem as exc:
            await JSONResponse(exc.body(), status_code=exc.status)(scope, receive, send)
