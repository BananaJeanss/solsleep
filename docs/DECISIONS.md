# Initial engineering decisions

17 October 2026. First part of M0/M1; see PLAN.md for the broader requirements.

## Identity and assets

Application ID `dev.bananajeans.solsleep`, with `.debug` for development. Code is GPL-3.0-or-later; all currently linked libraries are open source. Future proprietary SDKs need a separate compatibility decision.

The sleeping Android robot is path artwork, with credit in NOTICE under CC BY 3.0. SVG is the source of truth. The user forbids image-generated assets. No private production signing material is present.

## Toolchain

| Component | Pin |
| --- | --- |
| Java | 17 |
| Gradle | 9.8.1, distribution SHA-256 pinned |
| Android Gradle plugin | 9.4.1 |
| Kotlin / Compose compiler | 2.4.21 |
| Compose BOM | 2026.09.00 |
| Material 3 Expressive | 1.5.0-beta01 |
| Activity / Lifecycle | 1.13.0 / 2.11.0 |
| AndroidX Core / Coroutines | 1.19.1 / 1.11.0 |
| compile / target / minimum API | 37 / 37 / 29 |

AGP 9.4 supports API 37, Gradle 9.6+, and JDK 17. The BOM follows the official Compose setup guide; Expressive is an explicit beta override isolated from alarm policy. AGP built-in Kotlin compiles the app; the pure core uses Kotlin JVM.

References:
- https://developer.android.com/build/releases/agp-9-4-0-release-notes
- https://developer.android.com/build/migrate-to-built-in-kotlin
- https://kotlinlang.org/docs/releases.html
- https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler
- https://developer.android.com/jetpack/androidx/releases/compose-material3

## Authoritative alarm-only store

Use a small device-protected AlarmStore instead of duplicating definitions in the future health database. This removes the two-store projection transaction from this first slice. Labels and schedules are available before unlock; notifications use private visibility. Future health data must use separate credential-protected storage.

SQLite commits definitions and occurrence tokens atomically with FULL synchronization and WAL. The corruption handler retains original files. No destructive migration/downgrade fallback exists. Only schema 1 exists so far; future migrations need fixtures.

## Time and execution policy

One-shots have fixed civil dates. Reconciliation never rolls a missed one-shot into tomorrow. Weekly alarms follow the phone zone unless explicitly pinned in the domain model. Gaps shift by their duration; overlaps choose the earlier offset once. Intervals use an anchor, alternating weeks use ISO week numbers.

Occurrences have unique tokens, original deadlines, states, snooze counts, and persistent ringing starts. Editing invalidates old callbacks. A serialized engine owns actions and side effects, while SQLite claims are atomic across connections.

OS registration is outside the DB transaction. Reconciliation retries the durable token; acknowledgements are stored separately. Use `SCHEDULE_EXACT_ALARM` / `setAlarmClock`, with visible setup/access checks. No inexact replacement is advertised as armed.

Foreground playback uses alarm audio attributes and a local chime. On boot, schedule through AlarmManager; never start media playback directly from the boot receiver. Recovery retains the original deadline/start. Grace: 10 minutes. Ring limit: 30 minutes. Default snooze: five minutes, three attempts; configurable duration 1–60 minutes and limit 0–10. Failed snooze registration keeps the alarm ringing. Simultaneous alarms remain separate occurrences, presented sequentially by one output owner.

Permissions: exact alarms, alarm UI/notifications, foreground playback, wake lock, vibration, boot recovery. No microphone, health, location, Bluetooth, network, overlay, accessibility, or device-admin access.

References:
- https://developer.android.com/develop/background-work/services/alarms
- https://developer.android.com/about/versions/17/changes/bg-audio
- https://developer.android.com/privacy-and-security/direct-boot

## Initial limits

No tracking, watches, health imports, full health/audio backups, tasks, streaming, smart wake-up, or vendor service is simulated. Alarm-only archives are described separately below. M0/M1 are not fully complete: physical acoustic timing, locked boot, OEM survival, and overnight/energy validation remain open.

## ADR-006/007 — Initial alarm portability

Use a bounded versioned logical JSON document and shared codec, not a raw SQLite
file copy. The initial format is explicitly plaintext and alarm-only. Full
archive encryption, session/audio inclusion and reference import remain separate
M2 gates. Validate before preview; adding missing identities is one transaction;
existing definitions and deliveries are unchanged; restored alarms are disabled.
See ARCHIVE_FORMAT.md for the reader/writer and threat model contract. Device
permissions and pending tokens never come from an archive. Schema 1 is tracked
under schemas/alarm and opened independently by an instrumentation fixture test.

## Test runtime compatibility

Pin Espresso core 3.7.0 explicitly in androidTest dependencies. The stable Compose
BOM's UI-test artifact brings an older transitive Espresso; on API 37 it failed
before UI actions with `InputManager.getInstance` reflection. AndroidX Test's
3.7.0 release notes document the replacement with `getSystemService`. Upgrade the
test dependency rather than disabling API 37 UI checks or patching Android hidden
APIs. [Release notes](https://developer.android.com/jetpack/androidx/releases/test).

## Snooze publication ordering

A snooze keeps the original RINGING row while registering a fresh future token.
Only after registration succeeds does one SQLite transaction replace it with the
armed snooze. Only then can output stop. Failed registration cancels any partial
registration and leaves the old row; failed publication cancels the unpublished
new token and keeps the old row. A process loss before publication therefore
retains the original ringing state for sticky-service/boot recovery. Orphan
callbacks cannot claim an unpublished token. This differs deliberately from an
initial save: a new/edit alarm is persisted unarmed first so normal reconciliation
can repair registration. The engine lock serializes callbacks with publication.
Tests inspect durable state during registration and inject publication failure;
physical process-kill/overnight gates still apply.

## M2 — Manual journal and raw authority

Use a separate lazy credential-protected SQLite helper for manual rest reports.
Append full immutable revisions with expected-revision checks, and keep hard
alarms independent of journal errors. A plaintext logical journal archive precedes
live night recording. Deletion removes revisions and leaves a local identity
marker so additive imports cannot resurrect a deleted night on this installation.
See [JOURNAL_STORAGE.md](JOURNAL_STORAGE.md) for format, limits and remaining work.

Raw motion uses bounded, checksummed, self-describing files as authority. Publish
with file flush, atomic same-directory rename and directory flush; serialize
writers/recovery with a process lock and file lock. A future SQL index must be
rebuildable, so raw publication never relies on an imaginary cross-file/database
transaction. Recovery retains invalid bytes and reports gaps. This is a persistence
foundation, not a connected overnight recorder or a sleep-stage classifier.

## Toolchain patch qualification

Pin Gradle 9.8.1 and its official distribution SHA-256; regenerate both wrapper
launchers/JAR through the wrapper task. Its release notes describe build-channel
deserialization and TLS failure-handling security fixes. Pin AndroidX Core/KTX
1.19.1, the current stable patch; its lifecycle-runtime lint dependency update is
already compatible with this app's explicit Lifecycle 2.11.0. Keep JDK 17, AGP
9.4.1 and API 37. Qualify with the complete domain, API 29/37, lint and shrunk
release checks; do not suppress dependency advisories.

Primary references:
- https://docs.gradle.org/9.8.1/release-notes.html
- https://developer.android.com/jetpack/androidx/releases/core
- https://developer.android.com/build/releases/agp-9-4-0-release-notes
