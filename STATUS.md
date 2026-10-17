# Implementation status

18 October 2026 — [lossless recording backups and recoverable restore, PR #8](https://github.com/BananaJeanss/solsleep/pull/8), merged after exact-source qualification.

This is a **development build**. M0, M1, M2 and M3 remain partial. Full reference
parity, physiological accuracy and production readiness are not claimed.

## Current tracking and wake-task status

| Feature | Implemented status |
| --- | --- |
| Phone motion | Actual raw accelerometer observations, controls, interruption recovery, saved checks and separate recording backups |
| Manual journal | Reported rest intervals, corrections, notes/ratings/tags and verified Journal JSON portability |
| Sleep analysis | Observed-coverage/morning analytics, sleep/awake/onset/stages remain unimplemented |
| Sonar | Research only; no microphone acquisition or calibrated acoustic/DSP workflow |
| Wake-up CAPTCHAs | C01–C11 unimplemented; optional durable math is the first planned vertical slice |
| Wear / Health Connect | Acquisition adapters and physiological validation remain unimplemented |

## Can it be used overnight?

Alarms and the manual journal are implemented. **Phone motion** can now save real
accelerometer observations with pause/resume/finish and explicit interruptions.
It is experimental, not a completed sleep tracker: there are no sleep statistics,
sleep stages, snoring analysis or watch measurements. Keep a separately trusted
wake-up alarm while physical qualification is unfinished.

No physical phone/watch is attached here (`adb devices -l` was empty at this
checkpoint). Audible/haptic wake-up, OEM background behavior, 12-hour battery and
temperature, real placement/signal usefulness and hardware power-loss durability
have not been qualified. Emulator callback tests do not close these gates.

Raw recordings are **separate from Journal exports**. Finish a recording and use
Export recording to save an unencrypted `.solmotion` file. The new workflow
validates every original chunk/source/event, verifies chosen-provider readback and
supports explicit staged restore with interrupted-publication retry. Combined/
encrypted backups and portable capture deletion propagation remain open. Do not
uninstall or switch signers without verified external recovery copies.
Instrumentation uses dedicated disposable installs and may clear/uninstall data.

## Working alarm workflows

- Compose Material 3 Expressive, API 37 target / API 29 minimum, edge-to-edge,
  dynamic/fallback themes and monochrome original sleeping Android vectors.
- Dated, weekday, anchored-interval and alternating ISO-week recurrence; fixed
  or following zones, labels, edit/delete, enable/disable and skip/undo.
- Actual local playback, ramp, vibration, bounded snooze/dismiss and private
  notification/full-screen actions. Original deadline/ringing start survives
  recovery; failed snooze registration/publication retains the ringing occurrence.
- Device-protected FULL/WAL SQLite, atomic delivery claims, stale tokens and
  registration/reboot/update/time-change recovery. No health store is opened by
  alarm initialization. Plaintext alarm preview/export and additive disabled restore.
- An API 29 full run exposed shutdown racing an undelivered foreground start.
  Ringing now acknowledges foreground execution before its ready marker and
  stops only the current start ID. An older async result cannot cancel a newer
  request known to Android. The same guarded shutdown is used by capture.

## Manual journal and portability

- Remembered bed/get-up intervals, optional restfulness, Unicode notes/tags,
  original IANA zones and device 12/24-hour display. Duration is reported rest,
  not measured sleep. DST gaps reject; overlaps require an explicit offset.
- Immutable corrections, expected revisions, saved-correction restore as a new
  revision, persistent draft identity and controls usable above the keyboard at
  200% text. Deletion removes local revisions and retains a minimal identity marker.
- Independent credential-protected SQLite with retained corruption/unknown
  schemas, consistent snapshots, explicit missing-pointer errors, first-open
  process locks, rollback/CAS tests and no destructive reset.
- Native journal v2 preserves complete histories and portable deletion markers;
  v1 stays readable. Atomic additive restore keeps local identities and reports
  conflicts. Imported markers never erase live destination entries. Restore newer
  archives first; an older copy cannot know later deletions.
- Bounded 8 MiB / 10,000 revision / 10,000 marker archives; strict UTF-8, fields,
  histories and checksums. Byte/count preflight precedes full allocation; oversized
  output fails without omitting data or writing a destination. Preview reads IDs.
- Journal output is closed, reopened through the chosen provider, inspected and
  fingerprint-compared before verified success. Failed write and written but
  unverified outcomes differ. Independent local attempt/verified history cannot
  block actual recovery when its metadata store fails. Unavailable differs from
  known absence. Readback proves that response, not future cloud retention/upload.

## Experimental phone motion

Use **Tonight → Phone motion → Set up a motion recording**. Consent precedes the
private user-started `specialUse` foreground service. No sticky/boot auto-resume,
network, microphone, health/body, activity-recognition or Bluetooth access is added.
Notification access/channel are required and rechecked; Finish opens from a
session-scoped notification. Play subtype review is still required for that channel.

One durable open session, idempotent start identity and expected revisions protect
state. Separate source segments retain boot/stream identities, sensor descriptors,
wall/monotonic anchors and original finite float vectors in
m/s² including gravity. The current request is about 10 Hz, but schema 2 does not
store a per-source requested interval, producing build or original IANA zone. UTC
and monotonic times remain exact; absent provenance is not invented on restore.
Duplicate/late/invalid readings stay missing; timestamp
gaps and accuracy changes are explicit. A new stream is required after resume.
PREPARING becomes RECORDING only after a real raw batch publishes. A dead owner
becomes INTERRUPTED; reopening never invents capture or automatically resumes it.

At most 128 samples per batch, with approximately four-second flushing. Files use
checksums, file sync, atomic same-directory rename, directory sync and process/OS
locks. Exact retries preserve sequence. Files precede the rebuildable SQL index;
there is no file/database atomicity claim. Saved checks stream bounded files and
report corruption, sequence/timestamp overlap, changed anchors, missing source
metadata or indexed samples without files. Valid bytes remain retained.

A segment stops at 12 hours, 15 seconds without fresh readings, notification
access loss, a clock difference over two seconds, a storage failure or its free-
space/raw-size bounds. A bounded wake lock is released on pause/finish/interruption.
These are software policies; the five-second crash-tail/physical flush and battery
objectives remain unqualified. Current raw bound is 64 MiB per session, with a
64 MiB free-space check before writes. No primary records are silently evicted.

A completed/failed session can enter durable DELETING before raw removal. Only
owned regular files in its UUID namespace are removed under the existing lock;
unknown files/symlinks retain a retryable error. Directory sync precedes SQL
metadata removal. A local identity/request marker now commits with metadata
deletion, blocking an older copy from resurrecting that identity on this install.
The empty lock inode/directory remains; unlink is not secure erasure. Manual
journals and alarms are untouched. Recording history uses bounded pages; source/event and file-path metadata pagination remain open.

See [MOTION_CAPTURE.md](docs/MOTION_CAPTURE.md), [schema 2](schemas/capture/2.sql)
and its non-destructive schema 1 migration fixture.
The older debug-only five-minute probe still stores diagnostic counters, not a
night. Its components are absent from release; the actual recorder and its
SPECIAL_USE permission are intentionally present in release now.

## Finished-recording portability

The separate plaintext SOLR v1 format preserves the original finished state,
identities/revision/timestamps, all source descriptors/counters/anchors, all events
with original order/sequence/detail and exact SOLM raw bytes. A strict bounded
sequential container has no archive paths or compression; whole-envelope and
per-chunk SHA-256 plus exact EOF reject corruption. It is not authentication or
encryption. Limits: 2 MiB metadata, 512 sources, 10,000 events, 32,768 chunks and
64 MiB raw. Oversized originals remain retained; nothing is silently omitted.

Export freezes/validates a private spool before opening the provider output,
closes it, reopens the chosen URI, validates and compares exact fingerprints.
Written-unverified differs from failed write. Current raw-export outcomes do not
yet have persistent receipts; journal receipts still describe Journal JSON only.

A fully validated/synced private staging copy precedes preview and explicit
Restore. No active capture may run. Existing session/request/source identities and
local deletion markers win. Already-present raw files must be an exact subset;
conflicting/unknown bytes remain retained. Exact chunk publication precedes one
transaction inserting all metadata. An injected interruption or SQL rollback
retains invisible partial raw files and the full recovery archive for an identical
retry after reopening. There is no file/SQLite atomicity claim or automatic
acquisition/alarm start. Discard removes the recovery copy; partially published
orphan files are conservatively retained. Automatic orphan reclamation is open.

Capture schema 1→2 adds original imported event sequences beside local SQLite
row IDs, avoiding collisions, and minimal local deletion identities. Migration
retains original rows. Deletions before migration cannot be reconstructed, and a
clean installation restoring an older recording file cannot know later deletions.
Native preview/restore/export/low-space/conflict/error controls and 200% text are
implemented. See [MOTION_ARCHIVE.md](docs/MOTION_ARCHIVE.md), the independent
Python fixture and `tools/inspect_motion_archive.py` for an Android-independent
reader. The user chooses document access through SAF without broad storage access.

## Verified evidence

Tested source: `36ca8777a4f9b696af4f9cd632f2c99bb5b0e188`.
Merge: `8597a92b75a0ae54243926b3b881f02cb54deaf4`.
[CI39 actual reports, captures, logs and debug APK](https://github.com/BananaJeanss/solsleep/actions/runs/39076468922).
Documentation/capture copies after this run change no app source.

| Check | Actual result |
| --- | --- |
| JVM recurrence / alarm engine / alarm archive | 19 / 27 / 15 passed |
| JVM journal/archive / destination / export audit | 17 / 8 / 9 passed |
| JVM raw motion / capture buffering and policy | 12 / 13 passed |
| JVM recording archive and independent-fixture interoperability | 12 passed |
| Python independent inspector / fixture reproducibility | 4 tests passed; fixture exact |
| API 29 Google APIs x86_64 emulator | All 93 passed |
| API 37 Google APIs x86_64 emulator | All 93 passed |
| Lint / debug and test APKs | Pass; zero lint issues |
| Shrunk unsigned release / vital checks | Pass locally and in CI |
| Original SVG/chime reproducibility | Pass in CI; no image-generated assets |
| Release manifest | Private recorder specialUse; no diagnostic/test-provider or network/microphone/health/Bluetooth permissions |
| Native 200% recording restore/export controls | Actual captures reviewed for both APIs |

**132 JVM tests, four Python tests and 186 device test executions**, zero
failures/errors/skips in final XML. Each native suite retains the prior 79 cases
and adds 13 real archive/storage/migration/provider/fault-policy cases plus a
native 200% preview/restore case. These use real ContentResolver, SQLite and raw
files. Low-space and process-loss exceptions are injected software checks, not
physical full-disk/power-loss qualification. Host audio is disabled and sensors
are virtual. The archive UI fixture is explicitly synthetic original test data.

Source checks include complete metadata and exact float/chunk bytes; source-index
lag versus missing acknowledged samples; malformed metadata with a recomputed
checksum; corruption/truncation/limits; provider denial/replacement/readback;
complete preparing-copy recovery; interruption after synced raw publication;
SQL rollback after all files publish; exact retry; no local overwrite or deletion
resurrection; source/request collisions; live-capture rejection; v1/v2 independent
schema fixtures and migration/reopen; unchanged existing alarm/capture/journal
suites. No assertion was disabled or test ignored.

Local checks caught mixed-type SQLite fixture argument inference; explicit types
fixed compilation. Five free-space lint warnings led to a documented StatFs
available-byte safety-floor policy and a distinct retained low-space outcome,
with no broad suppression. CI38 at the superseded source built successfully;
its device jobs were cancelled by the refined-source push, so partial XML from
that run is not claimed as full qualification. PR #7/CI36 established the earlier
120/79 capture/foreground baseline; PLAN.md retains that source and evidence.

Local commands (JDK 17 / SDK 37.0 / Build Tools 36.0.0):

```sh
./gradlew :app:clean --no-daemon
./gradlew :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-daemon
./gradlew :app:assembleRelease --no-daemon
```

CI runs connectedDebugAndroidTest separately on both APIs. Gradle 9.8.1 (official
checksum), AGP 9.4.1, Kotlin 2.4.21 and AndroidX Core/KTX 1.19.1 remain pinned.
The Gradle 10 deprecation banner is retained. A prior incremental generated-R
collision and the clean-build recovery remain documented in TESTING.md; one
resource probe is not proof every upstream incremental case is fixed.

## Next work and concrete gates

- **Physical reference phone:** no attached device; record actual acoustic/haptic
  onset, locked boot/update, OEM/Doze/process loss, 12-hour power/temperature,
  real sensor placement/quality, storage faults and assistive interaction.
  [The lab protocol](docs/device-lab/README.md) and blank NOT RUN sheet are ready.
- **M2 software:** combined all-record/audio archives, encryption and portable
  key recovery, portable capture deletion propagation, raw-export receipts,
  recovery/orphan retention, provider/destination identity, large-history streaming
  and broader pagination. Single finished-recording plaintext portability is now
  qualified; this is not a complete combined backup. Encryption is researched, not
  implemented. Minimum-phone KDF cost also needs physical hardware.
- **M3/M4:** observed coverage and transparent morning analytics, validated
  sleep/awake inference, versioned acquisition provenance and further sensor quality
  policies. Optional durable math is the first planned CAPTCHA slice; all task
  families remain unimplemented. There is no measured
  hypnogram, sleep score, smart wake-up, sound analysis or clinical claim.
- **M5 and parity:** implement watch/Health Connect acquisition and remaining
  reference families. A real consented sanitized reference CSV is still missing
  before importer compatibility can be claimed.
- **Release:** stable production signing, full dependency notices/SBOM, physical
  matrix, TalkBack/RTL/wide layouts and release-candidate qualification. No
  production signing key is committed. Independent software work remains in PLAN.md;
  hardware gates are not a claim the remaining coding work is finished.
