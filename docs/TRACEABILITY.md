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
| A10 | Local challenge-visible gain, miss escalation and one-minute fixed quiet deadline | WakeChallengeTest gain/reopen/clock cases; native ringing flow | Physical audio/focus measurement and post-dismiss wake-up check |
| C01 | Typed generated math at three levels, durable seed/progress | WakeChallengeTest, ChallengeEngineTest, ChallengeStoreTest | Choice math and physical usability |
| C02 | Original code-drawn eyes-closed sheep, accessible labels/fallback | Seeded target tests; ChallengeUiTest actual 200% capture | Physical TalkBack/vision audit |
| C03 | Learned hashed NFC UID, visible reader mode and native fallback | Hash/answer tests, optional feature/permission checks | Physical tag technologies, unavailable/locked operation; barcode/QR unimplemented |
| C05 | Relative microphone-level task and offline voiceover delivery task | Finite/clipping/duration rejection; VoiceMatchTest and actual native reference assets | Real mic/route/room/false acceptance qualification; smile/laughter unimplemented |
| C06 | Deterministic touch/spoken-passage maze | Hundreds of connected/acyclic/solvable generated mazes | Physical usability; jumping/tilt game unimplemented |
| C07 | Digits, number trail, reverse/copy code, memory sequence and native pack phrases | Core solution/replay/Unicode tests; ringing recreation | Flags/distorted text and complete reference variants |
| C09 | Fixed per-occurrence shuffled task mix and chained rounds | Stable seed/order/recovery/CAS tests | Physical/task-chain behavior across OEM interruptions |
| C11 | Custom native JSON TEXT/CHOICE/ORDER authoring/import/export and rehearsal | Strict bounds/duplicate/unknown-field parser tests; frozen rule/backup storage | Android third-party app extension/grants and commercial-provider picker qualification |
| T01 | Explicit phone recording start, durable single session, pause/resume/finish and consent | CaptureStoreTest lifecycle/idempotency/races; native MotionCaptureTest | Start delay, broader source modes and complete recorded-night UX |
| T02 | Actual accelerometer vectors, source/boot/clock metadata, finite monotonic bounded batches and file checks | MotionCaptureTest core/policy/fault cases, native screen-off/readback and MotionFilesTest | Physical mattress/placement/rate/12-hour battery and signal usefulness; physiological validation |
| T14 | Debug five-minute counters; actual source/file diagnostics; versioned acquisition provenance and per-segment observed coverage with unknowns/deficits/overlaps | Native diagnostic/capture checks; provenance/migration/archive and coverage regression contract in TESTING.md | Current coverage qualification in STATUS.md; live graph, calibration/clipping, physical quality and complete placement guidance |
| T15 | Retained committed files, explicit dead-owner/service interruption and user-only resume with new anchor | File publication boundaries, orphan SQLite cases and native service-stop/recreation | Actual process kill/reboot/OEM/power-loss qualification; combined/encrypted raw backup and physical recovery |
| H12 | Manual additions/corrections, notes, optional 1–5 rating, complete immutable revision log and user-facing restore of an earlier version, local and portable deletion markers | JournalTest and JournalStoreTest revision/race/rollback/clean-store restore; JournalUiTest | Tracked interval/awake edits and source observations |
| H11 | Manual Unicode tags, preserved in revisions and archives | Codec/SQLite round trips | Quantities, rename/merge/undo, search and correlations |
| I01 | Separate alarm/manual-journal plaintext exports and single finished-recording SOLR export/verified readback/staged recoverable restore; original journal revisions/deletion markers and exact recording chunks/sources/events retained; imported markers preserve existing destination entries | Archive malformed/bounds/independent fixtures; SQLite clean-store restore/rollback; recording migration/fault/provider/200% tests | Complete combined sensor/audio archive, automatic cross-device deletion, encryption/recovery and physical reinstall/commercial-provider qualification |
| I03 | User-selected documents for alarm/journal and separate finished-recording files; journal/recording output-close and exact bounded readback; persistent receipts currently journal-only | Real Android document contracts; core I/O/close failures; separate instrumentation-provider denial/truncation/replacement tests | System picker, actual commercial provider quota/revocation/offline tests, destination identity/availability and cloud-specific adapters |
| I14 | Tonight, Alarms and manual Journal screens using actual saved records | AlarmUiTest and JournalUiTest real workflows and recreation | Raw motion is accessible from Tonight; full sensor history/analytics, dashboard configuration and complete recorded-night workflow remain |
| I16 | Expressive theme, edge-to-edge layouts, vector/monochrome artwork, localized resources | Lint/build; actual UI tests/screenshots | TalkBack, RTL, wide screens, broader font coverage, physical motion/contrast audit |

All interchange fixtures are synthetic SolSleep data; recording tests also acquire actual framework callbacks from virtual emulator sensors. The journal JSON and Python-authored SOLR binary interchange fixtures have independent producers and SHA-256 digests; they are not Sleep as
Android compatibility fixtures. No reference CSV import or sensor measurement is
implied by a native-format round trip.

A10 post-dismiss wake-check, task families C04/C08/C10 and the remaining variants of partial C01/C02/C03/C05/C06/C07/C09/C11, tracking except the partial T01/T02/T14/T15 subsets above,
measurements/history apart from the H11/H12 subsets above, sound analysis and dream/lullaby families, watch/health
integration and remaining integration families have no implemented workflow yet.
The source contains no dormant controls pretending these features are available.
