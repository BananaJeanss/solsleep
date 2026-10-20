# Implementation status

18 October 2026 — recording provenance and observed coverage are merged in
[PR #10](https://github.com/BananaJeanss/solsleep/pull/10), following the native
wake-up challenges, custom packs and offline voiceover in PR #9.
The exact source passed the complete API 29/37 software qualification below.

This is a **development build**. M0, M1, M2 and M3 remain partial. Full reference
parity, physiological accuracy and production readiness are not claimed.

## Download and qualification update — 20 October 2026

The reported Actions APK link returned 404 for the user. The artifact still exists
and the connected GitHub download endpoint returned a preview ZIP, but this does
not establish anonymous or durable access to the original Actions URL. No public
GitHub release exists at this checkpoint. See
[download status and recovery steps](docs/DEVELOPMENT_DOWNLOADS.md).

[PR #11](https://github.com/BananaJeanss/solsleep/pull/11) implements verified debug
bundles and gated development releases.
[PR #12](https://github.com/BananaJeanss/solsleep/pull/12) implements persistent
recording-export receipts. Both remain drafts, unmerged and **unqualified**:
CI52 and CI53 built successfully but both API 29 and API 37 jobs failed.
The earlier lifecycle cleanup change is therefore not an established fix.
The last fully qualified app milestone remains PR #10, recorded below.

The current execution environment cannot run commands, inspect downloaded ZIP
reports, attach a persistent local APK or compile changes. GitHub job status and
text logs were inspected remotely. The logs report failing native tests and
reference the archived reports; they do not identify the failed assertions.
No root cause, native pass count or fix is claimed from those logs. This is a
concrete continuation blocker, separate from the physical-device gates.

## Current tracking and wake-task status

| Feature | Implemented status |
| --- | --- |
| Phone motion | Actual raw accelerometer observations, controls, interruption recovery, saved checks and separate recording backups |
| Manual journal | Reported rest intervals, corrections, notes/ratings/tags and verified Journal JSON portability |
| Sleep analysis | Validated per-segment observation counts, nearby-reading durations, gaps and provenance; morning/sleep/awake/onset/stage analysis remains unimplemented |
| Sonar | Research only; no overnight/sonar microphone acquisition or calibrated acoustic/DSP workflow |
| Wake-up challenges | Eight native puzzle types, NFC, relative voice level, experimental offline voiceover and custom JSON packs implemented; software qualified, physical gates below |
| Wear / Health Connect | Acquisition adapters and physiological validation remain unimplemented |

## Qualified recording provenance and coverage software milestone

New acquisition stores the original ZoneId, requested interval and installed
base APK SHA-256 in versioned stream metadata. Additive capture schema
1/2→3 migration leaves legacy provenance null. Separate SOLR v2 backups preserve
known/unknown mixed segments; old v1 reading and byte-identical writing remain.

A native recording-coverage screen rebuilds per-segment counts and nearby-reading
durations from validated raw files. Missing chunks and recorded interruptions
break joins; absent cadence stays unknown. It does not infer sleep or whole-night
coverage. Fingerprints bind the policy and canonical input. Corruption is distinct
from a valid empty recording. See [RECORDING_COVERAGE](docs/RECORDING_COVERAGE.md)
for the exact threshold and migration/format contract.

Tested source: `5dc67cdb8a04a89e969635310024353e6d0af14a`.
Source tree: `34dac0210693644c8551a16562f84e989f78485b`.
Merge: `dccb8abb4504067fbb666526048fbb7c76c32d47`, with the identical tree.
[CI48 reports, captures, logs and development APK](https://github.com/BananaJeanss/solsleep/actions/runs/39119133196).
CI checked out PR merge `0610654ce77f916abe870d182d1c989d13bcc182`, whose
tree was independently compared with this source and the final merge. Subsequent
qualification documentation and exact PNG copies change no app, test, schema,
build or tool code.

| Check | Actual result |
| --- | --- |
| JVM domain/storage-protocol/format/DSP cases | All 203 passed; 25 new provenance/coverage cases |
| Independent Python motion archive / fixtures | All nine passed; v1/v2 fixtures exact |
| API 29 Google APIs x86_64 emulator | All 116 passed |
| API 37 Google APIs x86_64 emulator | All 116 passed |
| Final XML failures / errors / skipped | Zero across JVM and both native suites |
| Lint / debug and test APKs | Pass; zero lint issues |
| Shrunk unsigned release | Pass locally and in CI |
| Original vector/chime/voice asset checks | Pass; no image-generated assets |
| Native 200% text | Both final light/dark captures reviewed; exact PNG copies retained |

Actual XML reports and downloaded artifact SHA-256 digests were inspected. The
native additions exercise real SQLite 1/2→3 migrations, independent schema 3,
unknown/corrupt payload retention, provider v2 readback/restore, actual installed
APK provenance and known/legacy/corrupt coverage UI with recreation. All previous
tests remain enabled. See [TESTING](docs/TESTING.md) and
[SCREENSHOTS](docs/SCREENSHOTS.md) for artifact/capture provenance.

CI47 passed its automated suites, but visual review caught an inherited source
caption inventing a 100 ms request for legacy records. Raw details now show only
the retained request or explicit unknown. The legacy UI regression forbids the
invented caption; corrected source completed all checks again in CI48. No test,
assertion or supported platform was disabled. Observed continuity is not evidence
of useful mattress signals, sleep recognition, sonar or watch acquisition.

## Previously qualified wake-up challenge software milestone

Per-alarm opt-in Easy/Normal/Hard, 1–12 rounds, selected task mix, 5–40% initial
sound and rehearsal are implemented. Both Snooze and Dismiss require a durable
host-validated task. Eight native puzzle types, visible NFC/microphone tasks,
original offline voiceover references/own WAV import and native JSON custom packs
are wired to actual alarm state. Original ringing/quiet starts survive recovery;
failed snooze publication retains ringing and completed proof. See
[WAKE_CHALLENGES](docs/WAKE_CHALLENGES.md) and [VOICEOVER](docs/VOICEOVER.md).

The DP alarm database migrates 1→2 additively. Configured logical rules/archives
are v2; old v1 definitions remain readable and byte-stable. Frozen packs and voice
references are included within the same bounded 512 KiB alarm archive; excess
fails before writing, never silently omits content. Task definitions/references
are before-unlock data and plaintext in exports. Attempt audio is memory-only;
task progress and misses are durable.

No JavaScript, WebView, network, camera, call-placement, overlay, device-admin or
accessibility-service permission is added. NFC and microphone are optional; Record
asks runtime consent and capture stops on leaving. Public `ACTION_DIAL` opens the
system dialer; it does not place a call or guarantee keyguard bypass. System
emergency/phone controls remain available. Normal app actions are protected;
Android force-stop/power/uninstall remain under owner control.

Tested source: `3ac50fd63cee4650dcb6a661482c3ee0c2d8ff46`.
Source tree: `0efa194f20c238b8b96ef11cf80d107c66a407bb`.
Merge: `421af1706e5e4e1cd721615f1e490f9772a69d91`, with the identical tree.
[CI45 reports, captures, logs and development APK](https://github.com/BananaJeanss/solsleep/actions/runs/39098002133).
CI checked out PR merge `dd91d50a048a0e1f5549d9ccc21bc5aafbbdbdb4`,
whose tree was independently compared with this source and the final merge.
PR #9's immediate documentation/capture follow-up changed no app, test, schema,
build or tool code. PR #10 is qualified separately above.

| Check | Actual result |
| --- | --- |
| JVM domain/storage-protocol/format/DSP cases | All 178 passed, including 46 new challenge/voice cases |
| Independent Python motion archive / fixtures | All four passed; independent fixture exact |
| API 29 Google APIs x86_64 emulator | All 108 passed |
| API 37 Google APIs x86_64 emulator | All 108 passed |
| Final XML failures / errors / skipped | Zero across JVM and both native suites |
| Lint / debug and test APKs | Pass; zero lint issues |
| Shrunk unsigned release | Pass locally and in CI |
| Original vector/chime/voice asset checks | Pass; no image-generated assets |
| Native 200% text and fixed phone access | Actual captures from both final artifacts reviewed |

The final native suite retains all previous 93 cases and adds seven SQLite/
permissions/reference cases, three actual Activity lifecycle job-ownership cases
and five real alarm/settings/challenge UI cases per API. Reports and ZIP digests
were inspected, not inferred from a green job badge. See [TESTING](docs/TESTING.md)
and [SCREENSHOTS](docs/SCREENSHOTS.md) for provenance and retained captures.

Review caught a raw microphone producer dropping the final complete 100 ms frame;
the shared producer now counts it, with exact sustained-speech/clipping cases.
Pause/Stop cancels owned audio jobs directly, and stale completion cannot clear a
replacement job. Earlier CI41–44 UI harness failures were corrected by using real
AlarmManager/service ownership, bounded Compose-root waits, the notification's
canonical singleTask intent and actual stale-intent lifecycle observation. No
assertion, test or platform was disabled; both full final suites passed.

Do not treat compilation or synthetic similarity tests as physical qualification.
Real NFC, room/alarm bleed, voice score accuracy, input privacy/route contention,
incoming call/audio focus, locked emergency UI, speaker/haptic gain and OEM/device
recovery remain open with no attached hardware. Camera smile/barcode, movement,
Google image reCAPTCHA, dream-note, travel profiles and external app task grants
are unimplemented. Sonar, sleep inference and watch acquisition are unchanged.

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
m/s² including gravity. New schema 3 segments freeze the requested interval,
original ZoneId and installed base APK hash. Schema 1/2 history keeps unknown
provenance; UTC/monotonic anchors remain exact and restore never fills absent
fields from current settings. The request is about 10 Hz, not guaranteed delivery.
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

See [MOTION_CAPTURE.md](docs/MOTION_CAPTURE.md), [schema 3](schemas/capture/3.sql)
and its retained independent schema 1/2 migration fixtures.
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

## Previous recording-portability evidence

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
- **M3/M4:** provenance and conservative observed coverage are software-qualified
  above. Next are transparent morning analytics, source-quality policies and
  validated sleep/awake inference. Signal usefulness and accuracy require real
  placement trials and consented labeled reference data; neither is available
  here. The wake-up challenge milestone above supersedes the earlier
  math-only proposal; remaining task variants are tracked in TRACEABILITY.
  There is no measured
  hypnogram, sleep score, smart wake-up, sound analysis or clinical claim.
- **M5 and parity:** implement watch/Health Connect acquisition and remaining
  reference families. A real consented sanitized reference CSV is still missing
  before importer compatibility can be claimed.
- **Release:** stable production signing, full dependency notices/SBOM, physical
  matrix, TalkBack/RTL/wide layouts and release-candidate qualification. No
  production signing key is committed. Independent software work remains in PLAN.md;
  hardware gates are not a claim the remaining coding work is finished.
