# Portable recording deletion history

Implementation candidate, 20 October 2026. This prerequisite for combined recovery
adds a separate identity-only plaintext file; it is not an all-record backup.

## User workflow

Phone motion → Recording deletion history → Export deletion history uses the
system document picker. A transactional snapshot includes completed local deletion
identities and durable DELETING intents, even if file erasure is not finished.
The output is closed, reopened and fully validated before success. A denied,
truncated or replaced readback is unverified, not a successful recovery copy.
No deletion-history export receipt or retained URI grant is created.

On a fresh installation choose deletion history to import **before** older
recording backups. Inspecting or closing the preview changes nothing. Confirmation
adds only absent identities in one SQLite transaction; retry is idempotent.
Existing recordings always win: an imported marker never deletes a recording or
an external file. A match on either recording ID or start-request ID prevents
overwrite/reuse. Conflicting local identity pairs are skipped and counted.

The preview displays proposed additions, exact existing pairs, present recordings
kept, conflicting pairs skipped and a selectable whole-file SHA-256 fingerprint.
Commit repeats classification under its write transaction, so concurrent changes
can change the final counts. An active/paused/interrupted recording blocks import
and preview; exporting remains possible. A staged SOLR restore is re-inspected
after importing so its old conflict projection cannot enable an obsolete action.

The preview is held in a ViewModel across Activity recreation. Process death
before confirmation loses only the preview and requires choosing the file again;
no grant or pending write intent is necessary. Process loss after a committed
import is safe to retry. No current recording is silently removed.

## SOLD version 1

All integers are big-endian. The extension is .soldelete and the picker MIME is
application/octet-stream. This intentionally small prerequisite does not change
SOLR v1/v2, capture SQLite schema 3, raw files, journal or DP alarm storage.

| Bytes | Meaning |
| --- | --- |
| 0–3 | ASCII SOLD |
| 4–7 | unsigned format version, exactly 1 |
| 8–15 | signed export creation epoch milliseconds |
| 16–19 | unsigned pair count, 0–10,000 |
| following 32 bytes per pair | 16-byte recording UUID then 16-byte request UUID |
| final 32 bytes | SHA-256 of every preceding byte |

Pairs are ordered by canonical lowercase recording UUID text. Recording IDs and
request IDs are each unique. Readers reject unknown versions, noncanonical order,
duplicate/cross-paired identities, incorrect count/length/checksum, trailing bytes,
truncation and inputs over 320,052 bytes. The Kotlin parser additionally rejects
non-progressing streams. No compression, paths, general JSON object parser or
unbounded payload allocation is used. A snapshot makes a defensive immutable copy.

The creation time describes this export, **not** when a deletion happened.
Historical deletion time was not stored; it remains unknown. Nothing reconstructs
deletions predating the original capture deletion schema. Checksums detect byte
changes; they do not authenticate the author. Import only a file you trust.
Imported markers are monotonic and may block later restore of a matching identity.

Independent Python producer/inspector tests and the exact textual hex fixture
exercise the same bytes without using Kotlin production code. The fixture has two
synthetic UUID pairs and epoch export time; it contains no user observations.
Run python3 -m unittest discover -s tools -p 'test_*.py' and :core:test; actual
provider/SQLite/UI cases run in both complete API29/37 instrumented suites.

## Failure and retention boundaries

Exports never mutate source identities or records. Snapshot export fails explicitly
above 10,000 entries, rather than silently truncating; partitioned large-history
export remains future work. Future/corrupt SQLite files are retained, with no
destructive migration/reset. SQLite FULL/WAL settings and the original schema
remain in use. Local already-confirmed DELETING intents remain retryable.

Imports are all-or-nothing for eligible new markers. SQL failure rolls back every
new row. Exact retry adds nothing twice. There is no clock-based last-writer-wins
rule. Identity conflicts keep the destination pair unchanged. Separate recording
restore already checks both IDs in deleted_sessions; it therefore refuses an
older SOLR file after the imported marker has committed, without publishing files.

Physical power-loss/full-disk durability, cloud future availability and commercial
document-provider behavior remain separate gates. The independent file is
unencrypted; combined all-record manifests, encryption, receipt portability and
large-history partitioning remain unfinished.

Primary Android documentation reviewed:
https://developer.android.com/training/data-storage/shared/documents-files
The implementation uses per-document SAF access and no broad storage permission.
