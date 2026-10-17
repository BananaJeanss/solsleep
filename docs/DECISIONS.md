# Initial engineering decisions

17 October 2026. First part of M0/M1; see PLAN.md for the broader requirements.

## Identity and assets

Application ID `dev.bananajeans.solsleep`, with `.debug` for development. Code is GPL-3.0-or-later; all currently linked libraries are open source. Future proprietary SDKs need a separate compatibility decision.

The sleeping Android robot is path artwork, with credit in NOTICE under CC BY 3.0. SVG is the source of truth. The user forbids image-generated assets. No private production signing material is present.

## Toolchain

| Component | Pin |
| --- | --- |
| Java | 17 |
| Gradle | 9.7.1, distribution SHA-256 pinned |
| Android Gradle plugin | 9.4.1 |
| Kotlin / Compose compiler | 2.4.21 |
| Compose BOM | 2026.09.00 |
| Material 3 Expressive | 1.5.0-beta01 |
| Activity / Lifecycle | 1.13.0 / 2.11.0 |
| AndroidX Core / Coroutines | 1.19.0 / 1.11.0 |
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

Foreground playback uses alarm audio attributes and a local chime. On boot, schedule through AlarmManager; never start media playback directly from the boot receiver. Recovery retains the original deadline/start. Grace: 10 minutes. Ring limit: 30 minutes. Snooze: five minutes, three attempts. Failed snooze registration keeps the alarm ringing. Simultaneous alarms remain separate occurrences, presented sequentially by one output owner.

Permissions: exact alarms, alarm UI/notifications, foreground playback, wake lock, vibration, boot recovery. No microphone, health, location, Bluetooth, network, overlay, accessibility, or device-admin access.

References:
- https://developer.android.com/develop/background-work/services/alarms
- https://developer.android.com/about/versions/17/changes/bg-audio
- https://developer.android.com/privacy-and-security/direct-boot

## Initial limits

No tracking, watches, health imports, backups, tasks, streaming, smart wake-up, or vendor service is simulated. M0/M1 are not fully complete: physical acoustic timing, locked boot, OEM survival, and overnight/energy validation remain open.
