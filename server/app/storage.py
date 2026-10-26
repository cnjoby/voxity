import asyncio
import hashlib
import sqlite3
from contextlib import asynccontextmanager, contextmanager
from pathlib import Path


def digest(value: str | bytes) -> str:
    return hashlib.sha256(value.encode() if isinstance(value, str) else value).hexdigest()


class Database:
    def __init__(self, data_dir: Path):
        self.data_dir = data_dir.resolve()
        self.audio_dir = self.data_dir / "audio"
        self.audio_dir.mkdir(parents=True, exist_ok=True)
        self.path = self.data_dir / "sonar.sqlite3"
        db = self.connect()
        try:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript(Path(__file__).with_name("schema.sql").read_text())
            # Additive migration preserves existing users, drafts and delivery history.
            for table, columns in {
                "exchange_sessions": {
                    "network_enabled": "INTEGER NOT NULL DEFAULT 0",
                    "capabilities_json": "TEXT",
                },
                "profiles": {
                    "location_json": "TEXT",
                    "latitude": "REAL",
                    "longitude": "REAL",
                    "sampled_at": "REAL",
                    "accuracy_m": "REAL",
                },
                "deliveries": {
                    "attention_json": "TEXT",
                    "voice_json": "TEXT",
                    "received_at": "REAL",
                    "seen_at": "REAL",
                    "played_at": "REAL",
                },
                "posts": {
                    "recorded_location": "TEXT",
                    "target_latitude": "REAL",
                    "target_longitude": "REAL",
                    "target_label": "TEXT",
                },
            }.items():
                existing = {r[1] for r in db.execute(f"PRAGMA table_info({table})")}
                for column, kind in columns.items():
                    if column not in existing:
                        db.execute(f"ALTER TABLE {table} ADD COLUMN {column} {kind}")
            db.commit()
        finally:
            db.close()

    def connect(self):
        db = sqlite3.connect(self.path, timeout=5, check_same_thread=False)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        return db

    def audio_path(self, post_id):
        # IDs come from database rows, never raw request paths. Legacy WAVs remain valid.
        opus = self.audio_dir / f"{post_id}.opus"
        return opus if opus.is_file() else self.audio_dir / f"{post_id}.wav"

    @contextmanager
    def transaction(self):
        db = self.connect()
        try:
            db.execute("BEGIN IMMEDIATE")
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    @asynccontextmanager
    async def async_transaction(self):
        # A synchronous SQLite busy wait on the ASGI loop can prevent another
        # request's dependency cleanup from committing the writer we wait for.
        scope = self.transaction()
        opening = asyncio.create_task(asyncio.to_thread(scope.__enter__))
        try:
            db = await asyncio.shield(opening)
        except asyncio.CancelledError:
            # __enter__ may still acquire the lock after the request is cancelled.
            # Drain it and close its transaction instead of leaking a writer.
            try:
                await opening
            except Exception:
                pass
            else:
                await asyncio.to_thread(
                    scope.__exit__, asyncio.CancelledError, asyncio.CancelledError(), None
                )
            raise
        try:
            yield db
        except BaseException as exc:
            await asyncio.shield(
                asyncio.to_thread(scope.__exit__, type(exc), exc, exc.__traceback__)
            )
            raise
        else:
            await asyncio.shield(asyncio.to_thread(scope.__exit__, None, None, None))
