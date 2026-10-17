# Implementation status

17 October 2026 — alarm milestone merged in [PR #1](https://github.com/BananaJeanss/solsleep/pull/1);
manual journal and persistence foundation merged in [PR #2](https://github.com/BananaJeanss/solsleep/pull/2);
portable deletions and first-open/large-text fixes merged in [PR #3](https://github.com/BananaJeanss/solsleep/pull/3).

This is a development build. M0, M1 and M2 remain **partial**. Full reference
parity and production readiness are not claimed.

## Working workflows

- Kotlin / Compose Material 3 Expressive; API 37 target, API 29 minimum;
  edge-to-edge layout, dynamic/fallback themes and monochrome vector icons.
- Original editable sleeping Android artwork and procedural chime. No
  image-generated assets.
- Dated, weekday, anchored-interval and alternating ISO-week alarms; fixed or
  following zones; labels, enable/disable, edit/delete and skip/undo.
- Configurable ramp, vibration and bounded snooze/dismiss; actual alarm playback,
  private notification/full-screen controls and device-protected SQLite state.
- Atomic delivery claims, stale-token rejection and reboot/registration recovery.
  Failed snooze registration/publication retains the ringing occurrence.
- Plaintext alarm export, validation/preview and atomic additive disabled restore.
- **Manual Journal:** reported bed/get-up intervals, optional 1–5 restfulness,
  notes and removable Unicode tags. Duration is reported rest, not measured sleep.
  Original zones are retained; DST gaps are rejected and overlaps require an
  explicit offset. Display respects the device's 12/24-hour setting.
- Corrections append immutable revisions. Saved corrections can restore an earlier
  version as a new revision. Stale edits/deletes cannot overwrite newer work.
  Draft identity, notes and tags survive activity recreation; punctuation inside
  a tag stays intact. Save/Cancel remain visible above the keyboard at 200% text.
- Independent credential-protected journal schema with FULL/WAL transactions,
  expected-revision checks, consistent logical snapshots and explicit errors for
  inconsistent current pointers. Unknown schemas/corrupt files are retained.
  Alarm initialization never opens the journal database.
- Plaintext native journal export includes every correction; inspection validates
  before preview; missing histories restore atomically. Existing local identities
  and deletion markers win. Deletion removes all local revisions and retains a
  minimal identity marker. Journal v2 carries these markers to a clean installation;
  v1 files remain readable. Imported markers never erase existing destination
  entries. Preview explains conflicts and committed results report actual counts.
  Restore a newer archive first to block later old-archive resurrection. External
  copies cannot know later deletions and remain independent.
- Debug-only five-minute sensor probe verifies screen-off callbacks without
  changing the hard alarm. Snapshot reads/writes now share a lock, as AtomicFile
  alone provides no concurrent-access locking. Probe code/permission are absent
  from release.

## Raw persistence foundation

Bounded versioned motion chunks preserve wall/monotonic anchors, boot/stream
identity and exact finite float readings. Publication uses file flush, atomic
same-directory rename and directory flush. Process/OS locks coordinate writers
and recovery. Recovery verifies hashes and identity, retains invalid bytes,
rejects conflicting retries and reports sequence gaps.

**No production night recorder is connected to these files.** No measured sleep
stages, overnight accuracy or five-second recovery objective is claimed.
See [JOURNAL_STORAGE.md](docs/JOURNAL_STORAGE.md) for formats, independent fixtures,
publication/recovery contracts and limits.

## Verified evidence

Tested source: `e123c46d2b745a016947952eeb152d28ebde9076`.
Merge: `d0dd19d5d55fd6b39390b189d8916d97b4602c96`.
[Passing CI run, reports, captured screens, logs and debug APK](https://github.com/BananaJeanss/solsleep/actions/runs/39033124196).
Documentation updates after this run do not change app source.

| Check | Actual result |
| --- | --- |
| JVM recurrence | 19 passed |
| JVM alarm engine | 27 passed |
| JVM alarm archive/codec | 15 passed |
| JVM journal/archive/Unicode/DST/deletion markers | 16 passed |
| JVM motion codec/publication/recovery | 12 passed |
| API 29 Google APIs x86_64 emulator | All 43 passed |
| API 37 Google APIs x86_64 emulator | All 43 passed |
| Lint, debug and test APKs | Pass locally and in CI |
| Shrunk unsigned release APK / vital checks | Pass locally and in CI |
| Artwork/chime | CI reproducibility check passes |
| Final release manifest | Diagnostic components/special-use permission absent; no network/microphone/health access |
| Incremental resource probe | Temporary resource addition built under 9.8.1; original restored and clean debug rebuilt |

That is **89 JVM tests plus 86 device test executions**. Each 43-test device suite
contains ten alarm-store tests, two delivery/component tests, four alarm UI tests,
two diagnostic tests, eighteen journal-store tests, two motion-file tests and five
journal UI tests.

Evidence includes actual AlarmManager → private receiver → playback service →
durable snooze; SQLite reopen/two-helper races/transaction rollback; checked-in
schema compatibility; clean-store semantic restore; retained damaged database
bytes; explicit inconsistent-pointer failure; deleted-identity preservation;
every archive truncation; single-byte motion corruption; publication interruption
boundaries; concurrent diagnostic polling; and real create/correct/revision
restore/delete/recreation/stale-draft/200%-text workflows. Native JSON and binary
fixtures are independently serialized with Python.

Failed runs exposed a keyboard/large-text Save issue, asynchronous UI-test
selection, and unsafe concurrent AtomicFile access in diagnostics. The final
complete suites pass after the layout, focus, synchronization and locking fixes.
No test was disabled or assertion removed to obtain a pass. Emulator sensors are
virtual and host audio is disabled: this proves tested software paths, not audible
output, physical sleep accuracy or hardware power-loss durability.

Toolchain qualification uses **Gradle 9.8.1**, official distribution SHA-256,
regenerated wrapper, AndroidX Core/KTX 1.19.1, JDK 17, SDK 37.0 and Build Tools
36.0.0. The Gradle patch includes upstream build-channel/TLS security fixes.
See docs/DECISIONS.md and docs/DEPENDENCIES.md.

Local verification commands (CI also qualifies the exact source above):

```sh
./gradlew :app:clean --no-daemon
./gradlew :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-daemon
./gradlew :app:assembleRelease --no-daemon
```

CI additionally runs connectedDebugAndroidTest on both APIs. An incremental
generated-R collision was observed with Gradle 9.7.1; one resource-update probe
passes with 9.8.1. Broader incremental qualification remains open; the clean-build
recovery is documented in docs/TESTING.md. Gradle reports deprecations relevant
to a future Gradle 10 migration; the qualified wrapper remains 9.8.1.

## Latest M2 milestone

The workspace is reconnected and synchronized. Final CI18 journal PNGs are now
checked in with their original provenance. CI21 adds captured portable-restore
preview/result screens in both device artifacts. Independent v2 bytes, marker
checksum/type/UUID/count limits, deletion-only truncation, clean-store deletion
preservation, old-archive blocking, mixed batch rollback and concurrent restores
are tested.

The first expanded API 29 run exposed simultaneous first-open helpers attempting
to create the same schema, plus an alarm Save click that did not close the
scrolling editor at 200% text. Journal database opening now shares a per-path
process lock before helper initialization; write transactions remain coordinated
by SQLite. This fixes the observed single-process creation race, not arbitrary
multi-process schema management. Alarm Save now stays in a fixed footer above
the keyboard and the draft keeps its identity. Complete build and both 43-test
suites pass after these fixes. No test was disabled or assertion removed.

## Destination verification continuation (not yet qualified)

Journal export now freezes a validated snapshot before opening the destination,
writes/flushes/closes it, reopens that chosen URI and parses the returned bytes
under the archive limits. Only an exact full-document fingerprint match reports
verified success. A failed write differs from a written but unverified copy;
truncation, changed valid documents, unavailable reads and close failures cannot
become a success notice. Source records are never mutated by export.

New tests cover failure ordering and close/cancellation behavior, an actual
ContentResolver talking to a separate test-APK provider, provider denial/truncation/
valid replacement, semantic clean-store restore, and visible unverified/verified
UI outcomes. These changes are under verification; results above identify the
previous merged source. Durable attempt/success audit and provider-specific cloud
upload guarantees remain separate work.

## Remaining production and parity gates

No physical phone or Wear device is attached. Acoustic/haptic onset and routes,
locked reboot/update, Doze/OEM/process loss, overnight energy, real sensor quality
and hardware flush behavior require recorded device-lab runs. TalkBack/RTL/wide
screens and broader physical accessibility/contrast coverage remain open.

M2 still needs complete combined/raw/audio archives, encryption and portable key
recovery, automatic deletion synchronization, raw/derived/audio ownership and retention,
low-space policy, large-history streaming and provider round-trip verification.
Current exports are bounded plaintext documents. The researched
[encrypted archive plan](docs/ENCRYPTED_ARCHIVE_DESIGN.md) defines qualification,
secret lifecycle, authenticated staging and interop/fault tests; encryption is not
implemented. A real sanitized reference export fixture is needed before claiming
Sleep as Android CSV compatibility.

M3 must connect a lawful user-started recorder, checkpoints, pause/resume,
interruption/gap diagnostics and ownership before overnight validation.
Measured statistics/stages, sound analysis, watch/Health Connect acquisition,
challenges, wake checks/escalation, smart wake-up and most integrations remain
unfinished. [Traceability](docs/TRACEABILITY.md) identifies implemented subsets.

Next: provider readback verification and audit, then M2 archive/ownership work and the real capture
workflow alongside physical alarm/probe qualification. Stable production signing,
full dependency notices/SBOM and release qualification remain open. No production
signing key is committed. Instrumentation uses disposable debug installs and can
clear/uninstall their data; it must not target personal debug records.
