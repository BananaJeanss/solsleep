# Implementation status

17 October 2026 — initial alarm foundation in progress.

This is a development build. M0, M1 and M2 are partially implemented; the full feature
roadmap and release gates remain in PLAN.md.

## Implemented first slice

- Native Kotlin / Compose Material 3 Expressive app; API 37 target, API 29 minimum.
- Editable SVG sleeping Android mascot, vector launcher/monochrome icons; no
  image-generated assets. Procedural local chime.
- One-time, weekday, interval and alternating-week alarm UI; date/zone settings, labels, on/off, edit/delete, skip/undo.
- Independently testable time policy: fixed dates, weekly, anchored intervals,
  alternating ISO weeks, fixed/following zones, DST gaps/overlaps.
- Durable device-protected alarm-only SQLite state and unique occurrence tokens.
- Exact registration, readiness/permission flow, reboot/time-change reconciliation.
- Private receiver/actions and foreground local playback with alarm attributes.
- Configurable ramp, vibration and snooze duration/limit; dismiss, failed-snooze protection, bounded ringing.
- Real empty/denied/error UI; editor state and identity survive recreation.

- Bounded versioned plaintext alarm archives through a chosen document provider;
  validation/preview before atomic additive restore with imported alarms disabled.
- Shared logical codec and checked-in schema fixture; no raw live DB file copy.

## Evidence

- 55 JVM tests passed locally (19 recurrence, 22 engine, 14 archive); an additional future-date enabling regression is under validation.
- Debug application and instrumentation APK compilation have completed.
- Alarm baseline: debug APK, instrumentation APK, Android lint and shrunk release
  build pass locally; hosted API 37 build/lint/core/release job also passes.
- API 37 emulator baseline: 9 storage/delivery tests pass; 2 UI tests exposed an
  old transitive Espresso reflection failure before any UI actions. Stable
  Espresso 3.7.0 is now explicit; the expanded suite is being rerun.
- New portability/advanced-editor checks are in progress; do not treat baseline
  results as evidence for changes added afterward.
- Android tests cover SQLite persistence/concurrency/rollback, receiver/service
  delivery and snooze, privacy of components, and the real UI workflow.
- No physical-device/OEM/acoustic/overnight claim is made.

See docs/TESTING.md for commands and the required device lab work. Final results
must replace the in-progress entries before a production claim.

## Parity status

A01–A04/A09/A11/A12/A18 and I01/I03/I14/I16 have partial initial implementations.
See docs/TRACEABILITY.md for exact subsets, tests and missing variants. T01–T16,
health/statistics, sound analysis, watch support, full health/audio backup, reference import, advanced tasks,
smart wake-up, smart-home integrations, and AI features remain unimplemented.
A screenshot, test double, or attractive control is not treated as implementation.

## Next

1. Finish validation and fix first-slice defects.
2. Prove API 37 audio and locked reboot on physical hardware.
3. Finish the M2 health schema/chunk recovery/encrypted-archive gates before collecting sleep records.
4. Begin real motion tracking only after the foreground-service feasibility gate.

The canonical current source is the implementation branch/PR; main retains the
planning baseline until the change is merged.
