"""SSE MCP: per-user transports prevent cross-identity session injection."""

import asyncio
import json
import secrets
from typing import Literal

from mcp.server.fastmcp import FastMCP
from mcp.server.transport_security import TransportSecuritySettings
from mcp.types import CallToolResult, TextContent
from pydantic import ValidationError
from starlette.requests import Request
from starlette.responses import JSONResponse

from .errors import Problem
from .models import ExchangeUtterance


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

        @mcp.tool()
        def find_information(
            place_id: str,
            query: str = "",
            purpose: Literal["all", "parking", "weather", "route", "knowledge"] = "all",
        ):
            """查询明确地点有权限且有效的公告、观察及知识，保留各自来源与时间；不会发布或制造到达。"""
            if len(place_id) > 100 or len(query) > 1000:
                return CallToolResult(
                    content=[TextContent(type="text", text="INVALID_QUERY")], isError=True
                )
            return invoke(
                self.service.find_information, place_id=place_id, query=query, purpose=purpose
            )

        @mcp.tool()
        def get_tasks(task_id: str | None = None):
            """读取本人任务和真实状态；已有回应与已听完都不代表已解决。"""
            return (
                invoke(self.service.personal_task_view, task_id=task_id)
                if task_id
                else invoke(self.service.personal_tasks)
            )

        @mcp.tool()
        def get_request(request_id: str):
            """用原始请求UUID只读核对实际结果，不更换ID重发。"""
            return invoke(self.service.request_result, request_id=request_id)

        @mcp.tool()
        def set_task_state(
            task_id: str,
            expected_revision: int,
            client_request_id: str,
            action: Literal["resolve", "cancel"],
        ):
            """主人明确结束或确认已解决时更新本人任务；保留动作UUID用于幂等重试。"""
            return invoke(
                self.service.change_personal_task,
                task_id=task_id,
                expected_revision=expected_revision,
                client_request_id=client_request_id,
                action=action,
            )

        async def information(kind, value):
            try:
                if uid is None:
                    raise Problem(401, "MCP_IDENTITY_MISSING", "缺少平台身份，仅可发现工具")
                if not isinstance(value, str) or not 2 <= len(value.strip()) <= 100:
                    raise Problem(422, "INVALID_PLACE", "请提供具体地点")
                if kind == "places":
                    places = await asyncio.to_thread(self.service.geo.search, value.strip())
                    with self.service.scope() as db:
                        for place in places:
                            self.service.register_agent_place(db, place)
                    result = {"places": places, "provider": "amap", **self.service.now()}
                else:
                    with self.service.scope() as db:
                        row = db.execute("SELECT * FROM places WHERE id=?", (value,)).fetchone()
                        if not row:
                            raise Problem(404, "PLACE_UNKNOWN", "请先查找并明确地点")
                        place = dict(row)
                    result = await self.service.query_agent_tool(uid, kind, place)
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
        async def find_places(query: str):
            """地图地点搜索；同名地点须澄清，不把第一个结果当用户目的地。不会更新本人位置或公开发言。"""
            return await information("places", query)

        @mcp.tool()
        async def get_place_weather(place_id: str):
            """查询明确地点所在区县的天气报告；保留来源与报告时间，不冒充该地点的实时雨量。"""
            return await information("weather", place_id)

        @mcp.tool()
        async def get_driving_route(place_id: str):
            """从本人当前有效定位查询到明确目的地的驾车时间及分段路况；不代表现场目击或精确到达承诺。"""
            return await information("route", place_id)

        @mcp.tool()
        async def agent_turn(
            session_id: str,
            client_request_id: str,
            expected_session_revision: int,
            transcript: str,
            recorded_at: str,
        ):
            """统一查询、地点澄清及意图处理，使用本人已授权会话。
            文本不能冒充真人原声；不创建或扩大授权。
            """
            try:
                if uid is None:
                    raise Problem(401, "MCP_IDENTITY_MISSING", "缺少平台身份，仅可发现工具")
                data = ExchangeUtterance.model_validate(
                    {
                        "client_request_id": client_request_id,
                        "expected_session_revision": expected_session_revision,
                        "transcript": transcript,
                        "recorded_at": recorded_at,
                    }
                )
                result = await self.service.submit_agent_turn(uid, session_id, data)
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(result, ensure_ascii=False))],
                    structuredContent=result,
                )
            except ValidationError:
                exc = Problem(422, "INVALID_METADATA", "语音轮次字段不符合接口契约")
                return CallToolResult(
                    content=[
                        TextContent(type="text", text=json.dumps(exc.body(), ensure_ascii=False))
                    ],
                    isError=True,
                )
            except Problem as exc:
                return CallToolResult(
                    content=[
                        TextContent(type="text", text=json.dumps(exc.body(), ensure_ascii=False))
                    ],
                    isError=True,
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
