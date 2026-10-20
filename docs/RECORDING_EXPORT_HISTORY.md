# Local recording-export receipts

Implementation under qualification, 19 October 2026. Exact final source, runs,
reports and captures belong in STATUS.md before merge. This extends separate
finished-recording SOLR v1/v2 exports; it does not combine or encrypt backups.

## Human workflow

Finish a recording, choose **Export recording** and a document destination.
Picker cancellation starts no attempt. The existing frozen, validated private
spool is written, flushed and closed; matching provider readback must pass the
complete SOLR validator before success. Original capture rows/events/chunks are
never rewritten by export or receipt inspection.

**Recording export history** on each finished recording opens a native screen
showing its last attempt and last verified copy independently. A later failed
attempt does not erase earlier verification. Timestamps respect device locale,
12/24-hour preference and show the display zone. The screen scrolls at large
text, retains fixed Close/system Back access and supports selectable identities.

| Stored stage | Meaning |
| --- | --- |
| STARTED | An explicit export began; completion is unknown |
| WRITTEN | Output closed successfully; verification was interrupted or its result could not be saved |
| VERIFIED | Exact validated archive content matched provider readback after output close |
| FAILED_WRITE | Snapshot/write/flush/close did not complete; no verified receipt |
| UNVERIFIED | Output closed, but readback could not be verified |

Nonterminal rows stay nonterminal after process death. They are not promoted by
file presence, timestamps or opening the history screen. A retry is a new explicit
export attempt; the app does not reopen/truncate a previous destination silently.
A verified receipt proves matching bytes **at that time**, not current document
availability, cloud upload/retention or hardware power-loss durability.

## Destination and privacy contract

Receipts are in independent credential-protected `motion-exports.db`, schema 1,
FULL/WAL, with an independent SQL fixture. Each immutable identity includes
attempt/session UUIDs, monotonic database sequence, start/update UTC instants,
destination identity and phase. Terminal transitions use expected-stage guards.
Sequence orders attempts when wall clocks move backward; each attempt's updated
time clamps to its own last timestamp. Queries are indexed and limited to the
last attempt/verified row for the requested recording.

For a bounded content URI, destination identity is SHA-256 of its original UTF-8
URI string, plus a validated provider authority and optional validated display
name from `OpenableColumns`. A provider may refuse metadata queries; an unknown
name cannot prevent export. Equal URI hashes identify equal original strings,
not equivalent/canonical URIs or proof of a provider's identity. A URI hash is
correlatable local metadata, not anonymization. Raw URI strings, URI tokens and
persisted grants are not stored. No network permission or automatic external
availability check is added. File names may themselves be personal information.

Verified rows retain the SOLR **content fingerprint** specified by the archive
format (its checksummed content, excluding its final 32-byte checksum field).
This differs from SHA-256 of the whole `.solmotion` file including that field.
Checksums detect accidental changes, not an adversary rewriting and rehashing
an archive. Receipts themselves contain no raw vectors, health estimates or audio.

Receipt data is local only and excluded from SOLR. Restoring observations does
not invent export history from another installation. History unavailable is
distinct from a known empty history; earlier app versions recorded no receipts.
No alarm component depends on this database. Corrupt/future receipt files are
retained without destructive reset. An audit start/phase/completion failure does
not prevent a verified external recovery copy; the outcome explicitly reports
that its receipt could not be recorded.

User-confirmed recording deletion commits the existing durable DELETING intent,
removes its local receipts, then follows the raw/metadata deletion protocol. This is not an atomic
cross-database transaction. A later raw-deletion failure can leave the original
recording with cleared receipts, as authorized by deletion. A damaged receipt
database cannot veto raw deletion; the app warns that those local receipts could
not be removed and retains their database. External provider documents and other
sessions' receipts are never removed. Uninstall removes private receipt storage.

## Verification and remaining work

Core cases cover phase boundaries, unrelated/unclosed/repeated completion,
cancellation, each audit failure, original error preservation, metadata bounds
and cross-session summary rejection. Native cases use real SQLite reopen,
independent fixture, cold-start/terminal races, trigger rollback, clock rollback,
future/corrupt bytes and scoped deletion. Actual ContentResolver outcomes cover
write denial, unavailable/truncated/replaced readback and original observations
retained; a damaged receipt store still permits verified recovery. Native UI
checks known/unavailable histories and 200% recreation using a labeled synthetic
fixture. Both complete API suites and actual capture review remain required.

All-record manifests, portable recording deletion propagation, combined/encrypted
archives, receipts portability/retention controls, true destination availability
and commercial provider/physical storage trials remain separate plan work.

Primary documentation reviewed:

- https://developer.android.com/training/data-storage/shared/documents-files
- https://developer.android.com/reference/android/provider/OpenableColumns
- https://developer.android.com/reference/android/content/ContentResolver
- https://developer.android.com/reference/android/database/sqlite/SQLiteOpenHelper
- https://sqlite.org/wal.html
