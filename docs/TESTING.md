# Verification

```sh
./gradlew :core:test
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:lintDebug :app:assembleDebug :app:assembleRelease
```

The core tests use independent time fixtures and cover stale/concurrent callbacks, restart repair, late delivery, failed snooze, limits, disabled alarms, and travel. No framework mock establishes that an audible alarm played.

Android tests run on a fresh debug installation:
- AlarmStoreTest: actual SQLite, close/reopen, two helpers, foreign-key rollback, disabled records, cascade deletion, and refusal of unknown schemas without deletion.
- AlarmDeliveryTest: actual near-future AlarmManager → receiver → foreground service, snooze, OS deadline, and database reopen; private control components.
- AlarmUiTest: real create/edit/skip/toggle/delete flow, draft/identity preservation through recreation, advanced settings preservation, and creating an alarm at 200% system font size.
- MotionProbeTest: debug-only visible foreground start, real accelerometer callback statistics during confirmed screen-off, explicit stop, and an unchanged prearmed hard deadline. This is a runtime feasibility probe, not a sleep-accuracy test.

The delivery test grants its debug package test-device exact-alarm/notification access through the shell. Production code never bypasses permission checks.

Screenshots use AndroidX PlatformTestStorage, which AGP collects before uninstalling
the test application. Find them under
`app/build/outputs/connected_android_test_additional_output/`; CI publishes that
directory with test reports. App-private files are not available after AGP's test
cleanup. See the [storage API](https://developer.android.com/reference/androidx/test/platform/io/PlatformTestStorageRegistry).

Reproduce source-defined assets:

```sh
python3 tools/generate_brand.py
python3 tools/generate_tone.py
git diff --exit-code -- app/src/main/res/drawable app/src/main/res/raw
```

Still required in the device lab: physical acoustic onset/volume; locked boot and update recovery; actual process loss and force-stop boundaries; Doze/OEM behavior; headphones/Bluetooth/calls and permission denial; full-disk/corruption fault injection; TalkBack, 200% text, landscape/long translations; 12-hour runs and the release-candidate period.

Read STATUS.md for actual completed evidence. Test code existing is not a claim its device gates passed.

AlarmArchiveTest adds complete-definition round trips (all recurrences, Unicode,
null fields and limits), repeatable output, historical codec compatibility,
checksum/version/type/count/identity/time-zone rejection, every byte truncation,
UTF-8/depth/size limits, and I/O failure propagation. Engine restore tests prove
additive, disabled, idempotent behavior without touching a ringing occurrence.
SQLite tests additionally restore into a clean database, roll back an entire
conflicting batch, and open the checked-in v1 schema fixture with original data.

Hosted CI runs the device suite independently on API 29 and API 37. The cleanup
trap collects screenshots and logcat even when a test fails. Documentation-only
updates skip these expensive builds; source, build scripts and test changes do
not. Latest test results always identify the tested source commit in STATUS.md.

## Journal and raw-file foundation

The expanded suite adds manual-revision and archive tests, byte truncation and
corruption, and raw publication fault injection. Android tests use the actual
credential-protected SQLite/filesystem APIs, two helpers racing a correction,
transaction-abort triggers, a damaged database, and all publication boundaries.
They exercise manual creation/correction/history restore/deletion, preserved
identity/drafts, stale-writer rejection and 200% system text. These are distinct
from a real overnight recorder, actual power loss and physical sleep accuracy.
See JOURNAL_STORAGE.md and STATUS.md for precise claims and actual run results.

An incremental generated-R dex collision was observed with AGP 9.4.1 and
Gradle 9.7.1 after some resource edits. It reproduces locally as duplicate
`androidx.activity.compose.R` archives; a fresh app build passes. For a reproducible
local verification if this failure recurs, run `./gradlew :app:clean --no-daemon`
first, then the documented verification commands in a separate invocation. CI
uses fresh workspaces. Do not remove dependencies or suppress duplicate classes
to hide this failure. The Gradle 9.8.1 security update is not evidence that this separate incremental
issue is fixed. Re-qualify the reproducer before removing this note.

## Diagnostic concurrency and safe test installs

Diagnostic snapshot reads, writes and deletion share one same-process lock.
Android AtomicFile itself does not serialize access; on legacy platforms a
concurrent read can interfere with backup recovery. The regression test races
actual polling with repeated native durable writes. Alarm UI tests wait for
rendered enabled controls and editor dismissal; database commit alone is not
evidence that a subsequent touch target is interactive.
Reference: https://developer.android.com/reference/android/util/AtomicFile

Instrumentation clears/uninstalls disposable debug state. Use a dedicated emulator
or test install, never an installation containing personal nights/alarms. CI debug
signing keys can change between builds; keep one stable signing identity for
personal development records and verify a portable export before any key/install
transition. Production signing and real reinstall/provider recovery remain gates.

A temporary resource-addition probe passed with Gradle 9.8.1. Original resources
were restored and a clean unmodified debug APK rebuilt. This establishes that
case, not that every previously observed incremental generated-R collision is
fixed. Final independent reports are linked from STATUS.md.

## Portable deletion markers

The v2 reader has an independent Python-serialized fixture (including its digest)
and retains the original v1 fixture. JVM tests cover live/deleted disjointness,
canonical identity and marker type/uniqueness/count limits, checksum coverage of
markers, deletion-only truncation, deterministic output and mutable-input
revalidation. Existing writer sorting remains supported for unordered revisions.

Android SQLite tests export a deleted identity, clear the disposable test store,
restore the newer archive, reopen, and verify an older archive cannot resurrect
it. Imported markers for existing local corrections are skipped with explicit
counts. Trigger failures after earlier markers and after a later night roll back
the entire mixed batch. Two helpers race the same restore without duplicates.
A broken current pointer fails archive export while retaining the original history.

The UI test passes an actual app-private file URI through the ViewModel reader,
checks the preview and confirms the real restore via Compose. It verifies actual
counts and the preserved local entry and captures preview/result screens. This
is not a system picker, external provider, cross-app permission, actual uninstall
or cloud-provider qualification. Those remain separate M2 tests.

## Destination readback verification (results in STATUS.md)

JournalDocumentTest covers exact bytes and output-close-before-read ordering,
partial-write/quota, flush, output close, read, input close, changed valid archive,
truncation, validation-before-destination-open and cancellation propagation.
JournalDocumentProviderTest uses actual Binder/ContentResolver file transfers to
a provider in the instrumentation package. It tests semantic restore from the
verified result and preserves complete source revisions and deletion markers
under denied write/read, truncated read and valid replacement scenarios.
The fixture is test-only; these are controlled faults, not claims that a commercial
cloud provider was tested. JournalUiTest checks the real ViewModel and displayed
unverified/verified states using those provider URIs. It also verifies the saved
source remains equal and the output document includes all revisions/markers.
No external system picker or provider cloud upload is implied by this test.

The provider runs as a component of the separate test package, not as Kotlin test
code loaded into the instrumented target. Its Java-only implementation uses Android
and JDK APIs: the generated test APK does not contain the target's Kotlin runtime
(confirmed by inspecting all test DEX class definitions). This avoids an implicit
runtime dependency on the app being tested. The actual cross-package provider
suite remains the acceptance check; no fake resolver replaces Binder/file I/O.
Reference: https://developer.android.com/studio/test/test-in-android-studio

## Oversized journal export

A valid CJK-heavy database fits the character count while exceeding the UTF-8
byte budget. The test checks the app-created database encoding, rejects the export,
then compares every original record and reopens it. Identity-only preview remains
available. A second case retains all 10,001 deletion identities rather than
silently dropping one to fit the format. The writer test uses a valid oversize
snapshot and verifies zero writes to the destination. Independent v1/v2 fixtures
must retain their exact bytes after bounded construction changes. These guards
are not a claim that large-history streaming or current-list pagination is done.
