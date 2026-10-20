-- SPDX-License-Identifier: GPL-3.0-or-later
CREATE TABLE attempts(sequence INTEGER PRIMARY KEY AUTOINCREMENT,
 id TEXT NOT NULL UNIQUE,session_id TEXT NOT NULL,started_ms INTEGER NOT NULL,
 updated_ms INTEGER NOT NULL CHECK(updated_ms>=started_ms),
 destination_hash TEXT,authority TEXT,display_name TEXT,
 stage TEXT NOT NULL CHECK(stage IN ('STARTED','WRITTEN','VERIFIED','FAILED_WRITE','UNVERIFIED')),
 fingerprint TEXT,
 CHECK(destination_hash IS NOT NULL OR (authority IS NULL AND display_name IS NULL)),
 CHECK(destination_hash IS NULL OR (length(destination_hash)=64 AND destination_hash NOT GLOB '*[^0-9a-f]*')),
 CHECK(authority IS NULL OR length(authority) BETWEEN 1 AND 255),
 CHECK(display_name IS NULL OR length(display_name) BETWEEN 1 AND 256),
 CHECK((stage='VERIFIED' AND fingerprint IS NOT NULL AND length(fingerprint)=64
 AND fingerprint NOT GLOB '*[^0-9a-f]*') OR (stage!='VERIFIED' AND fingerprint IS NULL)));
CREATE INDEX session_exports ON attempts(session_id,sequence);
CREATE INDEX verified_exports ON attempts(session_id,stage,sequence);
PRAGMA user_version=1;
