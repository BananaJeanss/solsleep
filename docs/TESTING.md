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
- AlarmUiTest: real create/edit/skip/toggle/delete flow and draft/identity preservation through recreation.

The delivery test grants its debug package test-device exact-alarm/notification access through the shell. Production code never bypasses permission checks.

Screenshots are saved to the debug app's `files/qa` directory:

```sh
adb exec-out run-as dev.bananajeans.solsleep.debug cat files/qa/tonight-empty.png > tonight-empty.png
adb exec-out run-as dev.bananajeans.solsleep.debug cat files/qa/alarm-editor.png > alarm-editor.png
adb exec-out run-as dev.bananajeans.solsleep.debug cat files/qa/tonight-alarm.png > tonight-alarm.png
```

Reproduce source-defined assets:

```sh
python3 tools/generate_brand.py
python3 tools/generate_tone.py
git diff --exit-code -- app/src/main/res/drawable app/src/main/res/raw
```

Still required in the device lab: physical acoustic onset/volume; locked boot and update recovery; actual process loss and force-stop boundaries; Doze/OEM behavior; headphones/Bluetooth/calls and permission denial; full-disk/corruption fault injection; TalkBack, 200% text, landscape/long translations; 12-hour runs and the release-candidate period.

Read STATUS.md for actual completed evidence. Test code existing is not a claim its device gates passed.
