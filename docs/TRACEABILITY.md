# Implementation traceability

PLAN.md §4 is the complete 102-family scope ledger. This document records the
implemented subset; **every family not listed below remains unimplemented**.
Partial means real code exists for the stated subset, not that the full reference
family or its physical-device gates pass. See STATUS.md for actual run results.

| Family | Implemented subset | Automated evidence | Remaining acceptance gaps |
| --- | --- | --- | --- |
| A01 | Dated and weekday schedules, edit/enable/delete, exact occurrence tokens | RecurrenceTest, AlarmEngineTest, AlarmDeliveryTest, AlarmUiTest | Physical firing, process/reboot/OEM lab, all negative permission paths |
| A02 | Anchored day intervals and alternating ISO week numbers, editor and archive preservation | RecurrenceTest year/anchor fixtures; advanced AlarmUiTest | Calendar/travel/device workflow and reference variants |
| A03 | Skip/undo the next repeating date | Engine and UI workflow tests | Arbitrary next-date overrides and temporary multi-field changes |
| A04 | Durable per-alarm settings | SQLite/codec round-trip and advanced editor tests | Global editable defaults and inheritance behavior |
| A09 | Configurable snooze minutes/count, stale-action rejection, durable registration/publication | Engine boundary/race/fault tests; actual AlarmManager snooze test | Diminishing/total-deadline policies, physical interactions and process-kill matrix |
| A11 | Local ramp and vibration during ringing | Core setting preservation; actual service lifecycle | Measured acoustic/haptic onset, delayed stages and screen sunrise |
| A12 | Bundled procedural local tone with system/tone-generator fallback | APK/resource builds and service delivery | Custom URI loss, decode/runtime failures, silent mode, playlists/radio and routes |
| A18 | Alarm notification, full-screen PendingIntent, private lock-screen activity and readiness | Private component test, actual service start, UI workflows | Locked physical device, denied access, OEM and full-screen behavior |
| T01 | Explicit phone recording start, durable single session, pause/resume/finish and consent | CaptureStoreTest lifecycle/idempotency/races; native MotionCaptureTest | Start delay, broader source modes and complete recorded-night UX |
| T02 | Actual accelerometer vectors, source/boot/clock metadata, finite monotonic bounded batches and file checks | MotionCaptureTest core/policy/fault cases, native screen-off/readback and MotionFilesTest | Physical mattress/placement/rate/12-hour battery and signal usefulness; physiological validation |
| T14 | Debug five-minute counters; actual recording source, sample/gap/rejection and saved-file diagnostics | Native diagnostic and capture UI/file checks | Live graph, calibration/clipping, physical quality and complete placement guidance |
| T15 | Retained committed files, explicit dead-owner/service interruption and user-only resume with new anchor | File publication boundaries, orphan SQLite cases and native service-stop/recreation | Actual process kill/reboot/OEM/power-loss qualification; complete raw backup/recovery |
| H12 | Manual additions/corrections, notes, optional 1–5 rating, complete immutable revision log and user-facing restore of an earlier version, local and portable deletion markers | JournalTest and JournalStoreTest revision/race/rollback/clean-store restore; JournalUiTest | Tracked interval/awake edits and source observations |
| H11 | Manual Unicode tags, preserved in revisions and archives | Codec/SQLite round trips | Quantities, rename/merge/undo, search and correlations |
| I01 | Separate alarm and manual-journal plaintext export, validated preview and atomic additive restore; original journal revisions and deletion markers retained; imported markers preserve existing destination entries | Archive malformed/bounds/independent fixtures; SQLite clean-store restore/rollback tests | Complete combined sensor/audio archive, automatic cross-device deletion, encryption/recovery and actual reinstall/provider tests |
| I03 | User-selected document provider for manual alarm/journal files; journal output-close and exact bounded destination readback verification; durable local attempt/verified receipts with nonblocking recovery | Real Android document contracts; core I/O/close failures; separate instrumentation-provider denial/truncation/replacement tests | System picker, actual commercial provider quota/revocation/offline tests, destination identity/availability and cloud-specific adapters |
| I14 | Tonight, Alarms and manual Journal screens using actual saved records | AlarmUiTest and JournalUiTest real workflows and recreation | Raw motion is accessible from Tonight; full sensor history/analytics, dashboard configuration and complete recorded-night workflow remain |
| I16 | Expressive theme, edge-to-edge layouts, vector/monochrome artwork, localized resources | Lint/build; actual UI tests/screenshots | TalkBack, RTL, wide screens, broader font coverage, physical motion/contrast audit |

All interchange fixtures are synthetic SolSleep data; recording tests also acquire actual framework callbacks from virtual emulator sensors. The checked-in native archive
fixture is independently written JSON with a SHA-256 digest; it is not a Sleep as
Android compatibility fixture. No reference CSV import or sensor measurement is
implied by a native-format round trip.

A10 escalation/wake-check, all task families C01–C11, tracking except the partial T01/T02/T14/T15 subsets above,
measurements/history apart from the H11/H12 subsets above, sound analysis and dream/lullaby families, watch/health
integration and remaining integration families have no implemented workflow yet.
The source contains no dormant controls pretending these features are available.
