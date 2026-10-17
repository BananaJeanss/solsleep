# Journal and motion persistence contract

17 October 2026. M2 implementation slice, not a complete night recorder.

## User-visible journal

The Journal tab creates **manual reports** of a rest interval, restfulness (unrated
or 1–5), notes and tags. Duration is elapsed time between the reported instants;
it is not measured sleep time, sleep efficiency or a sleep-stage estimate.
All date/time controls use the entry's named time zone. Existing entries keep
that zone when the device zone changes. A spring-forward gap is rejected. A
repeated fall-back time requires an explicit UTC offset. No silent time shift is
applied to a user's recollection. The hard alarm's distinct recurrence policy is
unchanged.

Tags are individual removable chips, so punctuation inside a tag is preserved
when another field is corrected. A new draft keeps one identity across retries
and recreation to prevent duplicate insertion after an uncertain save. Save and
Cancel remain in a fixed footer above the keyboard, including at large text.

Each successful correction appends an immutable complete revision and updates
the current pointer in one SQLite transaction. Identity and original creation
time cannot change. Revisions are consecutive from one; modification times cannot
move backward. Every write supplies the expected prior revision. Competing edits
or stale deletes fail visibly. The editor retains its original base revision,
including across activity recreation, rather than silently rebasing a draft.

The Saved corrections view can use an earlier version by appending a new
revision. All intervening versions remain in exports; it never rewrites history.

Deletion removes all revisions and the current pointer in one transaction. A
minimal identity tombstone prevents an old archive or stale draft from resurrecting
that identity on this installation. There is no background purge. New v2 exports
carry these identity markers to another installation. Previously exported copies
are independent: a v1 archive or an older v2 archive cannot know later deletions.
Restore the newer archive first to establish its deletion markers before using
older copies. Restore never deletes a night already present on the destination;
an incoming marker for a live local entry is skipped and reported. This additive
policy cannot implement automatic cross-device synchronization or erase old
external copies.
Raw motion files are not attached to manual records by this slice. Connecting a
recorder must add coordinated raw/derived/audio deletion before it ships.

## Separate storage and failure policy

`JournalStore` uses the default application context with an explicit device-protected-context rejection, checks user unlock,
and opens only when journal functionality is requested. AlarmGraph, direct-boot
receivers and ringing do not reference or initialize it. `alarms.db` remains a
separate device-protected store. The journal uses WAL, FULL synchronization,
foreign keys and non-exclusive write transactions. FULL deliberately prioritizes
commit durability over the throughput advantage of NORMAL synchronization.

Schema 1 is tracked in `schemas/journal/1.sql`. It has sessions, immutable revisions
and deletion identities. Unknown upgrades and downgrades fail without reset.
The corruption handler retains originals rather than applying SQLite's default
file deletion behavior. A confirmed save is reported only after transaction end;
failed writes/exports retain the editor and display an error. Health values are
never included in exception logs.

Only a first schema exists: the checked-in fixture is opened independently by
instrumentation. Future changes require tested migrations from every released
schema, including failure rollback and all values/provenance. A fixture open is
not evidence that an unimplemented migration works.

## Native journal portability v1/v2

Separate from the existing alarm-only archive. UTF-8 JSON, format
`solsleep-journal`, creation instant, records and corruption checksum. The reader
accepts v1 (SHA-256 of the compact `records` array; no deletion markers). Writers
emit v2 with `records` and a sorted, unique `deletedIds` array. Its SHA-256 covers
the compact object `{"records":[...],"deletedIds":[...]}` in that field order,
using UTF-8. Marker identities are canonical lowercase UUIDs, disjoint from live
histories. Duplicate markers, wrong types and unknown fields/versions fail before
restore. The envelope creation time is informational and is not authenticated. Records contain version, identity, revision, creation and
modification instants, start/end instants, IANA zone ID, note, nullable integer
rating and unique tags. The writer sorts identities and revisions, and tags. The
reader checks exact fields, all types, identity/interval/precision bounds,
revision chains, checksum, UTF-8 validity, maximum nesting eight, 8 MiB input and
10,000 revisions and 10,000 deletion markers. Limits fail explicitly; no marker is silently dropped. SHA-256 detects accidental corruption; it provides neither
confidentiality nor authenticity. This format is explicitly plaintext in the UI.

Export takes one transactional logical snapshot of revisions, current pointers and markers, rejects inconsistent state, writes through SAF, closes the
provider stream and only then reports completion. It never copies a live SQLite
file without its WAL. A provider write/close failure is reported as failed; the
partial document may remain and the source is unchanged. Inspection validates
before showing a preview. Additive restore inserts complete histories for missing
identities and missing deletion markers in one transaction. Existing local
identities and local deletions win. Imported markers never delete local nights;
the preview explains conflicts, and the result reports actual added/skipped nights,
added/skipped markers and local nights kept despite incoming markers. Counts are
computed again in the write transaction, so changes after preview cannot bypass
the policy. Any insertion failure rolls back every new night and marker in that
batch. Native journal export does not include alarms, raw sensor chunks or audio.
A future complete archive, encryption, provider round-trip verification,
last-attempt/success audit and large-journal streaming/partitioning remain open.

`archives/journal-v1.json` and `journal-v2.json` are independently serialized and hashed with Python for
reader fixtures. Tests do not rely only on a writer reading its own output.

## Raw motion chunk v1 and publication

`MotionChunkStore` is an independently testable persistence foundation. It does
not start sensors, run in the background, label stages or claim measurement
accuracy. `MotionFiles` supplies a credential-protected no-backup session directory
and actual Android `Os.open(O_RDONLY)` / `fstat` directory verification / `Os.fsync` directory flushing.

A stream identifies one ordered source/boot context. The fixed big-endian format:

| Field | Bytes |
| --- | --- |
| Magic `SOLM`, version 1 | 4 + 4 |
| Session, stream, boot UUIDs | 16 each |
| Chunk sequence, wall anchor epoch milliseconds, monotonic anchor nanoseconds | 8 each |
| Sample count (1–4096) | 4 |
| Each sample: monotonic nanoseconds and x/y/z IEEE 754 floats in m/s² | 8 + 4 + 4 + 4 |
| SHA-256 of every preceding byte | 32 |

Times are original observations: wall and monotonic anchors remain distinct.
Samples must have strictly increasing nonnegative sensor timestamps and finite
values. Readers enforce 128 KiB before allocation, exact length, magic/version,
count, checksum, expected session and filename/sequence agreement. A Python-packed
binary fixture fixes byte order and float bits independently of Kotlin.

1. Acquire a per-directory JVM lock and an OS file lock; initialize missing
   directories and sync their parent entries.
2. Validate the complete chunk. Write `<stream>-<sequence>.motion.pending` in the
   same directory, flush the file descriptor and force the complete staging file.
3. Atomically rename on the same filesystem to `.motion`; do not provide a
   non-atomic copy fallback. An existing identity is accepted only if bytes match.
4. Sync the directory, then acknowledge publication. Sync/rename/write failures
   propagate; an existing valid chunk is never overwritten by conflicting data.
5. Recovery inspects only this session's known filename namespace. Valid staging
   files are forced, published and directory-synced. Invalid/conflicting files are
   retained and reported. Valid final chunks are checksum-verified; missing
   sequences produce gap diagnostics, never fabricated samples. Unrelated files
   are left alone; symbolic-link payloads are rejected.

Self-describing published chunks are the raw authority. No cross-file/SQLite
atomic transaction is claimed. A later SQL timeline/index must be rebuildable
from verified files, record gaps and not advertise data as persisted before step
4 succeeds. Corrupt pending files intentionally block reusing that identity;
the recorder needs a new stream and visible gap rather than truncating evidence.
The future recorder must integrate an observable five-second checkpoint policy,
low-space behavior, bounded streaming recovery, interruption states, retention,
portable raw-data archives and deletion ownership. None is claimed by this class.

Fault injection covers interruption after file sync, rename and directory sync,
truncated staging, corrupted committed files, missing sequence, conflicting retry,
sync failure and concurrent helpers. Android instrumentation exercises the same
boundaries on the actual app filesystem. Exceptions simulate process interruptions;
they do not establish hardware power-loss behavior. Durability still relies on
filesystem/device implementations honoring flushes, requiring physical lab work.

## Documentation consulted

- Android SQLiteDatabase transactions/WAL/foreign keys:
  https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase
- Android SQLite performance and synchronization tradeoffs:
  https://developer.android.com/topic/performance/sqlite-performance-best-practices
- Direct Boot / credential storage separation:
  https://developer.android.com/privacy-and-security/direct-boot
- Android fsync/open API:
  https://developer.android.com/reference/android/system/Os
- SQLite atomic commit and assumptions:
  https://www.sqlite.org/atomiccommit.html
- SQLite WAL sidecar files:
  https://www.sqlite.org/tempfiles.html
