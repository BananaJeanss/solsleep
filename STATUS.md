# Implementation status

18 October 2026 — [phone motion capture and foreground lifecycle fixes, PR #7](https://github.com/BananaJeanss/solsleep/pull/7), merged after exact-source qualification.

This is a **development build**. M0, M1, M2 and M3 remain partial. Full reference
parity, physiological accuracy and production readiness are not claimed.

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

Raw recordings are **not included in Journal exports** and have no portable
recovery format yet. The recording consent explains this before acquisition.
Do not treat an installation holding irreplaceable raw data as disposable or
uninstall it to switch debug signers. Instrumentation uses dedicated test installs
and may clear/uninstall their data.

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
wall/monotonic anchors, requested interval and original finite float vectors in
m/s² including gravity. Duplicate/late/invalid readings stay missing; timestamp
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
metadata removal. The empty lock inode/directory remains; unlink is not secure
erasure. Manual journals and alarms are untouched. Recording history uses bounded
pages; source/event and file-path metadata pagination remain open.

See [MOTION_CAPTURE.md](docs/MOTION_CAPTURE.md) and the [SQL fixture](schemas/capture/1.sql).
The older debug-only five-minute probe still stores diagnostic counters, not a
night. Its components are absent from release; the actual recorder and its
SPECIAL_USE permission are intentionally present in release now.

## Verified evidence

Tested source: `8c2460e33acab009308992ca33955a9b4c838406`.
Merge: `9190d971f6963fe47ffc71071401266783dd24bb`.
[CI36 reports, device captures, logs and debug APK](https://github.com/BananaJeanss/solsleep/actions/runs/39073894634).
Documentation/capture copies after this run change no app source.

| Check | Actual result |
| --- | --- |
| JVM recurrence | 19 passed |
| JVM alarm engine | 27 passed |
| JVM alarm archive | 15 passed |
| JVM journal/archive | 17 passed |
| JVM raw motion codec/publication/recovery | 12 passed |
| JVM destination transfer | 8 passed |
| JVM export audit | 9 passed |
| JVM capture buffering/policies/control/anchor/time bounds | 13 passed |
| API 29 Google APIs x86_64 emulator | All 79 passed |
| API 37 Google APIs x86_64 emulator | All 79 passed |
| Lint / debug and test APKs | Pass; zero lint issues |
| Shrunk unsigned release / vital checks | Pass locally and in CI |
| Original artwork/chime reproducibility | Pass in CI; no image-generated assets |
| Release manifest | Private recorder + specialUse; no diagnostic/test-provider components or network/microphone/health/Bluetooth permissions |
| Native saved/200% captures | Reviewed and copied for both APIs with source attribution |

**120 JVM tests and 158 device test executions**, zero failures/errors/skips in
the downloaded final JUnit reports. Each 79-test native suite has 10 alarm-store,
3 delivery/foreground, 4 alarm UI, 2 diagnostic, 20 journal-store, 2 raw-file,
7 journal UI, 6 provider, 8 export-history, 12 capture-store and 5 recording UI/
framework-sensor cases. Host audio is disabled and sensors are virtual.

Evidence includes actual AlarmManager delivery/snooze, repeated framework starts
racing shutdown, screen-off SensorManager callbacks, pause/resume/new segments,
unchanged hard alarm deadlines, actual file/vector/source readback, stale action
rejection, unexpected service-stop retention, explicit old-owner interruption,
native deletion and 200% consent/control/completed-state workflows. Storage tests
cover real SQLite/schema/reopen/races/rollback/corruption and mismatched source/
file progress. No test or assertion was disabled to obtain the pass.

Earlier failures exposed a malformed extended-year date bound, a screenshot
storage import, and UI checks racing rendered state; all were fixed. The API 29
foreground crash above was fixed rather than dismissed as a flaky test. Earlier
journal milestones are documented in PLAN.md and PRs #1–#6.

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
- **M2 software:** complete combined/raw/audio archives, encryption and portable
  key recovery, deletion propagation, retention, provider/destination identity,
  large-history streaming and broader pagination. Encryption is researched, not
  implemented. Minimum-phone KDF cost also needs physical hardware.
- **M3/M4:** observed coverage and transparent morning analytics, validated
  sleep/awake inference and further sensor quality policies. There is no measured
  hypnogram, sleep score, smart wake-up, sound analysis or clinical claim.
- **M5 and parity:** implement watch/Health Connect acquisition and remaining
  reference families. A real consented sanitized reference CSV is still missing
  before importer compatibility can be claimed.
- **Release:** stable production signing, full dependency notices/SBOM, physical
  matrix, TalkBack/RTL/wide layouts and release-candidate qualification. No
  production signing key is committed. Independent software work remains in PLAN.md;
  hardware gates are not a claim the remaining coding work is finished.
