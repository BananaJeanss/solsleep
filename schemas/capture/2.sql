-- SPDX-License-Identifier: GPL-3.0-or-later
CREATE TABLE sessions(id TEXT PRIMARY KEY NOT NULL,request_id TEXT NOT NULL UNIQUE,
 owner_id TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN ('PREPARING','RECORDING','PAUSED','INTERRUPTED','COMPLETED','FAILED','DELETING')),
 started_ms INTEGER NOT NULL,updated_ms INTEGER NOT NULL,revision INTEGER NOT NULL CHECK(revision>=1));
CREATE UNIQUE INDEX single_open_session ON sessions((1)) WHERE state NOT IN ('COMPLETED','FAILED','DELETING');
CREATE TABLE events(sequence INTEGER PRIMARY KEY AUTOINCREMENT,
 session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,reason TEXT NOT NULL,
 wall_ms INTEGER NOT NULL,elapsed_ns INTEGER NOT NULL CHECK(elapsed_ns>=0),boot_id TEXT NOT NULL,detail INTEGER,
 original_sequence INTEGER CHECK(original_sequence>0));
CREATE TABLE streams(id TEXT PRIMARY KEY NOT NULL,
 session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,boot_id TEXT NOT NULL,
 anchor_ms INTEGER NOT NULL,anchor_ns INTEGER NOT NULL CHECK(anchor_ns>=0),
 sensor_name TEXT NOT NULL,sensor_vendor TEXT NOT NULL,sensor_version INTEGER NOT NULL,
 resolution REAL NOT NULL,maximum_range REAL NOT NULL,wake_up INTEGER NOT NULL CHECK(wake_up IN (0,1)),
 committed_samples INTEGER NOT NULL DEFAULT 0 CHECK(committed_samples>=0),
 rejected_samples INTEGER NOT NULL DEFAULT 0 CHECK(rejected_samples>=0),gaps INTEGER NOT NULL DEFAULT 0 CHECK(gaps>=0));
CREATE UNIQUE INDEX imported_event_order ON events(session_id,original_sequence) WHERE original_sequence IS NOT NULL;
CREATE TABLE deleted_sessions(id TEXT PRIMARY KEY NOT NULL,request_id TEXT NOT NULL UNIQUE);
PRAGMA user_version=2;
