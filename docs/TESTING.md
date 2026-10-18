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

## Durable export history

JournalExportAuditTest adds nine transfer/audit ordering and failure tests,
including cancellation and preserving the original transfer error when audit
recording also fails. JournalExportHistoryTest adds eight native SQLite cases:
full values/reopen, unverified pending attempts, backward clocks, two cold helpers,
verified-publication rollback, competing terminal writes, independent schema
fixture/newer-schema retention, and byte-for-byte retained corruption. A real
provider test verifies journal recovery despite an unopenable history schema.
The UI tests check last-attempt/verified history and unavailable versus empty
history with actual successful recovery. They scroll the real lazy list before
querying offscreen history. Disposable test cleanup also clears export metadata.

[Physical lab protocol and read-only collector](device-lab/README.md) provide the
next hardware qualification workflow. The helper passes Bash syntax/help/invalid
argument checks; without a connected device it exits 3 before creating a report.
No acoustic/overnight/energy/power-loss pass is inferred from these helper checks.


## Real phone motion and foreground lifetime

PR #7 qualifies 120 core tests and all 79 tests per API in CI36 at the source in
STATUS.md. MotionCaptureTest (core) covers exact finite batching, source anchors,
monotonic rejection/gaps, bounded checkpoints, identical publication retries,
conservative owned-file deletion, time/space policies, constructor bounds,
cross-chunk overlap/anchor inconsistencies and control identity across first
checkpoint versus a later resume. It adds no clinical accuracy assertion.

CaptureStoreTest has 12 actual SQLite cases: complete source/state/event reopen,
start idempotency/terminal replay, simultaneous cold starts, stale/wrong-owner
writes, orphan interruption, trigger rollback, non-regressing progress and durable
deletion intent, independent schema/future version retention, unchanged corrupt
bytes, pagination, file-leading-index versus missing acknowledged files, and
mismatched source clock anchors. Original observation files remain on damage.

The five native recording cases use a visible Activity, real SensorManager and
actual chunk/filesystem/SQLite adapters. They cover screen-off samples,
pause/resume into distinct sources, Finish/readback, unchanged hard alarm,
stale actions, service-stop retention, explicit old-owner interruption, local
deletion, 200% consent/control/completed-state rendering, and a preparation Finish
remaining valid across the first checkpoint. Rendered controls are awaited after
the durable commit; Compose lifecycle/frame scheduling is not replaced with a
fixed sleep. Disposable cleanup waits for service closure and quiesces the screen.

AlarmDeliveryTest adds actual repeated starts racing foreground shutdown, with a
visible Activity and a controlled queue gate on the real alarm executor. The
original API 29 crash is not waived as infrastructure flakiness. The service must
acknowledge foreground execution before its ready marker and guard shutdown by
start ID; no test assertion is disabled. Repeated starts cannot mutate saved
alarm state. Physical acoustic onset and audio routes are still unqualified.

Saved-data and 200% captures use PlatformTestStorage and the final source. The
fixture's virtual sensor readings are not human sleep/physiological data. Confirm
12-hour battery/temperature, real placement, sensor rate/continuity, notification
revocation, process/task-manager stop/reboot and hardware flush behavior in the
physical lab. Raw capture is not in Journal archives; never run these destructive
instrumentation fixtures on an install holding personal raw observations.

## Recording archive and restore regression contract

MotionArchiveTest has 12 cases: independent Python fixture read/write byte parity,
all metadata/original float bytes, strict corruption/truncation/bounds/EOF,
malformed UTF-8/flags/enum with recomputed hash, source/anchor/index/sequence/
timestamp validity, thousands of bounded chunks, exact destination comparison,
cancellation and source retention on sink failure. The standalone Python inspector
has four tests including every fixture truncation and single-bit mutation.

MotionArchiveStorageTest adds 13 native cases: complete provider→clean-store
restore/reopen; interruption after synced file; SQL rollback after raw publication;
local/deleted/request/source conflicts; conflicting raw retention; active capture
rejection; corrupt/truncated staging/discard; complete preparing-copy recovery;
provider denied-write/read, truncation and replacement; additive v1 migration and
independent v2 fixture; low-space before provider/destination and retained retry.
The low-space counter and post-publication exception are explicit fault injections;
they do not claim real ENOSPC or hardware power-loss qualification.

A sixth MotionCaptureTest exercises actual ViewModel/provider staging, visible
preview/explicit Restore at 200% text, complete SQL/raw readback, no active service,
removed staging and enabled export controls. Its synthetic fixture is labeled as
test data; motion-archive-large-text.png is captured through PlatformTestStorage.
The real framework-sensor cases remain unchanged and pass in the same suite.

PR #8 final source and exact CI39 results are in STATUS.md. Tests use disposable
installs and named private databases/slots, never personal data. No test/assertion
is disabled. The original 79 native cases plus these 14 give 93 per API.


## Wake-up challenge regression contract

`WakeChallengeTest` exercises each ordinary puzzle at every level over hundreds of
seeds, mixed ordering, exact progress/replay, numeric locale input, one-minute gain
and failure escalation, connected acyclic mazes and solved paths, accessible
fallback work, NFC domain hashes, invalid voice evidence, strict custom packs,
Unicode/control/duplicate/depth/stream attacks and exact frozen rule/archive data.
The independently authored `challenge-pack-v1.json` has different tasks per level.
The JSON Schema is editor assistance; Kotlin validation is authoritative.

`ChallengeEngineTest` covers both action gates, stale identities/revisions,
process/ringing recovery, completed proof retention on scheduler/publication
failure, alternate intended action, failed task CAS, original ringing expiry,
edit protection and a fresh task after snooze. Existing plain-alarm cases remain.
`VoiceLevelTest` verifies the final complete 100 ms window, exact two-second speech, separated bursts, silence/steady room noise, excess clipping and bounded PCM inputs. The same core producer is used by the microphone adapter; evidence is not just tested with hand-constructed metrics.

`VoiceMatchTest` covers exact reference PCM, amplitude invariance, bounded tempo
variation, silence/noise/clipping, quiet baseline suppression, strict WAV lengths/
formats/metadata/downsampling, host scoring, portable references and rejection of
oversized output before destination writes. Synthetic tones test DSP properties;
they are not human recognition or microphone qualification.

`ChallengeStoreTest` uses actual Android SQLite for schema 1→2 with ringing data,
independent schema 2, reopen, cross-connection CAS, direct finish/snooze guards,
exact frozen packs/voice and the actual bundled PCM references. Permission checks
assert optional NFC/microphone and absence of network/camera/call/overlay access.
`ChallengeUiTest` delivers through actual AlarmManager/receiver/foreground playback (never an unowned fabricated ringing row), then saves per-alarm settings across recreation, solves the real
ringing task after a retained miss/recreation, checks the fixed phone control and
accessible fallback with real 200% system text, refuses stale activity tokens and
proves old broadcasts cannot bypass configured challenges. Screenshots use
PlatformTestStorage, not a web mockup or synthetic image.

Run source qualification on both API 29 and 37 and inspect actual test XML and
large-text capture before merge. Exact runs/counts live in STATUS.md. These use
disposable installs/databases. No assertion/test is disabled. Physical NFC/mic,
private-input contention, phone call audio, lock-screen emergency navigation,
TalkBack, actual acoustic level and OEM recovery remain separate lab gates. Do
not place test calls to emergency services or use test installs for personal data.
