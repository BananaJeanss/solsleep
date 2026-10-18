# Offline voiceover task

18 October 2026. Experimental delivery comparison, not speech recognition,
identity verification, liveness, wakefulness or a clinically validated signal.
Physical microphone/room performance remains an explicit qualification gate.

Listen to a short reference and imitate its rhythm and intonation in a normal
voice. The included phrases are original SolSleep text, spoken with the CMU Flite
2.2 `cmu_us_kal` 8 kHz diphone voice. No movie, commercial game, political recording
or other downloaded performance is bundled. Own clips can be imported as bounded
PCM WAV; use speech you own or have permission to distribute.

## Included clips and reproducibility

- `voice_morning.wav`: “Good morning. The sun is up.”
- `voice_captain.wav`: “Captain, the pillows have taken over.”
- `voice_moon.wav`: “Moon control. I am ready for breakfast.”

`tools/generate_voice_clips.py` calls the local Flite 2.2 library through its public
C API. This is a conventional diphone synthesizer, not a neural image or voice
model. The Android application does not link or install Flite. Source phrases and
regeneration code are provided; assets are retained as PCM WAV and covered by
SolSleep's GPL-3.0-or-later distribution with upstream attribution retained in
NOTICE and `third_party/flite-COPYING.txt`. The CMU permissive license/source is:
https://github.com/festvox/flite/blob/master/COPYING
https://github.com/festvox/flite/blob/master/include/flite.h

Regenerate on a machine with Debian's `libflite1` 2.2 package:

```sh
python3 tools/generate_voice_clips.py
```

The application and native tests read the actual bundled assets with the same
strict WAV parser used for imports. CI additionally verifies their recorded
hashes/headers, without requiring an engine at runtime.

## Import and storage

SAF import reads at most 2 MiB plus one sentinel byte. It requires a complete RIFF
WAVE file, one PCM fmt chunk (16-bit mono/stereo, 8–48 kHz), one bounded data chunk,
valid byte rate/block alignment, frame-aligned data and 0.5–6 seconds of speech.
Compressed WAV, inconsistent/truncated/duplicate chunks, wrong lengths, trailing
bytes and oversized streams are refused. Unknown metadata chunks are skipped
within the same bounds and never executed or treated as paths. Input is averaged
into 8 kHz mono bins, not passed to a general platform codec. A self-match sanity
check rejects silence/unusable imported references before replacing the task.

The reference is frozen as canonical base64 PCM16 (8,000–96,000 bytes) with a
bounded display title in the alarm policy. No live provider URI is retained.
This makes reference playback available offline and after reboot, and preserves
it exactly in the versioned logical alarm backup. It also means it is stored
before unlock and included in plaintext export; avoid sensitive recordings.
Attempt recordings are memory-only, bounded and discarded; reference imports are
intentional retained content. No network, speech service, vendor key or account.

## Scoring and limits

The host computes the score from recorded PCM; it never accepts a client-reported
number. Frames are 40 ms with 20 ms steps. Features include normalized log energy,
voicing and autocorrelation pitch (about 70–400 Hz). Live attempts use the first quiet half-second as a baseline and suppress 100 ms blocks below that baseline plus 8 dB; this is a conservative level gate, not source separation. Leading/trailing low-energy
regions are trimmed. Relative pitch is centered on each utterance's median, so a
higher or lower natural voice is not penalized merely for its register. Dynamic
time warping allows modest timing variation with a bounded band and duration
ratio. Gain is normalized, so shouting does not improve the score. Silence,
unvoiced noise, excessive clipping and extreme duration mismatch score zero.

Difficulty targets are provisional 40 / 55 / 70 out of 100. Synthetic DSP tests
check identity, gain invariance, tempo tolerance, silence/noise/clipping and
bounds; these do not establish real-world recognition accuracy. It compares
acoustic delivery, not the phrase's words. Another phrase, recording or ambient
sound could resemble the template. It must not be presented as a secure human
CAPTCHA or proof of wakefulness. Rehearse with the actual phone, room, alarm sound
and accessibility needs before enabling it. The native code fallback remains
available and adds two rounds instead of bypassing all tasks.

The short visible-activity AudioRecord workflow asks permission on Record, sets
privacy-sensitive capture on API 30+, checks client-silenced state, bounds stalls
and elapsed time, and stops/releases on cancellation or leaving. Android may
return silence when a higher-priority capture owns the input. Never interpret
that as a successful attempt. During a live alarm, reference playback mixes under the alarm’s existing focus and stops when that focus is lost. It does not acquire a second focus grant that could repeatedly duck the alarm. Rehearsal playback requests its own short focus grant and stops on loss;
ordinary call/emergency system access has priority over the task.

## Follow-up execution gates

1. Measure reference bleed-through and baseline suppression on actual phone
   speakers/mics, including the rising alarm, vibration, Bluetooth routes, privacy
   mute and concurrent calls/capture. Refuse unavailable routes explicitly.
2. Evaluate a consented speaker/language/room dataset. Record false accepts and
   rejects, rhythm/pitch error and raw input/version provenance. Tune difficulty
   only with evidence; preserve the experimental label until qualified.
3. Add a user-visible Record-your-own-reference workflow and wider explicitly
   bounded formats only after safe acquisition/decoder tests. WAV import already
   supplies user-made references; arbitrary media URLs are not supported.
4. Consider licensed human-recorded default lines and richer local phonetic
   matching. No copyrighted movie/performance library or required cloud recognizer.
