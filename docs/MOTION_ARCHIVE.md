# Recording archives v1 and v2

18 October 2026. Implemented and software-qualified in PR #8; see STATUS.md for exact
source and executed results. This is a separate plaintext, single-recording
format, not a combined app backup, encryption or physiological analysis.
Version 2 adds original acquisition provenance and is under separate qualification;
see [RECORDING_COVERAGE](RECORDING_COVERAGE.md) and current STATUS.md evidence.

## Human workflow and scope

Phone motion → finish recording → **Export recording** → choose a document.
The app freezes and validates a private spool before opening the destination,
writes/closes it, independently reopens the chosen URI, validates every chunk and
the envelope and compares the fingerprint. Written but unverified is distinct
from failed write. Keep the original when verification fails. Readback checks the
provider's returned bytes; it cannot promise future upload, retention or availability.

Import: choose a `.solmotion` document → bounded private staging → complete
validation → preview timestamp/files/samples/bytes/conflicts → explicit Restore.
The source and destination do not need matching signing keys. Do not uninstall an
installation containing records without external copies just to change signers.

Only COMPLETED/FAILED recordings export. Finish a paused/interrupted recording
first. Request/owner/session identities, state/revision, both wall timestamps,
all source descriptors/anchors/counters, all ordered events including original
sequence/detail and every original SOLM chunk are preserved. Files may lead the
rebuildable indexed sample count after an interrupted checkpoint; this difference
is retained. Indexed samples without matching raw data reject export/restore.
There is no wall-time sorting that erases clock rollback or boot boundaries.

Alarms, manual journal entries, preferences, other recordings, audio, clinical
stages and watch data are not included. No media/network/storage permission is
added. Files reveal timing and movement; the picker and consent explicitly call
them unencrypted. Device-protected alarms never initialize this archive store.

## Container specification

All integers are signed big-endian unless described otherwise. UUIDs are their
16 network-order bytes. `string` is an int32 UTF-8 byte count followed by strict
UTF-8 (no replacement decoding), max 2,048 bytes. `flag` is exactly one byte,
0 or 1. Floats are finite IEEE-754 binary32 with original bits, including negative
zero. Times are Unix epoch milliseconds (1900 inclusive through year 9999).
Monotonic values are nanoseconds since their retained source boot, never portable
wall timestamps by themselves.

| Order | Fields |
| --- | --- |
| Header | int32 `0x534f4c52` (SOLR), int32 version `1` or `2`, int32 metadata byte length |
| Metadata | Exactly that many bytes, described below |
| Repeated chunk | int32 byte length then exact checksummed SOLM v1 bytes |
| End | int32 `0` instead of another length |
| Footer | 32 raw SHA-256 bytes of everything through the zero terminator |
| EOF | Required; trailing bytes reject |

Version 1 metadata is sequential, with no padding or optional extensions:

1. Session: UUID id, UUID request, UUID owner, string state, int64 started-ms,
   int64 updated-ms, int64 revision.
2. int32 stream count, followed by each stream: UUID id, UUID boot, int64
   anchor-wall-ms, int64 anchor-elapsed-ns, string sensor-name, string vendor,
   int32 sensor-version, float32 resolution, float32 maximum-range, flag wake-up,
   int64 indexed-committed-samples, int64 rejected-samples, int64 gaps.
3. int32 event count, followed by each event: int64 original sequence, string
   CaptureReason enum name, int64 wall-ms, int64 elapsed-ns, UUID boot,
   flag has-detail, int64 detail only when has-detail is 1.
4. Exact metadata EOF. Session identity is inherited by each stream/event.

Version 2 appends, after each stream's `gaps`, a canonical flag `has-provenance`.
Only when 1, a `string` contains at most 1,024 UTF-8 bytes of acquisition JSON:
exact fields `version: 1`, `baseApkSha256` (64 lowercase hex), `zoneId` (valid Java
ZoneId, max 128 characters) and `requestedPeriodMicros` (integer 1–60,000,000).
Duplicate/unknown fields, unknown versions and malformed values reject. Null
legacy streams remain null. Other metadata, framing, bounds and raw bytes do not
change. Writers select v2 when any stream has provenance; an all-legacy record
retains exact v1 output. Older applications refuse v2 instead of dropping fields.

Each SOLM chunk has the existing self-describing codec in MotionChunk.kt:
magic/version, session/stream/boot UUIDs, sequence, wall/elapsed anchors, count,
20 bytes per sample (int64 elapsed + three float32 axes in m/s² including gravity),
and its own 32-byte SHA-256. Archive chunks are ordered per source by consecutive
sequence starting at zero. Sources can interleave; readings must remain strictly
increasing across each source's chunks and at/after its monotonic anchor. Every
chunk must match a manifest source, boot and both anchors. Unknown versions,
invalid enum names, duplicate sources, nonpositive/out-of-order event sequences,
nonfinite floats, noncanonical flags, missing indexed observations, sequence gaps,
regression, checksum errors and trailing data reject. Hashes detect corruption;
there is no authentication against a malicious party recomputing them.

Bounds: 2 MiB metadata, 512 sources, 10,000 events, 32,768 chunks, 64 MiB raw
chunk bytes. Maximum container length is 69,337,136 bytes (64 MiB + 2 MiB +
4 × 32,768 + 48). No compression, paths, entry names or executable content occur.
One bounded chunk is allocated at a time. Metadata lists and per-source counters
are bounded; source row counts are checked before full snapshot allocation.
Raw-file export enumeration is bounded too. Oversized originals remain retained;
export fails rather than quietly omitting older observations.

The independent Python fixture generator and inspector in `tools/` demonstrate
reading this format outside Android. Their fixture does not establish compatibility
with Sleep as Android or any real physiological recording.

## Staging, publication and recovery

A single credential-protected `noBackupFilesDir/motion-import` slot is guarded by
a process monitor and an OS lock. No provider-supplied filename becomes a path.
A bounded copy goes to `preparing.solmotion`, file sync follows, the entire file is
validated, then atomic same-directory rename and directory sync publish
`pending.solmotion`. A complete preparing copy can recover after restart; a partial
or corrupt copy remains explicit and can be discarded. Preview never publishes
raw observations or starts a service.

Restore validates the immutable slot again, requires no active capture, checks
free space and holds one capture-database transaction while publishing. Existing
session/request/source IDs and local deletion markers win. Existing raw chunks
must be an exact subset of the incoming archive; unrelated/conflicting or unknown
files retain an error. The complete source is validated before live publication;
chunk bytes publish with the existing file/rename/directory sync protocol. Exact
retries accept identical chunks. All session/source/event metadata then inserts
in one transaction. The pending copy is removed only after commit.

There is **no atomic transaction across files and SQLite**. Failure after some
raw publishes but before metadata commit leaves invisible orphan chunks plus the
private complete recovery copy. Reopening offers the same preview; an explicit
retry validates and accepts identical bytes. Failure inserting a source/event
rolls back all visible metadata and keeps the recovery file. A cleanup failure
after commit can leave a duplicate recovery copy; local identity wins on retry.
Nothing automatically resumes acquisition or arms an alarm.

Discard removes only the staging copy, never the chosen document or live records.
Already-published orphan files are conservatively retained for a later identical
retry; automatic orphan reclamation/retention management remains open. Only one
pending import can occupy the slot. The UI explains retained recovery storage.
Staging reserves maximum archive size plus 64 MiB; restore reserves declared raw
bytes plus 64 MiB, rechecking during writes. These conservative software checks
cannot reserve space against unrelated concurrent filesystem users.

## Capture schema 2 migration and deletion

Schema 1 → 2 adds nullable `events.original_sequence`, a per-session unique index
for non-null imported sequences, and `deleted_sessions(id,request_id)`. Existing
rows and local event ordering are unchanged. Imported events retain original
sequence separately from destination SQLite row IDs, avoiding collisions with
unrelated sessions. Completed restored records never become active owners.

Deletion's existing durable DELETING → owned raw removal → metadata deletion
ordering now inserts the local marker and deletes metadata in the same transaction.
A failed transaction retains its DELETING row; a marker prevents an older recording
copy from resurrecting that identity on this installation. Deleted request IDs
cannot start another session. Deletions predating migration cannot be reconstructed.
These markers are **not yet portable** in a single-live-recording archive. A clean
installation restoring an older archive cannot know subsequent deletions. Combined
archives and portable deletion propagation remain explicit plan work.

## Verification and remaining gates

Core tests exercise an independently Python-authored fixture, byte/float/metadata
round trips, malformed inputs/bounds/anchors, gaps/overlap, thousands of chunks,
provider transfer semantics and source retention on sink failure. Native cases use
real SQLite migration/reopen/rollback, durable staging, injected interruption after
synced raw publication, local conflicts/deletions, real ContentResolver readback
and a native preview/restore flow at 200% text. See STATUS.md for executed results;
implementation alone is not qualification.

Persistent raw-export attempt/verified receipts and provider identity/availability,
encryption/key recovery, combined all-record backups, portable capture tombstones,
recovery/orphan storage management and real power-loss/full-disk qualification
remain open. Journal receipts still refer only to journal JSON. Android task/process
loss tests and injected exceptions do not prove hardware power-loss behavior.

## Primary documentation reviewed

- Android Storage Access Framework and chosen documents:
  https://developer.android.com/training/data-storage/shared/documents-files
- ContentResolver stream errors/provider crash and readback:
  https://developer.android.com/reference/android/content/ContentResolver
- SQLiteOpenHelper migration transaction/lifecycle:
  https://developer.android.com/reference/android/database/sqlite/SQLiteOpenHelper
- SQLite WAL transactions (our database mode): https://sqlite.org/wal.html
- Atomic commit scope/hardware assumptions: https://sqlite.org/atomiccommit.html
- Java 17 digest stream semantics:
  https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/security/DigestInputStream.html

Space-floor follow-up: use StatFs.availableBytes for currently available bytes,
not a promise that reclaimable caches have been allocated. AllocatableBytes may
include caches and allocateBytes can clear them; neither is a reserved safety
margin by itself. A distinct low-space UI result retains original/recovery data.
The policy is fault-injected in native tests without filling the CI device disk;
actual full-disk behavior remains a physical qualification gate.
https://developer.android.com/reference/android/os/StatFs
https://developer.android.com/reference/android/os/storage/StorageManager

Historical capture schema 1/2 has no original zone, producing build or requested
cadence. Schema 3 adds a nullable, bounded acquisition payload and validates 1→3
and 2→3 migration. Legacy nulls are retained; new segments store actual acquisition
metadata. Restoring on another phone/build does not substitute its settings.

Qualified source `36ca8777a4f9b696af4f9cd632f2c99bb5b0e188`, [CI39](https://github.com/BananaJeanss/solsleep/actions/runs/39076468922): 132 JVM, four Python and all 93 native tests on each of API 29/37, zero failures/errors/skips, zero lint issues and shrunk unsigned release. Native UI captures were reviewed; physical gates remain open.
