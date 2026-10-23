import time
from contextlib import asynccontextmanager
from typing import Annotated

from fastapi import Depends, FastAPI, File, Form, Header, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse
from pydantic import TypeAdapter, ValidationError

from .audio import MAX_AUDIO
from .config import Settings
from .errors import Problem
from .models import Ack, Context, Draft, Empty
from .service import Service


class UploadLimit:
    """Bound the entire multipart body before Starlette parses/spools it."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope["path"] != "/v1/posts/drafts":
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
        yield

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
