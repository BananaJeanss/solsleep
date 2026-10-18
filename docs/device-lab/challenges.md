# Physical wake-up challenge qualification

Use a disposable development install on supported physical phones. Record API,
vendor/build, signing/build SHA, audio/NFC hardware/routes, locale, font scale,
permissions, battery state and exact wall/monotonic times with the existing result
template. Keep a trusted independent alarm. This protocol is not a claim of a
completed test; no phone is attached in the development environment.

Never place an emergency-services test call. Opening/navigating the system dialer
or lock-screen emergency UI is sufficient for those controls. Ordinary incoming
calls may be tested with consenting participants/devices.

## Alarm identity, recovery and audio

1. Save one plain alarm and one configured challenge alarm. Reopen and reboot;
   verify definitions, policy, chosen pack and reference remain exact. Confirm
   an alarm can fire before first unlock without opening journal/capture storage.
2. At ring, choose Snooze, make a wrong answer, complete part of a sequence and
   record task ID, revision, stage/step, misses and original start. Rotate/reopen,
   move between Home and the task, and test a controlled process interruption.
   Verify progress/seed/deadlines survive; stale notifications cannot act on the
   next occurrence. Force-stop/power-off are Android owner controls, not a bypass
   SolSleep attempts to defeat or silently undo.
3. Measure actual sound with a suitable external recorder/meter, noting system
   alarm volume and route. Test initial gain 5/15/40%, each miss and the original
   60-second return to full gain. Reopening must not restart the quiet period.
   Leave the task and verify normal gain returns; stalled storage/UI must not
   retain a quiet lease indefinitely. Local gain is amplitude, not a calibrated
   percent/dB change in the room. Test media/default/tone fallback separately.
4. Try normal Snooze/Dismiss from task and notification, limited snoozes and two
   simultaneous alarms. Verify complete proof is retained when replacement
   registration/publication fails; the original cannot stop before both succeed.
   Never simulate failure by deleting a personal database. Core fault-injection
   tests cover these boundaries independently of destructive hardware faults.
5. Wait through the original 30-minute ringing expiry with a partially completed
   task. Verify no answer/replay/action switch extends it. Check time changes
   and original-start recovery without inventing a new ringing start.
6. During a task/reference playback, receive an ordinary call and open the
   dialer/lock-screen emergency navigation without placing an emergency call.
   Verify calls/system audio have priority, no task blocks system controls, no
   automatic call is placed, and task progress remains. Check post-call audio
   focus recovery and haptics. Repeat reference playback after the minute to
   verify it cannot repeatedly duck/reset the live alarm.

## Accessibility and alternatives

Use TalkBack, 200% system text, largest display scaling, dark/light/dynamic themes,
portrait/landscape and the software keyboard. Phone access must remain visible
and operable outside the scrolling puzzle. Every sheep has a spoken eyes state;
maze position and open directions must be usable without the drawing. Check
numeric locale input and custom non-Latin text. Complete each memory task after
hiding; replay must count a miss and retain the same prompt.

For each task, choose the alternative. It must retain completed stages, add two
code rounds and still require completion. Exercise missing tag, NFC off, missing
microphone, permission denied, privacy mute, input contention and an unfamiliar
pack. No sensor or content failure may strand access to the phone.

## NFC

Enroll supported tag technologies, test before/after lock/unlock and repeated
reads, then scan a different tag. Confirm only the learned UID hash is retained,
no card/NDEF data or external URL is read/launched, and Pause disables reader
mode. Tags with unstable/random UIDs may be unsuitable; the fallback remains.
A UID hash is a nonsecret task token, not secure identity authentication.

## Voice and voiceover

1. Deny/grant permission through the real Android prompt; cancel it, background
   the Activity while pending and rotate. No deferred result may start capture
   while the task is hidden. Record after explicitly returning and tapping.
2. Verify six-second and stalled-read bounds, Stop/Back/Home/rehearsal disposal,
   route changes, mic privacy mute and a competing higher-priority input. Check
   actual AudioRecord/AppOps cessation; the privacy indicator may show recent
   use separately from an active microphone. No attempt file should appear in
   app storage or archives. No network upload is implemented or permitted.
3. Test normal speech, whisper, shouting/clipping, silence, TV/music, alarm-only,
   vibration and replayed reference across rooms/routes. Measure baseline and
   reference bleed-through. No amount of silence/clipping may pass by an error;
   do not claim a replay/ambient false accept proves a person is awake.
4. Collect consented labeled score trials across speakers/languages/ability,
   devices and room noise, with reference/algorithm/build IDs and the observed
   false accept/reject counts. No raw speech is retained automatically. Any
   separate research recording requires its own explicit consent/storage policy.
   Tune provisional 40/55/70 targets with evidence, preserving the experimental
   label until useful thresholds are qualified. Shouting must not be rewarded.
5. Import own 0.5–6 s PCM references at supported rates/channels, invalid/truncated/
   oversized files and silence. Verify failed import retains existing choices;
   successful selection is rehearsable offline after provider access is gone.

## Custom content and portability

Import the independent three-level JSON example, a full bounded pack, malformed
UTF-8, duplicate/unknown fields, excessive nesting and invalid sequence indices.
Markup/URLs must stay plain text. No script, page, file include, permission or
network capability may appear. Verify visible import/export success and failure,
output close/readback through actual local and chosen commercial providers, quota,
revocation and offline behavior.

Export an alarm with a custom pack and voice reference; inspect and restore into
a separate disposable installation. Confirm exact content, disabled restored
alarms and no restored active proof/token. Exceed the 512 KiB archive bound and
verify explicit failure before writing, with no silent omission. Keep the known
working source/copy until actual provider recovery has been verified.

Record failures with reproduction and retained original data; do not mark a gate
passed from JVM/emulator results. Camera smile/barcode, Google image reCAPTCHA,
movement and external-app extensions are not shipped and are not qualified by
this protocol. Sonar/watch/physiological accuracy require separate hardware and
reference-data work.
