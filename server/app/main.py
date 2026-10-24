import asyncio
import time
from contextlib import asynccontextmanager, suppress
from typing import Annotated

from fastapi import Depends, FastAPI, File, Form, Header, Query, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse
from pydantic import TypeAdapter, ValidationError

from .attention_worker import AttentionWorker
from .audio import MAX_AUDIO
from .config import Settings
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
    Listening,
    LocationUpdate,
    VoiceQueue,
)
from .service import Service


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
            if len(data) > 350 * 1024:
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

    app = FastAPI(title="Sonar", version="0.1.0", lifespan=lifespan)
    app.state.service = service
    app.add_middleware(UploadLimit)

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
        return service.me(db, uid)

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

    @app.post("/v1/exchange/sessions")
    def start_exchange(data: ExchangeStart, db=DB, uid=USER):
        return service.start_exchange(db, uid, data)

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
        audio: Annotated[UploadFile, File()],
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
        audio: Annotated[UploadFile, File()],
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
    def ack(did: str, data: Ack, db=DB, uid=USER):
        return service.ack(db, uid, did, data.event, data.request_id)

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

    @app.get("/v1/audio/{pid}")
    def audio(pid: str, db=DB, uid=USER):
        p = service.post(db, pid)
        if not service.can_read_audio(db, uid, p):
            raise Problem(404, "NOT_FOUND", "音频不存在")
        return FileResponse(
            service.store.audio_dir / f"{p['id']}.wav", media_type="audio/wav", filename="voice.wav"
        )

    from .mcp import ProtectedMCP

    app.mount("/", ProtectedMCP(service))
    return app
