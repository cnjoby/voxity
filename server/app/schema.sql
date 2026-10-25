PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS remote_place_choices (
 user_id TEXT PRIMARY KEY REFERENCES users(id), place_id TEXT, selected_at REAL NOT NULL
);
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

CREATE TABLE IF NOT EXISTS places (
 id TEXT PRIMARY KEY, label TEXT NOT NULL, latitude REAL NOT NULL,
 longitude REAL NOT NULL, kind TEXT NOT NULL, created_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS place_parents (
 child_id TEXT PRIMARY KEY REFERENCES places(id),
 parent_id TEXT NOT NULL REFERENCES places(id), evidence TEXT NOT NULL,
 CHECK(child_id != parent_id)
);

CREATE TABLE IF NOT EXISTS schema_migrations (
 id TEXT PRIMARY KEY, applied_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS voice_settings (
 user_id TEXT PRIMARY KEY REFERENCES users(id), settings_json TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS channels (
 id TEXT PRIMARY KEY, place_id TEXT NOT NULL REFERENCES places(id),
 label TEXT NOT NULL, operator_label TEXT NOT NULL, demo INTEGER NOT NULL DEFAULT 1
);
CREATE TABLE IF NOT EXISTS channel_operators (
 channel_id TEXT NOT NULL REFERENCES channels(id), user_id TEXT NOT NULL REFERENCES users(id),
 PRIMARY KEY(channel_id,user_id)
);
CREATE TABLE IF NOT EXISTS voice_details (
 post_id TEXT PRIMARY KEY REFERENCES posts(id), metadata_json TEXT NOT NULL,
 withdrawn_at REAL, replaced_by TEXT REFERENCES posts(id)
);
CREATE TABLE IF NOT EXISTS voice_visits (
 user_id TEXT NOT NULL REFERENCES users(id), place_id TEXT NOT NULL REFERENCES places(id),
 visit_id TEXT NOT NULL, outside_since REAL, scene_count INTEGER NOT NULL DEFAULT 0,
 last_scene_at REAL, PRIMARY KEY(user_id,place_id)
);
CREATE INDEX IF NOT EXISTS voice_place ON posts(target_place_id,kind,status);
CREATE TABLE IF NOT EXISTS delivery_receipts (
 delivery_id TEXT NOT NULL, request_id TEXT NOT NULL,
 recipient_id TEXT NOT NULL REFERENCES users(id), post_id TEXT NOT NULL REFERENCES posts(id),
 visit_id TEXT, event TEXT NOT NULL, recorded_at REAL NOT NULL,
 PRIMARY KEY(delivery_id, request_id, event)
);
CREATE TABLE IF NOT EXISTS voice_queue_requests (
 user_id TEXT NOT NULL REFERENCES users(id), command_id TEXT NOT NULL,
 post_id TEXT NOT NULL REFERENCES posts(id), delivery_id TEXT NOT NULL,
 request_id TEXT NOT NULL, PRIMARY KEY(user_id,command_id)
);

CREATE TABLE IF NOT EXISTS exchange_sessions (
 id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id),
 client_request_id TEXT NOT NULL, fingerprint TEXT NOT NULL,
 place_id TEXT NOT NULL REFERENCES places(id),
 auto_publish INTEGER NOT NULL, auto_play INTEGER NOT NULL,
 state TEXT NOT NULL DEFAULT 'active', revision INTEGER NOT NULL DEFAULT 1,
 created_at REAL NOT NULL, expires_at REAL NOT NULL,
 context_post_id TEXT REFERENCES posts(id), capabilities_json TEXT,
 UNIQUE(user_id,client_request_id)
);
CREATE TABLE IF NOT EXISTS exchange_turns (
 id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES exchange_sessions(id),
 user_id TEXT NOT NULL REFERENCES users(id), client_request_id TEXT NOT NULL,
 fingerprint TEXT NOT NULL, session_revision INTEGER NOT NULL,
 profile_revision INTEGER NOT NULL, created_at REAL NOT NULL,
 status TEXT NOT NULL, result_json TEXT, post_id TEXT REFERENCES posts(id),
 UNIQUE(user_id,client_request_id)
);
CREATE TABLE IF NOT EXISTS exchange_candidates (
 session_id TEXT NOT NULL REFERENCES exchange_sessions(id), post_id TEXT NOT NULL REFERENCES posts(id),
 profile_revision INTEGER NOT NULL, state TEXT NOT NULL, created_at REAL NOT NULL,
 PRIMARY KEY(session_id,post_id,profile_revision)
);

-- Private turn continuation, separate from public posts and physical presence.
CREATE TABLE IF NOT EXISTS agent_pending (
 session_id TEXT PRIMARY KEY REFERENCES exchange_sessions(id),
 turn_id TEXT NOT NULL REFERENCES exchange_turns(id),
 metadata_json TEXT NOT NULL, audio BLOB NOT NULL, plan_json TEXT NOT NULL,
 expires_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS voice_sessions (
 session_id TEXT PRIMARY KEY REFERENCES exchange_sessions(id),
 policy_json TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS voice_preferences (
 user_id TEXT PRIMARY KEY REFERENCES users(id), enabled INTEGER NOT NULL,
 until_at REAL, session_id TEXT REFERENCES exchange_sessions(id)
);
CREATE TABLE IF NOT EXISTS user_destinations (
 user_id TEXT NOT NULL REFERENCES users(id), alias TEXT NOT NULL,
 place_id TEXT NOT NULL REFERENCES places(id), PRIMARY KEY(user_id,alias)
);
-- Mutual requests express chat willingness; assist_enabled is never reused for chat.
CREATE TABLE IF NOT EXISTS chat_requests (
 id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id),
 session_id TEXT NOT NULL REFERENCES exchange_sessions(id),
 state TEXT NOT NULL, peer_id TEXT REFERENCES chat_requests(id),
 transcript TEXT NOT NULL, audio BLOB NOT NULL,
 created_at REAL NOT NULL, expires_at REAL NOT NULL, seen_at REAL NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS one_open_chat ON chat_requests(user_id)
 WHERE state IN ('waiting','paired','agent');
CREATE TABLE IF NOT EXISTS chat_messages (
 id TEXT PRIMARY KEY, request_id TEXT NOT NULL REFERENCES chat_requests(id),
 author_id TEXT NOT NULL REFERENCES users(id), transcript TEXT NOT NULL,
 audio BLOB NOT NULL, created_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS chat_deliveries (
 id TEXT PRIMARY KEY, recipient_id TEXT NOT NULL REFERENCES users(id),
 message_id TEXT NOT NULL REFERENCES chat_messages(id),
 request_id TEXT NOT NULL, profile_revision INTEGER NOT NULL,
 state TEXT NOT NULL DEFAULT 'pending', expires_at REAL NOT NULL,
 UNIQUE(recipient_id,message_id)
);

CREATE TABLE IF NOT EXISTS personal_tasks (
 id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id),
 request_id TEXT NOT NULL, session_id TEXT REFERENCES exchange_sessions(id),
 question_id TEXT REFERENCES posts(id), conversation_id TEXT REFERENCES posts(id),
 last_request_message_id TEXT REFERENCES posts(id),
 kind TEXT NOT NULL, status TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 1,
 original_text TEXT NOT NULL, place_id TEXT, reason_code TEXT, result_json TEXT,
 created_at REAL NOT NULL, updated_at REAL NOT NULL, expires_at REAL NOT NULL,
 UNIQUE(user_id,request_id)
);
CREATE INDEX IF NOT EXISTS personal_task_owner ON personal_tasks(user_id,updated_at);
CREATE INDEX IF NOT EXISTS personal_task_question ON personal_tasks(question_id,user_id);
CREATE TABLE IF NOT EXISTS personal_task_turns (
 task_id TEXT NOT NULL REFERENCES personal_tasks(id),
 turn_id TEXT NOT NULL UNIQUE REFERENCES exchange_turns(id),
 PRIMARY KEY(task_id,turn_id)
);
CREATE TABLE IF NOT EXISTS personal_task_commands (
 user_id TEXT NOT NULL REFERENCES users(id), request_id TEXT NOT NULL,
 task_id TEXT NOT NULL REFERENCES personal_tasks(id), fingerprint TEXT NOT NULL,
 PRIMARY KEY(user_id,request_id)
);
CREATE TABLE IF NOT EXISTS question_routes (
 question_id TEXT PRIMARY KEY REFERENCES posts(id), last_batch_at REAL NOT NULL,
 policy_version TEXT NOT NULL DEFAULT '1'
);
