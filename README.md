<div align="center">
  <img src="assets/solsleep.svg" width="160" height="160" alt="An Android robot asleep on a pillow under a blue blanket">
  <h1>SolSleep</h1>
  <p>Calmer nights. Kinder mornings. Free software.</p>
</div>

SolSleep is an independent, free and open-source Android™ sleep app in early development. The goal is the functional breadth of Sleep as Android, with an original Material 3 Expressive interface, durable records, and transparent measurements.

The current implementation provides **local alarms and a manual sleep journal**. It is not a finished sleep tracker. See [STATUS.md](STATUS.md) for implemented behavior and evidence, and [PLAN.md](PLAN.md) for the full roadmap.

## First alarm workflow

- Create/edit dated, selected-weekday, interval and alternating ISO-week alarms, with optional fixed time zones.
- Enable/disable, skip the next weekly occurrence, and undo a skip.
- Receive an exact local alarm, with a gentle sound ramp and optional vibration.
- Choose the sound ramp, vibration, snooze duration and maximum snoozes, or dismiss.
- Check exact-alarm, notification, full-screen, and volume readiness.
- Recover schedules after a reboot, including before first unlock.
- Export a portable alarm file, inspect it, and restore missing alarms switched off.
- No account, ads, network permission, or analytics; exports go only to a document destination you choose.

Time-zone and DST behavior is defined and tested in the core; physical-device delivery gates are tracked separately.

## Manual journal

Use **Journal → Add a night** to record remembered bed/get-up times, restfulness,
notes and tags. Corrections keep all earlier revisions; Saved corrections can restore an earlier
version as a new revision. Concurrent stale edits
are rejected. Times retain their original zone, and repeated daylight-saving
times require choosing the intended offset. Reported duration is not measured
sleep. You can export every revision, inspect a journal file and restore missing
nights. Journal exports are read back from the selected destination before being
reported as verified. Failed and unverified copies have distinct messages.
Journal data is credential protected and independent of alarm delivery.
Deletion removes all local revisions. New journal exports carry deletion identities,
so restoring the newer copy first prevents an older backup from bringing those
entries back. Existing destination entries are kept and conflicts are reported.
Previously exported copies remain independent.
See [journal/storage limits](docs/JOURNAL_STORAGE.md).

## App screens

<p>
  <img src="docs/screenshots/tonight-empty.png" width="230" alt="SolSleep first alarm screen with its sleeping Android mascot">
  <img src="docs/screenshots/tonight-alarm.png" width="230" alt="A real scheduled weekday alarm">
</p>

[All captured screens, including large text](docs/SCREENSHOTS.md). The tested
milestone passes 97 JVM tests and all 49 device tests on both API 29 and API 37.
[Checks and development APK artifact](https://github.com/BananaJeanss/solsleep/actions/runs/39041179253).

## Build

Requires JDK 17, Android platform `android-37.0` / API 37, and Build Tools 36.0.0. Set `ANDROID_HOME` or `sdk.dir` in an untracked `local.properties`.

```sh
./gradlew :core:test :app:assembleDebug :app:lintDebug
./gradlew :app:assembleRelease
./gradlew :app:connectedDebugAndroidTest
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Debug ID: `dev.bananajeans.solsleep.debug`. Release builds are shrunk and unsigned. No production signing key is committed. Instrumentation requires a running emulator/device; see [docs/TESTING.md](docs/TESTING.md).

Instrumentation installs are disposable and may clear/uninstall debug data.
Use a dedicated emulator/test install. CI debug signing identities can change;
export and verify records before switching development builds or signing keys.
A different signer cannot update an existing install in place. Keep a stable
signing identity for any installation holding personal records.

## Alarm setup

Allow precise alarms and notifications in **Alarm setup**, and full-screen access if desired. Use the sound preview at your intended alarm volume.

A force-stopped application or powered-off phone cannot be made dependable by this app. Physical-device, OEM, and overnight validation remain required. This development build is not production ready.

## Alarm archives

In **Alarm setup**, use **Export alarms** to save a plaintext JSON file. **Inspect or restore a file** validates it and previews conflicts before any change. **Add missing alarms** keeps existing definitions and adds restored ones switched off. Re-enable them after reviewing dates and settings. Sleep records/audio are not part of this initial archive. See [the format and restore contract](docs/ARCHIVE_FORMAT.md).

## Design and privacy

The cute sleeping Android logo is drawn directly as SVG paths. Its Android vectors and monochrome themed launcher icon are reproducible:

```sh
python3 tools/generate_brand.py
python3 tools/generate_tone.py
```

There are **no image-generated assets**. The local chime is synthesized from sine waves without external recordings or a generation service.

Alarm schedules, labels, and necessary output settings use a small device-protected database so they work before first unlock. No health history, recordings, credentials, or location is stored there. See [docs/DECISIONS.md](docs/DECISIONS.md).

## License

Code is [GPL-3.0-or-later](LICENSE). See [NOTICE](NOTICE) for third-party notices.

The Android robot is adapted from Google's artwork under [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/). Changes include its sleeping pose, pillow, blanket, palette, and monochrome silhouette. Android is a trademark of Google LLC. No Google endorsement is implied.
