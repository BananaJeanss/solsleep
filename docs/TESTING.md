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

The alarm UI harness follows the notification's canonical content-intent filter
and presses real Snooze/Dismiss buttons. Android 29 can redeliver another intent
to a singleTask Activity; a Scenario tied to a different action incorrectly sees
it as permanently paused. The stale-action case observes actual lifecycle
callbacks, waits for rejection or a later legitimate notification handoff, and
asserts the entire current pending row/proof is unchanged. It does not require
Android to suppress a fresh valid notification or bypass lifecycle assertions.

### Final PR #9 qualification

Source `3ac50fd63cee4650dcb6a661482c3ee0c2d8ff46`, tree
`0efa194f20c238b8b96ef11cf80d107c66a407bb`,
[CI45](https://github.com/BananaJeanss/solsleep/actions/runs/39098002133).
Merge `421af1706e5e4e1cd721615f1e490f9772a69d91` has that same tree.
All 178 JVM tests, four Python tests and 108 native cases on each API passed;
actual final XML has zero failures/errors/skips and lint XML has zero issues.
Debug/test APKs and the shrunk unsigned release pass. Both original native PNGs
were reviewed and copied exactly; [SCREENSHOTS](SCREENSHOTS.md) records hashes.

| Artifact | ID | Verified ZIP SHA-256 |
| --- | --- | --- |
| Build/core | 12125322106 | `8c29c708e0924219909fa4847226752ed71a3b83c83acf9ea0b507bd46e7fe00` |
| API 29 | 12125790961 | `3598c40718f60d5c0c88fc7f58e9e9796987a59a79946c085ac0919c05874747` |
| API 37 | 12125950575 | `db50b3e715f818cbe6e2617e0199b6c384d40df752e56cd98f59873b40801ba1` |

CI41–44 were unsuccessful qualification attempts, not waived passes. The final
harness delivers a scheduled occurrence through the receiver/service rather than
an unowned RINGING fixture that main-screen recovery correctly reschedules.
Its canonical notification intent also fixes ActivityScenario tracking the wrong
filter after Android 29 singleTask redelivery. The stale-token test observes the
delivered request and asserts the complete pending row/proof remains unchanged.
No test is ignored, no assertion is disabled and both supported API suites run.

Local qualification used JDK 17 and the commands in STATUS.md. Switching debug/
release incremental caches produced duplicate generated/top-level symbols during
development; a separate `:app:clean` followed by the full debug/test/lint check
resolved them. No source workaround or lint suppression was added. Final CI uses
clean checkout builds and independently passed both variants.

## Recording provenance and observed-coverage regression contract

`CaptureProvenanceTest` keeps the independent v1 fixture byte-identical and tests
v2 cross-language rewrite, mixed known/unknown segments, strict future/duplicate/
malformed provenance and checksum-recomputed attacks. The Python producer and
inspector retain v1 and add an independent v2 fixture, exhaustive truncation/bit
faults and schema/field validation. These are synthetic interchange fixtures.

`MotionCoverageTest` tests exact nearby-reading intervals, inclusive cadence/hard
cap boundaries, original unknown cadence, empty/single/sparse input, missing
chunks/indexed samples, across-chunk continuity, per-stream/boot separation,
clock rollback, same-boot overlap, interruption events, malformed source/checksum/
ordering and deterministic metadata/sample-bound fingerprints. It does not test
sleep recognition. Original raw bits remain retained.

`CaptureProvenanceStorageTest` exercises actual SQLite 1/2→3 migration, independent
schema 3, unknown payload retention, failed migration rollback, frozen metadata
through reopen/counter updates, actual provider v2 export/readback/restore,
rebuild identity and corrupt/missing raw handling. `MotionCaptureTest` checks the
actual installed APK hash/cadence/zone on real framework acquisition, plus native
known/legacy/unavailable coverage screens and a retained 200% recreation capture.
Final full-suite results and source/ZIP/capture provenance belong in STATUS.md;
implementation or an initial passing subset is not a complete qualification.

CI47 passed all 203 JVM/nine Python/116-per-API native cases, but capture review
caught an inherited source-description string hardcoding a 100 ms request for
legacy recordings. It was removed; raw-file details now use retained provenance
or an explicit unknown label. The legacy coverage UI regression also refuses any
invented requested-interval value. CI47 is not the final visual qualification.
Corrected source completed all checks again in CI48 below.

### Final PR #10 qualification

Source `5dc67cdb8a04a89e969635310024353e6d0af14a`, tree
`34dac0210693644c8551a16562f84e989f78485b`,
[CI48](https://github.com/BananaJeanss/solsleep/actions/runs/39119133196).
CI's synthetic PR merge `0610654ce77f916abe870d182d1c989d13bcc182` and final
merge `dccb8abb4504067fbb666526048fbb7c76c32d47` independently have that same
tree. Final reports contain all 203 JVM, nine Python and 116 native cases on
each API 29/37, zero failures/errors/skips and zero lint issues. All previous
cases and both APIs remain enabled. Local clean debug/test/lint and shrunk
unsigned release pass; clean CI independently passes both variants and original
vector/chime/voice asset checks.

| Artifact | ID | Verified ZIP SHA-256 |
| --- | --- | --- |
| Build/core | 12133184670 | `ee9e625953ff522b3b2205a785274956e6d88e84723cda43d01dfa5b7a65cfa7` |
| API 29 | 12134205620 | `3fa564b7d5eb9271725f89de8a0133186c21cf20b6e98fbe30f767232336b997` |
| API 37 | 12133364979 | `84fdb331fdc5966c679ae7c4f7843cbf6b15e28e557a2f1bad0ed4ddb4dc29ad` |

Build job `117650942230` and native jobs `117650942019` / `117650942268`
completed successfully. Downloaded ZIP digests match the artifact manifests;
actual nested JUnit testcase results and lint XML were parsed. Both final native
200% captures were visually reviewed and copied byte-for-byte, with hashes in
[SCREENSHOTS](SCREENSHOTS.md). They have readable light/dark text, fixed close
access, localized millisecond timestamps and the corrected source description.
The known/legacy/corrupt UI assertions pass on both APIs. Screenshot labels and
coverage fixtures are explicitly synthetic; actual virtual sensor acquisition
is tested separately. No image generation, editing or UI reconstruction was used.

| Independent identity | SHA-256 |
| --- | --- |
| Unchanged SOLR v1 fixture | `82ade8ef0f4a2ce98a0baa4f0819fe31c47c18f33e673ee15a4901b03db2c46d` |
| Added SOLR v2 fixture | `babdceef51b6609c4d38d0b27c66864e035520ee6da024fbffcb6b428354120f` |
| Policy 1 / v1 fixture input identity | `9304190021d10f69d0b83e1afb20fd5ceb1af4ffb0854f515de134efc102ba6c` |
| Policy 1 / v2 fixture input identity | `96e0297e0fe210486efad09742ac5549ddae89bb9009a178ceffca7fbad4bc24` |

The independent Python digest construction is checked against the Kotlin rebuild.
All identities describe synthetic interchange inputs, not human sleep validation.
Physical power loss/full disk, overnight usefulness/energy, accessibility and
clinical/physiological accuracy remain unqualified. No phone/watch was attached.

## Historical APK delivery attempt — CI50, superseded

`test_debug_delivery.py` exercises source/run/counter and file/schema binding,
checksum/APK/certificate changes, unsafe/extra files, draft upload interruption,
remote readback failure, exact public retry, changed tag/assets, non-404 API
errors, bounded exclusive private signing files and fork/upstream separation.
The combined Python suite has 26 cases; actionlint 1.7.12 passes. Actual CI
packaging invokes Android `aapt` and `apksigner`, so package/API/debug/version/
certificate assertions are checked against a real APK, not only test fixtures.

CI50 source `b248c9e0b6dec99d366880a5ab42285ffe95513d`, tree
`deec5835cdb202428477754faec0f972889714c3`, checked out synthetic merge
`1c1eaf37ec71fe40e0be9b7c60e08ee9b1e7bcd2`. Its build reports show 203 JVM,
24 Python, zero lint and passing debug/shrunk unsigned release. API 37's actual
XML has 116 cases, zero failures/errors/skips. API 29 was cancelled at the 30-minute
job limit, with no final JUnit XML; it is not a waived pass or a zero-case success.
Its retained logcat last enters the 200% recording-coverage/recreation test,
opens its screenshot, restores font scale and recreates the Activity. The following
cleanup could await Compose idle on a stale root indefinitely. The corrective
harness stops lifecycle polling first and destroys the real Activity before font
restoration, keeping every body assertion. At that point, full final reruns were
still required; the completed investigation and qualification are recorded below.

| CI50 artifact | ID | Verified ZIP SHA-256 |
| --- | --- | --- |
| Standalone preview APK | 12152233356 | `fd4e684937221c4cfd8889389295ed6d4277d52364a73115569e0200da4c4f9e` |
| Build/core | 12152343039 | `ed8346cd82db20d5e61faa74d3f86ff27899973eaea38226e3def8d175c1e1ac` |
| API 37 | 12152354315 | `53169a0da84d86875666b51bf081a5c98abce5acb016793ce381b4cb732e21e4` |
| API 29 diagnostics, incomplete | 12153211560 | `cb22a42be449e01b99f4fc0dacfc953e8c2862d219a9fa31050ca1c985c5497f` |

The preview APK SHA-256 is
`ee43fff37efcfdb9be9d349daae46dc1247c324c36470b794999bd9402544e01`;
certificate SHA-256 is
`3aabf00d782c8b8ea727b53f8b8dcc14463906bab12fc6802ca6b3a6258907c6`.
It is `dev.bananajeans.solsleep.debug`, version code 15001, API 29 minimum/37
target, runner-ephemeral signing. Independent local `apksigner` verifies it.
This identifies the preview; it does not close the failed API 29 gate.

Local debug/test/lint and shrunk release pass. One concurrent-worktree release
attempt hit the shared Gradle cache lock; serialized execution passed. No source
workaround or test/lint suppression was used. A separate disposable 3072-bit
private key also signed the real debug APK successfully; its actual certificate
matched SHA-256 of independently exported keytool DER bytes, then both private
keystore files were deleted. That local signing-test APK is not distributed.

## Recording harness investigation — completed 20 October 2026

Read-only CI54 parsed all four retained CI52/53 JUnit reports and matched the
downloaded ZIP digests: CI52 has 116 cases/nine failures per API; CI53 has
126 cases/eleven failures per API. XML retained the later cleanup busy-state
timeout. CI60 logcat also revealed setup failures before the recording bodies.
All eleven receipt-screen setup calls failed, explaining the absent PNGs.

The initial clock-only hypothesis was incomplete. CaptureUiState starts
busy=true before CaptureHome's STARTED repeatOnLifecycle effect launches its
first load. Stopping a fresh Activity before that effect runs prevents
initialization. The final helper allows the real initial load while STARTED,
then stops polling and drains launched work using bounded Compose waitUntil
without requiring a registered/idle stale root. It does not assume that every
ViewModel coroutine uses the composition dispatcher. Cleanup destroys its
Activity before restoring font scale. No test body/assertion was removed.

CI62 then passed all 116 API29 cases and, after a scoped SDK-install retry, all
116 API37 cases. CI61 passed every recording test on both APIs and all 126 API37
cases; its one API29 failure was an earlier ChallengeUiTest control disappearing
between finding Dismiss and scrolling. The corrected helper requires an actual
visible/enabled scroll-and-click within the existing bounded wait. It starts no
task via an engine shortcut; proof/progress/miss/recreation assertions remain.

CI66 completed all 213 core/26 Python and 126 native cases per API29/37, with
zero failures/errors/skips. CI57/58 and temporary targeted startup experiments
are not counted as qualification. No experimental startup assertion was promoted.
Original CI61 receipt PNGs were visually reviewed on both APIs at 200% and retained
byte-for-byte; the only later source difference was the earlier alarm test helper.
Temporary PR #13 performed read-only evidence inspection and closed without merge.

Core/native jobs now parse and print actual reports on success and failure,
rejecting missing/empty reports and any failure/error/skipped cases. Complete
logs/reports remain retained. Local command execution was unavailable during this
follow-up; hosted builds and read-only artifact inspection provided the evidence.

Primary implementation/documentation checked:
- https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui-test/src/androidMain/kotlin/androidx/compose/ui/test/ComposeUiTest.android.kt
- https://developer.android.com/studio/test/command-line
- https://github.com/cli/cli/blob/trunk/pkg/cmd/release/shared/fetch.go

## Qualified public APK delivery and recording receipts — 20 October 2026

| Source / purpose | Executed result |
| --- | --- |
| PR11 source 7f284b48e3e9eddd39620ec979130ed1baaa86e2 / CI62 | 203 core, 26 Python, 116 native per API29/37; all pass |
| PR11 main merge 3be27815bb5cb03ca6ab1c256b6c7415db732059 / CI68 | Full checks repeated; first public prerelease published |
| Anonymous CI70, run 39470990960 / job 118794681228 | All four public assets HTTP200/HTTPS, no authentication; exact bundle/source/hash verification |
| Publisher retry CI68 / job 118796208229 | Workflow attempt 2 reused build attempt 1; tag/release/asset IDs/sizes/hashes unchanged |
| PR12 source 841328e12cd5ff92c5c741ee9e3fbc3d27e347f0 / CI66 | 213 core, 26 Python, 126 native per API29/37; all pass |
| PR12 main merge b7afd0c9db9b6c5150c2e879de0946469c87a8cf / CI71 | Full checks repeated; signed debug release published |
| Final public readback | [CI72 public readback](https://github.com/BananaJeanss/solsleep/actions/runs/39474809678) |

Qualified receipt checkout/tree:
`18a8f0b50d7079f7a97f7e567ab70cdb1d14368e` /
`f7c73e72f98d48a8657b74ac1d321e811abf08f8`.
Final main commit/tree:
`b7afd0c9db9b6c5150c2e879de0946469c87a8cf` / `d3c74e911ca0f05b6b0998ff02764844e34d6eba`.
Direct recursive Git-tree comparison verifies all 159 non-documentation files
match between qualification and merge. Only Markdown and exact PNG copies differ.
A diverged GitHub comparison can include inherited changes from its merge-base;
actual blob identities, rather than that three-dot view, establish this equality.

The receipt additions include ten independent core audit-boundary cases, eight
actual SQLite/provider cases and two native UI cases per API. Coverage includes
phase ordering/output-close/readback, interruption/cancellation/audit failure,
independent schema fixture/reopen, concurrent cold opens and competing terminal
updates, transaction rollback, clock rollback, future/corrupt byte retention,
denied/truncated/replaced provider results, verified recovery despite damaged
receipts, scoped deletion and actual 200% known/unavailable history/recreation.
Raw source bytes and metadata stay unchanged before explicit deletion.

| Final main check | Actual result |
| --- | --- |
| Core XML | 213 cases, zero failure/error/skip |
| Python tools/fixtures | 26 passed |
| API29 XML | 126 cases, zero failure/error/skip |
| API37 XML | 126 cases, zero failure/error/skip |
| Lint XML | zero lint errors; two warnings; SHA-256 `e085d840b4852e50ecf4a0a044932082f6103d59c5bb794fe97241b2188b148d` |
| Debug/test APKs / shrunk unsigned release | Pass |
| Original vector/chime/voice assets | Checks pass; no generated imagery |
| Public APK/signer/checksums/build metadata | Exact matching anonymous HTTP200 downloads |
| Physical phone/watch/overnight/accuracy | Not qualified; no attached reference hardware/data |

Final public release: [debug-71.1-b7afd0c9db9b](https://github.com/BananaJeanss/solsleep/releases/tag/debug-71.1-b7afd0c9db9b).
Version `0.1.0-dev.71.1.b7afd0c9db9b-debug`, code 17101, API29 minimum/API37 target,
debuggable application `dev.bananajeans.solsleep.debug`.
APK SHA-256: `2e94f41f39258efeb491c487b1873344d7017032d658471fd0316ffc0d99bbb7`.
Certificate SHA-256: `cccd603ec9d9a76750bb6bc0a5b56148f0c5babde6e4f678802b5548fdfafc5b`.
Default runner development signing is ephemeral; this is not a production signer.

| Retained artifact | ID | ZIP SHA-256 |
| --- | --- | --- |
| CI62 API29 | 12284172817 | `5df08d27342e6eb73c860e99713f060a1e592784f0f24793a6a38bd19534dcfa` |
| CI62 API37 retry | 12285932017 | `c7117f4c51a8802e80e32e62eaecf36138be34e1987dd211e37b5a119b08bc89` |
| CI66 debug bundle | 12284723058 | `e331babeb54e305e94650b557baedc223f2d96ed47c93aa049e35dbb8efa0899` |
| CI66 build/core | 12284703080 | `dc66433732c61eb746126335066516525572ce3016e8a33d891361176e1eaa65` |
| CI66 API29 | 12285054827 | `fe55ca5e9b5d5a87eb2bb7b1593f3e352b88e663d7c38a2d677bfdb6477ebf25` |
| CI66 API37 | 12286381536 | `97c1aeb0d60c6fee407c897e4816cf8414c99093e67dc2836bc12e88d189b0d9` |
| CI68 debug bundle | 12286895387 | `4bca3cbf56200075b46d79dfece920f1f069f1bb71ce61f070f5f43044f593a5` |
| CI68 build/core | 12286850421 | `093a7264bb82c6f91d62082b9b20de604ef222f4e94def9c1ccca62fc70cb48b` |
| CI68 API29 | 12287056799 | `c4fbb1e1a236a74b12d59b1980505761c6ccd5234e45759d69728e44fda56f6f` |
| CI68 API37 | 12287475984 | `af1110f8516409dbc05a442e8e342c7ed836dc34aeea7f70bb9255f406af55cd` |
| CI71 debug bundle | 12287757977 | `dcc88f1f0a124bf413d62dbefaa1916dcd88c78a7456ae181f6941459deafb22` |
| CI71 build/core | 12287940967 | `081444d78ce34df53370138784c0c25aca056a560b727e5dbb93bba5eb9b3168` |
| CI71 API29 | 12289460243 | `2680a0c960cd12964967bdcf2e719e78bac912bf09e0915b39d01eafecdb9d97` |
| CI71 API37 | 12287344425 | `e25a00f0207fd764ddd161aa4e204f216810657ba2798c7a827f0bed789763c0` |

The final public verifier reads exact main build/core/lint artifacts separately
from its unauthenticated release downloads. It validates original report XML
and records digests; it executes no artifact code. The release publisher downloads
its exact successful build artifact, verifies byte-identical remote assets before
publication and supports retry without replacing tags/assets. All report gates,
both API suites and prior assertions remain enabled.

Hardware acoustic/haptic gain, lock-screen/emergency navigation, ordinary-call
focus, real NFC/microphone/voiceover false accepts, OEM background recovery,
12-hour energy/temperature, physical storage faults, TalkBack/RTL/wide layouts,
physiological accuracy, sonar and watch acquisition remain separate gates.

Retained lint XML inspection in [CI73](https://github.com/BananaJeanss/solsleep/actions/runs/39474931506)
identified both warnings as available dependency upgrades: Gradle 9.8.1 → 9.8.2
(`AndroidGradlePluginVersion`, wrapper line 4) and kotlinx-serialization-json
1.11.0 → 1.12.0 (`NewerVersionAvailable`, version catalog line 21). Both are
Warning severity; zero lint errors is not a claim of zero lint issues. Dependency
upgrades require their own compatibility review and complete format/migration and
native checks. The qualified published build retains its pinned versions.
