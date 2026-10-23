PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS users (
 id TEXT PRIMARY KEY, token_hash TEXT NOT NULL UNIQUE,
 device_id TEXT NOT NULL UNIQUE, agent_id TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS profiles (
 user_id TEXT PRIMARY KEY REFERENCES users(id), place_id TEXT NOT NULL,
 direction TEXT NOT NULL, source TEXT NOT NULL, updated_at REAL NOT NULL,
 assist_enabled INTEGER NOT NULL, mode TEXT NOT NULL, focus TEXT NOT NULL,
 revision INTEGER NOT NULL DEFAULT 1
);
CREATE TABLE IF NOT EXISTS posts (
 id TEXT PRIMARY KEY, author_id TEXT NOT NULL REFERENCES users(id),
 client_request_id TEXT NOT NULL, fingerprint TEXT NOT NULL, kind TEXT NOT NULL,
 transcript TEXT NOT NULL, target_place_id TEXT NOT NULL, direction TEXT NOT NULL,
 reply_to TEXT REFERENCES posts(id), invitation_id TEXT,
 status TEXT NOT NULL DEFAULT 'draft', question_status TEXT,
 created_at REAL NOT NULL, published_at REAL, expires_at REAL,
 UNIQUE(author_id, client_request_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS one_waiting_question ON posts(author_id)
 WHERE kind='question' AND question_status='waiting';
CREATE TABLE IF NOT EXISTS deliveries (
 id TEXT PRIMARY KEY, recipient_id TEXT NOT NULL REFERENCES users(id),
 question_id TEXT NOT NULL REFERENCES posts(id), post_id TEXT NOT NULL REFERENCES posts(id),
 type TEXT NOT NULL, request_id TEXT NOT NULL, profile_revision INTEGER NOT NULL,
 state TEXT NOT NULL DEFAULT 'pending', reason_code TEXT NOT NULL DEFAULT 'ELIGIBLE',
 created_at REAL NOT NULL, expires_at REAL NOT NULL,
 UNIQUE(recipient_id, post_id, type)
);
CREATE INDEX IF NOT EXISTS recipient_queue ON deliveries(recipient_id, state, created_at);
CREATE INDEX IF NOT EXISTS question_deliveries ON deliveries(question_id);
