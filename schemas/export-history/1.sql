-- SPDX-License-Identifier: GPL-3.0-or-later
CREATE TABLE attempts(sequence INTEGER PRIMARY KEY AUTOINCREMENT,
 id TEXT NOT NULL UNIQUE,started_ms INTEGER NOT NULL,updated_ms INTEGER NOT NULL CHECK(updated_ms>=started_ms),
 display_name TEXT,stage TEXT NOT NULL CHECK(stage IN ('STARTED','WRITTEN','VERIFIED','FAILED_WRITE','UNVERIFIED')),
 fingerprint TEXT,CHECK((stage='VERIFIED' AND fingerprint IS NOT NULL AND length(fingerprint)=64
 AND fingerprint NOT GLOB '*[^0-9a-f]*') OR (stage!='VERIFIED' AND fingerprint IS NULL)));
CREATE INDEX verified_exports ON attempts(stage,sequence);
PRAGMA user_version=1;
