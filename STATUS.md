# Implementation status

17 October 2026 — initial alarm foundation in progress.

This is a development build. M0 and M1 are partially implemented; the full feature
roadmap and release gates remain in PLAN.md.

## Implemented first slice

- Native Kotlin / Compose Material 3 Expressive app; API 37 target, API 29 minimum.
- Editable SVG sleeping Android mascot, vector launcher/monochrome icons; no
  image-generated assets. Procedural local chime.
- One-time and selected-weekday alarm UI, labels, on/off, edit/delete, skip/undo.
- Independently testable time policy: fixed dates, weekly, anchored intervals,
  alternating ISO weeks, fixed/following zones, DST gaps/overlaps.
- Durable device-protected alarm-only SQLite state and unique occurrence tokens.
- Exact registration, readiness/permission flow, reboot/time-change reconciliation.
- Private receiver/actions and foreground local playback with alarm attributes.
- Gentle ramp, vibration, snooze/dismiss, failed-snooze protection, bounded ringing.
- Real empty/denied/error UI; editor state and identity survive recreation.

## Evidence

- 40 JVM tests passed locally (19 recurrence, 21 engine).
- Debug application and instrumentation APK compilation have completed.
- Final lint/release checks and actual device tests are being completed.
- Android tests cover SQLite persistence/concurrency/rollback, receiver/service
  delivery and snooze, privacy of components, and the real UI workflow.
- No physical-device/OEM/acoustic/overnight claim is made.

See docs/TESTING.md for commands and the required device lab work. Final results
must replace the in-progress entries before a production claim.

## Parity status

A01/A03/A09/A11/A18 and I14/I16 have partial initial implementations. T01–T16,
health/statistics, sound analysis, watch support, import/backup, advanced tasks,
smart wake-up, smart-home integrations, and AI features remain unimplemented.
A screenshot, test double, or attractive control is not treated as implementation.

## Next

1. Finish validation and fix first-slice defects.
2. Prove API 37 audio and locked reboot on physical hardware.
3. Add M2 archive/restore and migration safeguards before collecting sleep records.
4. Begin real motion tracking only after the foreground-service feasibility gate.

The canonical current source is the implementation branch/PR; main retains the
planning baseline until the change is merged.
