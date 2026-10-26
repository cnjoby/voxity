import asyncio
import logging
import time
from contextlib import asynccontextmanager, suppress
from typing import Annotated

from fastapi import Depends, FastAPI, File, Form, Header, Query, Request, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse, Response
from pydantic import TypeAdapter, ValidationError

from .attention_worker import AttentionWorker
from .audio import MAX_AUDIO, is_opus
from .config import Settings
from .contracts import AgentTurnResult, SessionCapabilities, VoiceMessageView
from .errors import Problem
from .models import (
    Ack,
    Context,
    Draft,
    Empty,
    ExchangeChange,
    ExchangeContext,
    ExchangeStart,
    ExchangeUtterance,
    InformationCheck,
    InformationQuery,
    Listening,
    LocationUpdate,
    RemotePlaceUpdate,
    TaskAction,
    VoiceQueue,
)
from .service import Service

voice_log = logging.getLogger("sonar.voice")
AUDIO_UPLOAD_DESCRIPTION = (
    "完整单声道Ogg Opus（audio/ogg）或16kHz/16-bit PCM WAV（audio/wav），"
    "最长30秒，单文件最多1000KiB；按实际音频内容校验。"
)
AUDIO_RESPONSES = {
    200: {
        "description": "按存储格式原样返回音频：历史WAV仍为WAV，新上传的Ogg Opus返回Ogg Opus。",
        "content": {
            media: {"schema": {"type": "string", "format": "binary"}}
            for media in ("audio/wav", "audio/ogg")
        },
    }
}


def audio_agent(scope):
    agent = dict(scope.get("headers", [])).get(b"user-agent", b"").decode("utf-8", "replace")
    return "".join(c for c in agent if c.isprintable())[:80]


class AudioTrace:
    """Bounded audio transfer metadata; never log headers, tokens or speech bodies."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        path = scope.get("path", "")
        if (
            scope["type"] != "http"
            or scope.get("method") != "GET"
            or not path.startswith("/v1/audio/")
        ):
            return await self.app(scope, receive, send)
        status, size = 0, 0

        async def counted(message):
            nonlocal status, size
            if message["type"] == "http.response.start":
                status = message["status"]
            elif message["type"] == "http.response.body":
                size += len(message.get("body", b""))
            await send(message)

        try:
            await self.app(scope, receive, counted)
        finally:
            voice_log.info(
                "audio_transfer path=%r status=%s bytes=%s scheme=%s agent=%r",
                path[:100],
                status,
                size,
                scope.get("scheme"),
                audio_agent(scope),
            )


class UploadLimit:
    """Bound the entire multipart body before Starlette parses/spools it."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        limited = scope.get("path") == "/v1/posts/drafts" or (
            scope.get("path", "").startswith("/v1/exchange/sessions/")
            and scope.get("path", "").endswith("/utterances")
        )
        if scope["type"] != "http" or not limited:
            return await self.app(scope, receive, send)
        data = bytearray()
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            data.extend(message.get("body", b""))
            if len(data) > 1024 * 1024:
                response = JSONResponse(
                    Problem(413, "BODY_TOO_LARGE", "上传请求过大").body(), status_code=413
                )
                return await response(scope, receive, send)
            if not message.get("more_body", False):
                break

        async def replay():
            return {"type": "http.request", "body": bytes(data), "more_body": False}

        await self.app(scope, replay, send)


def create_app(settings: Settings | None = None, clock=time.time):
    # Upstream request URLs can contain provider credentials in query parameters.
    logging.getLogger("httpx").setLevel(logging.WARNING)
    voice_log.setLevel(logging.INFO)
    logging.getLogger("sonar.latency").setLevel(logging.INFO)
    settings = settings or Settings()
    service = Service(settings, clock)

    @asynccontextmanager
    async def lifespan(app):
        task = (
            asyncio.create_task(AttentionWorker(service).run())
            if settings.decision_enabled
            else None
        )
        app.state.attention_task = task
        try:
            yield
        finally:
            if task:
                task.cancel()
                with suppress(asyncio.CancelledError):
                    await task

    app = FastAPI(title="Sonar", version="0.3.0", lifespan=lifespan)
    app.state.service = service
    app.add_middleware(UploadLimit)
    app.add_middleware(AudioTrace)

    @app.exception_handler(Problem)
    async def problem_handler(request, exc):
        return JSONResponse(exc.body(), status_code=exc.status)

    @app.exception_handler(RequestValidationError)
    async def validation_handler(request, exc):
        return JSONResponse(
            Problem(422, "VALIDATION_ERROR", "请求字段不符合接口契约").body(), status_code=422
        )

    def session():
        with service.scope() as db:
            yield db

    DB = Depends(session, scope="function")

    def user(authorization: Annotated[str | None, Header()] = None, db=DB):
        return service.auth(db, authorization)

    USER = Depends(user)

    @app.get("/healthz")
    def health():
        db = service.store.connect()
        try:
            db.execute("SELECT 1")
        finally:
            db.close()
        return {"status": "ok"}

    @app.get("/v1/me")
    def me(db=DB, uid=USER):
        return {
            **service.me(db, uid),
            "media": {
                "upload_formats": ["wav", "ogg_opus"],
                "client_managed_voice": service.managed_voice_available(db, uid),
            },
        }

    @app.get("/v1/contracts")
    def contracts(uid=USER):
        return {
            "schema_version": "1",
            "schemas": {
                model.__name__: model.model_json_schema()
                for model in (VoiceMessageView, AgentTurnResult, SessionCapabilities)
            },
            **service.now(),
        }

    @app.get("/v1/messages/{message_id}")
    def message_view(message_id: str, db=DB, uid=USER):
        return {"message": service.message_view(db, uid, message_id), **service.now()}

    @app.post("/v1/information")
    def information(data: InformationQuery, db=DB, uid=USER):
        return service.find_information(db, uid, data.place_id, data.query, data.purpose)

    @app.post("/v1/information/validate")
    def information_check(data: InformationCheck, db=DB, uid=USER):
        return service.validate_information(db, uid, data.message_ids, data.purpose)

    @app.get("/v1/tasks")
    def personal_tasks(db=DB, uid=USER):
        return service.personal_tasks(db, uid)

    @app.get("/v1/tasks/{task_id}")
    def personal_task(task_id: str, db=DB, uid=USER):
        return service.personal_task_view(db, uid, task_id)

    @app.post("/v1/tasks/{task_id}/actions")
    def task_action(task_id: str, data: TaskAction, db=DB, uid=USER):
        return service.change_personal_task(db, uid, task_id, **data.model_dump())

    @app.get("/v1/requests/{request_id}")
    def request_result(request_id: str, db=DB, uid=USER):
        return service.request_result(db, uid, request_id)

    @app.put("/v1/me/context")
    def context(data: Context, db=DB, uid=USER):
        return service.context(db, uid, data)

    # Authenticate without holding the SQLite writer lock across provider network calls.
    def location_user(authorization: Annotated[str | None, Header()] = None):
        db = service.store.connect()
        try:
            return service.auth(db, authorization)
        finally:
            db.close()

    LOCATION_USER = Depends(location_user)

    @app.put("/v1/me/remote-place")
    def remote_place(data: RemotePlaceUpdate, db=DB, uid=USER):
        return service.select_remote_place(db, uid, data)

    @app.post("/v1/exchange/sessions")
    @app.post("/v1/agent/sessions")
    def start_exchange(data: ExchangeStart, db=DB, uid=USER):
        return service.start_exchange(db, uid, data)

    @app.post("/v1/agent/sessions/{sid}/turns")
    async def agent_turn(sid: str, data: ExchangeUtterance, uid=LOCATION_USER):
        return await service.submit_agent_turn(uid, sid, data)

    @app.get("/v1/exchange/sessions/{sid}")
    def exchange_session(sid: str, db=DB, uid=USER):
        return service.exchange_view(db, uid, sid)

    @app.put("/v1/exchange/sessions/{sid}")
    def exchange_change(sid: str, data: ExchangeChange, db=DB, uid=USER):
        return service.change_exchange(db, uid, sid, data)

    @app.put("/v1/exchange/sessions/{sid}/context")
    def exchange_context(sid: str, data: ExchangeContext, db=DB, uid=USER):
        return service.set_exchange_context(db, uid, sid, data)

    @app.get("/v1/exchange/sessions/{sid}/inbox")
    async def exchange_inbox(
        sid: str,
        exclude: Annotated[list[str] | None, Query(max_length=256)] = None,
        uid=LOCATION_USER,
    ):
        return await service.exchange_inbox(uid, sid, exclude or ())

    @app.get("/v1/exchange/turns/{turn_id}")
    def exchange_turn(turn_id: str, db=DB, uid=USER):
        return service.exchange_turn_view(db, uid, turn_id)

    @app.post("/v1/exchange/sessions/{sid}/utterances")
    async def exchange_utterance(
        sid: str,
        audio: Annotated[UploadFile, File(description=AUDIO_UPLOAD_DESCRIPTION)],
        metadata: Annotated[str, Form(max_length=4096)],
        uid=LOCATION_USER,
    ):
        try:
            data = ExchangeUtterance.model_validate_json(metadata)
        except ValidationError as exc:
            raise Problem(422, "INVALID_METADATA", "语音轮次字段无效") from exc
        return await service.submit_exchange(uid, sid, data, await audio.read(MAX_AUDIO + 1))

    @app.put("/v1/me/location")
    def location(data: LocationUpdate, uid=LOCATION_USER):
        if data.location and data.location.source in ("simulated_place", "remote_place"):
            raise Problem(422, "SIMULATION_ENDPOINT_REQUIRED", "模拟点位请使用专用接口")
        if data.location:
            service.check_fix(data.location)
        resolved = service.geo.resolve(data.location) if data.location else None
        with service.scope() as db:
            return service.update_location(db, uid, data, resolved)

    @app.get("/v1/places")
    def places(query: str = Query(min_length=2, max_length=100), uid=LOCATION_USER):
        results = service.geo.search(query)
        with service.scope() as db:
            for p in results:
                db.execute(
                    "INSERT INTO places VALUES (?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET label=excluded.label",
                    (
                        p["id"],
                        p["label"],
                        p["latitude"],
                        p["longitude"],
                        p["kind"],
                        service.clock(),
                    ),
                )
        return {"places": results, **service.now()}

    @app.post("/v1/posts/drafts")
    async def draft(
        audio: Annotated[UploadFile, File(description=AUDIO_UPLOAD_DESCRIPTION)],
        metadata: Annotated[str, Form(max_length=4096)],
        db=DB,
        uid=USER,
    ):
        try:
            meta = TypeAdapter(Draft).validate_json(metadata)
        except ValidationError as exc:
            raise Problem(422, "INVALID_METADATA", "草稿字段无效") from exc
        content = await audio.read(MAX_AUDIO + 1)
        result, created = service.draft(db, uid, meta, content)
        return JSONResponse(result, status_code=201 if created else 200)

    @app.post("/v1/posts/{pid}/confirm")
    def confirm(pid: str, data: Empty, db=DB, uid=USER):
        return service.confirm(db, uid, pid)

    @app.get("/v1/questions/{qid}")
    def question(qid: str, db=DB, uid=USER):
        return service.question(db, uid, qid)

    @app.post("/v1/questions/{qid}/cancel")
    def cancel(qid: str, data: Empty, db=DB, uid=USER):
        return service.cancel(db, uid, qid)

    @app.get("/v1/inbox")
    def inbox(db=DB, uid=USER):
        return service.inbox(db, uid)

    @app.get("/v1/inbox/{did}")
    def delivery(did: str, db=DB, uid=USER):
        return {"delivery": service.delivery(db, uid, did), **service.now()}

    @app.post("/v1/inbox/{did}/ack")
    def ack(did: str, data: Ack, request: Request, db=DB, uid=USER):
        result = service.ack(db, uid, did, data.event, data.request_id)
        voice_log.info(
            "audio_ack user=%s delivery=%s event=%s scheme=%s agent=%r",
            uid,
            did,
            data.event,
            request.scope.get("scheme"),
            audio_agent(request.scope),
        )
        return result

    @app.put("/v1/me/listening")
    def listening(data: Listening, db=DB, uid=USER):
        return service.set_listening(db, uid, data)

    @app.get("/v1/voices")
    def voices(place_id: str = Query(min_length=1, max_length=100), db=DB, uid=USER):
        return service.catalog(db, uid, place_id)

    @app.post("/v1/voices/{pid}/queue")
    def queue_place_voice(pid: str, data: VoiceQueue, db=DB, uid=USER):
        return service.queue_place_voice(
            db, uid, pid, str(data.client_request_id) if data.client_request_id else None
        )

    @app.post("/v1/voices/{pid}/withdraw")
    def withdraw_voice(pid: str, data: Empty, db=DB, uid=USER):
        return service.withdraw_voice(db, uid, pid)

    @app.get("/v1/conversations/{qid}")
    def conversation(qid: str, db=DB, uid=USER):
        return service.conversation(db, uid, qid)

    @app.get("/v1/audio/{pid}", response_class=FileResponse, responses=AUDIO_RESPONSES)
    def audio(pid: str, db=DB, uid=USER):
        p = service.post(db, pid)
        if not service.can_read_audio(db, uid, p):
            raise Problem(404, "NOT_FOUND", "音频不存在")
        path = service.store.audio_path(p["id"])
        compressed = path.suffix == ".opus"
        return FileResponse(
            path,
            media_type="audio/ogg" if compressed else "audio/wav",
            filename="voice.opus" if compressed else "voice.wav",
        )

    @app.get("/v1/chat/audio/{mid}", response_class=Response, responses=AUDIO_RESPONSES)
    def chat_audio(mid: str, db=DB, uid=USER):
        data = service.chat_audio(db, uid, mid)
        return Response(data, media_type="audio/ogg" if is_opus(data) else "audio/wav")

    from .mcp import ProtectedMCP

    app.mount("/", ProtectedMCP(service))
    return app
