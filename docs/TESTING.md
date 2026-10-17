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
