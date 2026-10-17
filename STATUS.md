# Implementation status

17 October 2026 — alarm milestone merged in [PR #1](https://github.com/BananaJeanss/solsleep/pull/1).

## Current M2 work (verification in progress)

The next slice implements a manual Journal tab: reported intervals with named
zones/DST handling, nullable ratings, notes/tags, saved corrections, draft
recreation, deletion, plaintext native export and validated additive restore.
A separate credential-protected schema keeps alarms independent. Immutable
revisions, expected-revision writes and transaction rollback prevent lost edits;
local deletion identities prevent old-archive resurrection on this installation.

The raw-data foundation adds bounded motion chunks, original wall/monotonic
anchors, SHA-256, file flush/atomic rename/directory flush and recovery that keeps
invalid files and reports gaps. No production recorder is connected to it.
See [storage contract and limitations](docs/JOURNAL_STORAGE.md).

Local JVM suite: **81 passed** (61 existing + 9 journal + 11 motion-chunk tests).
Android build/lint/release and the expanded API 29/37 suites are being verified;
the device counts below refer to the previous merged alarm milestone until the
new run is recorded. New instrumentation covers journal revisions/races/rollback,
clean-store restore/deletion/schema isolation, actual Android filesystem sync and
manual-journal UI/recreation. No new device results are claimed yet.

This is a development build. M0, M1 and M2 are **partial**. The full feature scope
and production release gates remain in PLAN.md. Main now contains the app.

## Working workflows

- Native Kotlin / Compose Material 3 Expressive, API 37 target and API 29 minimum.
- Original editable sleeping Android SVG, Android vector and monochrome icon;
  no image-generated assets. Reproducible procedural local chime.
- Dated, weekday, anchored-interval and alternating ISO-week alarms; fixed or
  following time zones; labels, enable/disable, edit/delete and skip/undo.
- Configurable sound ramp, vibration and snooze duration/count; local playback,
  notification/full-screen controls, snooze/dismiss and bounded ringing.
- Device-protected alarm-only SQLite state with unique occurrence tokens,
  atomic claims, stale-action rejection and registration/reboot reconciliation.
- Snooze retains the original ringing row until replacement OS registration and
  durable publication succeed. Failed registration/publication keeps it active.
- Bounded, versioned **plaintext alarm-only** export through a chosen document
  provider; validation/preview before atomic additive restore. Existing identities
  are preserved; imported alarms start disabled without pending deliveries.
- Shared logical codec, schema history fixture and independent archive fixture.
- Debug-only motion foreground-service probe, limited to five minutes. It stores
  callback/timestamp statistics, not sensor vectors or sleep records. Probe code,
  subtype and permission are excluded from release builds.

## Verified evidence

Tested source: `d8d4484c75873382dd93e2ba24a8b4ef172c07b2`.
[Passing CI run and downloadable artifacts](https://github.com/BananaJeanss/solsleep/actions/runs/38991928418).
Documentation/screenshots added afterward do not change the tested app source.

| Check | Actual result |
| --- | --- |
| JVM recurrence | 19 tests passed |
| JVM alarm engine | 27 tests passed |
| JVM archive/codec | 15 tests passed |
| API 29 Google APIs x86_64 emulator | All 17 instrumentation tests passed; zero failures/errors/skips |
| API 37 Google APIs x86_64 emulator | All 17 instrumentation tests passed; zero failures/errors/skips |
| Android lint | No issues found locally; CI passes |
| Debug and instrumentation APKs | Compile/package successfully |
| Shrunk unsigned release APK | Build and release checks pass |
| Generated artwork/chime | Reproducibility check passes |
| Release merged manifest | Diagnostic service/activity/permission absent |
| Captured UI | Real screens reviewed in API 29 light fallback and API 37 dark/dynamic theme, including the 200% text workflow |

That is **61 JVM tests plus 34 device test executions**. The 17-test device suite
contains ten SQLite tests, two alarm delivery/component tests, four UI tests and
one motion-probe test. Evidence includes actual AlarmManager → private receiver →
foreground service → durable snooze, SQLite reopen/concurrent claim/rollback,
clean-store restore, schema compatibility, create/edit/skip/toggle/delete,
rotation/draft/identity preservation, advanced settings, and 200% system text.
The motion probe verifies advancing sensor counters during confirmed screen-off,
explicit stop, and an unchanged independent hard-alarm token/OS deadline.

These results establish the tested software paths. The emulator uses virtual
sensors and disabled host audio; it establishes neither audible output nor sleep
measurement accuracy. Screenshots are in docs/screenshots; see docs/TESTING.md.

## Concrete roadblock and release gates

No physical Android phone or Wear device is attached to this workspace. Closing
the M0/M1 hardware gates requires measurements that software emulators cannot
supply: speaker onset/volume and routes, actual locked reboot and update recovery,
process loss/Doze/OEM behavior, and physical energy/haptic observations. A real
reference phone and a recorded device-lab run are the next dependency. The app
must not be advertised as a dependable production alarm until those gates pass.

The short debug probe is a foreground-execution feasibility result, not an
implemented night recorder. Before collecting irreplaceable nights, M2 still
requires the health/session schema, revision/chunk publication/recovery, retention,
full archive/encryption/key-recovery design and fault injection. M3 then requires
real overnight capture and validation. Store acceptance of a future tracking
service subtype needs its own review; runtime success is not distribution approval.

Further physical work includes TalkBack/RTL/wide-screen coverage, denied access,
full disk/corruption, headphones/Bluetooth/calls, unattended runs, and Wear data
acquisition. Existing records are never destructively reset to recover a failure.

## Parity and next work

A01–A04/A09/A11/A12/A18 and I01/I03/I14/I16 have partial implementations.
[Traceability](docs/TRACEABILITY.md) records exact subsets and missing variants.
Tracking, sleep statistics/sensor history, sound analysis, full health/audio backup,
reference CSV migration, watch support, escalation/wake checks, challenges,
smart wake-up, integrations and AI features remain unfinished or unimplemented.
No screenshot or test double is counted as reference-feature parity.

Next: validate and merge the journal/chunk slice; complete remaining M2 safety work
and physical alarm/probe gates; then build a real capture workflow with source
quality and gaps.
Production signing, exhaustive dependency notices/SBOM and release qualification
also remain open. No production signing key is stored in the repository.
