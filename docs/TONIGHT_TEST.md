# Trying SolSleep tonight

This is a development test checklist, not overnight reliability or sleep-accuracy
qualification. Keep your usual independently trusted wake-up alarm enabled.

## Install and preserve data

Download `solsleep-debug.apk` and its checksums/build information from
[Releases](https://github.com/BananaJeanss/solsleep/releases), or the dedicated
debug artifact in [Android checks](https://github.com/BananaJeanss/solsleep/actions/workflows/android.yml).
An Actions artifact is a preview until its complete run passes. Android 10/API 29
or newer is required. See [DEVELOPMENT_RELEASES](DEVELOPMENT_RELEASES.md) for the
certificate and version policy.

If an existing installation has personal records, finish active recordings and
save verified external alarm, journal and individual recording exports first.
Those are separate workflows. A different signing certificate prevents an in-place
update; uninstalling removes private data. Do not uninstall merely to get past an
update error. Keep an installation with a stable signer for continuing records.

## Short checks while awake

1. Open Tonight and review alarm readiness. Allow the Android permissions/access
   the app actually requests, including notifications and exact alarms as needed.
   Check the alarm audio level and channel on your phone.
2. Set a plain alarm a couple of minutes ahead. Test audible output/vibration,
   then snooze and let it fire again. Try a separate short alarm with the screen
   locked. A successful emulator suite cannot establish your phone's behavior.
3. Enable one Easy challenge/one round, rehearse it, then test it on another short
   alarm. Snooze/Dismiss should open the task; ringing should become quieter and
   rise after misses or the bounded quiet period. Try math and sleeping sheep
   before using a longer task mix. Voice/voiceover and NFC remain experimental
   until tested with your microphone/room/tag; normal Android system controls
   remain available. Do not place emergency-services test calls.
4. Start a Phone motion recording for a few minutes. Leave the screen off for
   part of it, pause, resume and finish. Check saved observations and open
   **View recording coverage**. Look for validated readings, plausible original
   times and separate segments after Resume. Paused/unknown periods must not
   become invented sleep or a whole-night percentage.
5. Export the finished recording and wait for verified readback. Keep that external
   `.solmotion` file. Add/edit a manual journal entry, reopen the app and check the
   saved correction. Journal JSON does not contain raw motion recordings.

If any check fails, retain its original records and report what happened; do not
clear storage or reinstall to make the failure disappear.

## Optional overnight motion trial

Start a new recording explicitly. Keep the phone uncovered on a safe firm surface,
not under bedding or a pillow. This is a real motion-data trial; placement,
overnight power/temperature and sensor usefulness are still unqualified. Finish
in the morning, inspect interruptions/coverage and export a verified external
copy before changing builds. A bedside recording may contain little useful
movement; a flat signal is not proof you slept well.

There are no implemented sonar, snore, measured sleep/stage, smart-wake or watch
acquisition results to test. The manual journal is your report, not sensor-derived
sleep. Export-receipt work is a separate milestone until its own qualification.

Useful observations to retain: phone model, Android version, build/certificate
identity, alarm versus expected time, screen/lock state, actual sound/haptic and
challenge behavior, recording start/stop/interruption, battery before/after and
whether export readback verified. Avoid sharing raw personal files publicly.
