import hashlib
import sqlite3
from contextlib import contextmanager
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
