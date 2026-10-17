# Local journal export history

Implementation continuation; actual results belong in STATUS.md.

The journal keeps a local receipt history, independent of its authoritative
records and the direct-boot alarm store. `journal-exports.db` uses credential
storage, unlock checks, FULL/WAL transactions, per-path process opening locks,
retained-corruption handling and non-destructive failure on unsupported schemas.
Schema 1 is tracked in `schemas/export-history/1.sql`; it is a first-schema fixture,
not a claim that unimplemented upgrades/downgrades work.

| Stage | Evidence and display |
| --- | --- |
| STARTED | Attempt recorded before snapshot/export. No completion claimed. |
| WRITTEN | The provider output stream wrote, flushed and closed; readback is not yet complete. |
| VERIFIED | Bounded destination parsing and exact full-document fingerprint comparison succeeded, then the history transaction committed. |
| FAILED_WRITE | Preparation/open/write/flush/close failed before successful output close. No verified copy claimed. |
| UNVERIFIED | Output closed, but read/parse/input-close/fingerprint verification failed. |

Transitions use expected prior stage and a transaction. A failed verified-state
write rolls back; competing terminal transitions cannot overwrite one another.
There is no atomic transaction across the provider file and SQLite. If the process
stops between those operations, a pending attempt remains unverified after reopen.
No filename or file presence can upgrade it to success. Sequence numbers order
attempts independently of wall-clock correction; timestamps are wall observations,
not elapsed-time measurements. Updates within an attempt do not move backward.

The last attempted export can differ from the last verified copy. A later failure
does not erase the earlier verified receipt. The UI shows both and distinguishes
unavailable history from an available history with no verified receipt. The metadata
is local to this installation and is not included in the journal archive. It does
not certify an old copy after a provider changes/deletes it or remote upload fails.

Metadata includes a UUID, sequence, times, stage, optional bounded document display
name, and a full-document SHA-256 only for verified receipts. No authoritative
journal values, encryption secrets, content URI or provider credentials are stored.
A user-chosen filename itself can be personal; this database receives the same
credential storage/backup exclusions as the rest of the app.

Audit storage is auxiliary. Its open/write/commit/corruption failure must not stop
a user recovering a valid copy of their journal. The coordinator continues the
actual provider transfer and readback, separately reports whether its receipt was
recorded, and never claims a persisted receipt when the audit operation failed.
Cancellation propagates and does not mark a pending attempt verified. Alarm
initialization never references this database. No automatic or network backup is
introduced; document writes remain explicitly user selected.

The domain suite tests transfer/audit ordering and failures at each audit phase.
Android tests use actual SQLite reopen, independent helpers, abort triggers,
unknown schema, damaged retained bytes and a separate-package ContentResolver
provider. UI tests exercise visible verified/history and unavailable-history
recovery paths. These do not replace hardware power-loss, real reinstall or
commercial provider quota/revocation/remote-completion qualification.
