-- SolSleep alarm-only device-protected schema, version 2.
-- Schema fixtures change only through a tested non-destructive migration.
CREATE TABLE alarms(id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL);
CREATE TABLE pending(
    alarm_id TEXT PRIMARY KEY NOT NULL REFERENCES alarms(id) ON DELETE CASCADE,
    token TEXT UNIQUE NOT NULL,
    due_ms INTEGER NOT NULL,
    origin_date TEXT NOT NULL,
    state TEXT NOT NULL,
    snoozes INTEGER NOT NULL,
    armed INTEGER NOT NULL,
    started_ms INTEGER,
    challenge_payload TEXT
);
PRAGMA user_version=2;
