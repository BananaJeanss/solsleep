-- SPDX-License-Identifier: GPL-3.0-or-later
-- Credential-protected, journal-only schema. No destructive fallback.
PRAGMA foreign_keys=ON;
CREATE TABLE tombstones(id TEXT PRIMARY KEY NOT NULL);
CREATE TABLE sessions(id TEXT PRIMARY KEY NOT NULL, current_revision INTEGER NOT NULL CHECK(current_revision>0));
CREATE TABLE revisions(session_id TEXT NOT NULL REFERENCES sessions(id), revision INTEGER NOT NULL CHECK(revision>0),payload TEXT NOT NULL,PRIMARY KEY(session_id,revision));
PRAGMA user_version=1;
