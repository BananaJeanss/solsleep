# Wake-up challenges

18 October 2026. Implemented software scope; physical qualification is still open.
See [STATUS](../STATUS.md) for exact test/build evidence and remaining gates.

Configure each alarm with **Add a wake-up challenge**, choose Easy / Normal / Hard,
round count (1–12), a mix of tasks and starting sound level (5–40%). Enable
**Require a challenge**, rehearse, save these settings, then save the alarm.
Unconfigured existing alarms retain ordinary Snooze and Dismiss behavior.

## Interaction and safety contract

Both Snooze and Dismiss are protected. Either action opens the configured task.
The same task identity, seed, progress, failure count and original challenge start
survive reopening and delivery recovery. Switching the chosen action retains
progress. A snooze scheduling/storage failure keeps the original alarm ringing
and the completed proof available; retry or choose Dismiss without solving again.
A successfully snoozed occurrence has a new token and requires a new challenge
when it rings again. Repeated or stale UI/notification answers do not complete a
newer round/occurrence.

While the challenge screen is actually visible, the player uses a nonzero local
gain cap. Each miss adds 15 percentage points. Gain also rises linearly to full
level over the original minute. Leaving the screen, an expired visibility lease,
a completed task or a backward clock returns normal gain. Reopening cannot buy a
new quiet minute. The existing gentle-start ramp remains a ceiling; the challenge
does not change Android's global alarm volume. Tone fallback follows the cap too.
Audio focus loss yields to system/call audio; reference playback mixes under the live alarm’s existing focus; rehearsal uses a short grant and stops on loss. Ordinary sound preview is refused while an alarm rings. Vibration settings remain the alarm's settings.

A fixed **Phone / emergency access** control opens public Android `ACTION_DIAL`.
It does not place a call, request `CALL_PHONE`, select a phone number or promise
that a third-party app can bypass the keyguard. Android may require unlocking.
The system's lock-screen emergency controls, Home, Back, power menu, settings and
volume controls remain available. Never test by calling emergency services.
There is no overlay, device administration, kiosk mode, accessibility service,
notification trampoline, hidden emergency API or repeated focus stealing.

Every unfinished task offers an accessible native copy-code fallback. It retains
already completed rounds and adds two rounds; it never grants a plain-dismiss
shortcut. NFC/microphone permission loss or an unsuitable imported task cannot
make the phone unusable. The existing 30-minute ringing timeout is retained and
no task resets the original ringing deadline.

These are wake-up tasks, not security authentication or proof that a person is
awake. A phone owner can force-stop, power off or uninstall an app through Android;
SolSleep does not attempt to defeat those controls. NFC UIDs can be copied, a
recording may resemble a voiceover and accessible labels reveal visual state.
The enforced guarantee is that ordinary SolSleep alarm actions require host-
validated completion, not that the operating system or device owner is trapped.

## Implemented task inventory

| Task | Difficulty changes | Validation / accommodations |
| --- | --- | --- |
| Mental math | Small sums; larger sums/products; product plus term | Exact integer solution; Arabic-Indic numeric input accepted, no expression evaluation |
| Sleeping sheep | 9 / 16 / 25 original code-drawn sheep | One eyes-closed target; descriptive TalkBack labels and copy-code alternative |
| Number trail | 6 / 9 / 12 shuffled numbers | Ascending taps; wrong tap resets this round only |
| Remember digits | 4 / 6 / 8 digits | Explicit memorized action durably hides the prompt; replay counts as a miss |
| Memory sequence | 3 / 5 / 7 pad taps | Four numbered accessible pads, persisted step and replay |
| Maze | 4×4 / 6×6 / 8×8 spanning-tree maze | Every generated maze connected; touch direction buttons expose open passages and position text |
| Reverse digits | 4 / 6 / 8 digits | Exact reversed string; leading zeroes preserved |
| Copy code | 6 / 8 / 10 digits | Exact transcription, available without sensors |
| NFC tag | Same learned tag at all levels | Visible-activity reader mode; only domain-separated SHA-256 UID stored, no NDEF/card data |
| Raise your voice | 1 / 1.5 / 2 seconds above baseline and level threshold | Quiet first half-second, then normal clear speech; finite relative RMS evidence, no words/identity inference |
| Voiceover | Experimental score targets 40 / 55 / 70 | Offline rhythm/relative-pitch comparison, original included or imported reference; see [VOICEOVER](VOICEOVER.md) |
| Custom pack | Separate Easy / Normal / Hard question banks | Native typed answers, choices or ordered sequences; no executable extension |

A shuffled mixed sequence visits each selected type before repeating. All round
prompts derive from the persisted seed and stage; misses do not reroll an easier
question. Custom tasks select a question from the current level's frozen bank.

## Durable protocol and database migration

`AlarmRule.challenge` is optional and versioned with the definition. Rules without
it still encode as rule v1. Configured rules encode as v2 with an exact policy;
archives containing them use container v2. Both old formats remain readable.

The device-protected alarm database migrates schema 1 → 2 with one nullable
`pending.challenge_payload` column. Existing payloads, tokens, deadlines, original
ringing start, snooze count, registration and state remain untouched. Opening
independent helpers is process-locked, migration is transactional, and unknown
newer schemas are retained and refused. No journal/capture store is opened.

The serialized alarm engine creates one UUID challenge and secure-random seed for
the exact ringing token. Answers require token + challenge UUID + expected
revision. Core code derives the question and computes the transition; there is no
client `complete=true` or externally accepted score. SQLite publishes through a
compare-and-set on token, RINGING state and previous serialized payload. Both
engine and storage guard Snooze/Dismiss completion. Publication must succeed
before UI progress or reduced volume is authoritative.

A policy fingerprint prevents reusing proof for another definition. A recovery
registration retains the same pending challenge; snooze creates a new pending row
without the old proof. Configured notification actions open the private activity
directly, including the token and action. Old broadcast actions are still gated
by the engine. A stale activity token does not fall through to a newer alarm.

## Custom JSON pack contract, version 1

Choose **Export pack / example**, edit the plain JSON and import it explicitly. An independently authored [minimal three-level example](../core/src/test/resources/challenge-pack-v1.json) is also tested.
The export contains the selected pack, or the original example when none is
selected. Output is closed, reopened and compared before being considered
verified. Import freezes a validated copy inside the alarm definition; there is
no later dependency on the source document's URI, provider, network or filename.

An [editor JSON Schema](../schemas/challenge-pack-1.schema.json) is provided; the host also enforces byte/Unicode/duplicate-key and exact permutation rules that an editor cannot fully express.

Root fields are exactly `format: "solsleep-challenge-pack"`, `version: 1`, `title`
and `levels`. Levels must contain exactly `EASY`, `NORMAL` and `HARD`, each a bank
of 1–24 tasks. Each task has `kind` and `prompt`, plus:

| Kind | Additional fields | Meaning |
| --- | --- | --- |
| `TEXT` | `answer`, `caseSensitive` | Trim/NFC normalization, optional locale-independent lowercase normalization |
| `CHOICE` | `choices`, `order` | 2–9 unique choices; `order` contains the one correct zero-based index |
| `ORDER` | `choices`, `order` | 2–9 unique choices; `order` is an exact permutation of all zero-based indices |

Example fragment for a task bank (the exported file supplies all three levels):

```json
{"kind":"ORDER","prompt":"Put the day in order",
 "choices":["Evening","Morning","Afternoon"],"order":[1,2,0]}
```

Strict UTF-8 input is capped at 48 KiB before JSON allocation. Root/task fields,
versions, nesting, duplicate keys (including escaped duplicate names), Unicode
surrogates/control characters, array counts, answer bounds and permutations are
validated. Titles are at most 80 characters, prompts 600, answers/choices 120.
Malformed, oversized or non-progressing streams fail without replacing the saved
policy. Markup/URLs in text are rendered as text; there is no HTML, WebView,
JavaScript, fetch, regex execution, shell, file inclusion or Android bridge.
Answers are part of the definition, not secrets or authentication credentials.

This intentionally uses a smaller native declarative contract instead of adding
arbitrary script execution to a critical alarm path. More task kinds can later be
added through an explicit versioned host schema and corresponding tests.

## Privacy, backups and remaining work

NFC and microphone are optional hardware features. Microphone permission is asked
only after an explicit Record action in a visible task/rehearsal. Capture is
bounded to six seconds, cancelled on Stop/disposal, uses nonblocking reads and
releases AudioRecord. Attempt samples are not written to disk or uploaded.
Microphone privacy mute, contention, silence, excessive clipping, denied permission and
errors cannot complete a task. This does not add overnight microphone capture or
sonar to phone motion recording.

Alarm definitions, packs and chosen reference clips live in device-protected
storage so they work before unlock. They are included in plaintext alarm backup;
avoid sensitive prompts or speech. The 512 KiB / 500-rule archive limit remains,
including clips. Oversized exports fail before writing a destination; no clip or
alarm is silently omitted. A combined/encrypted backup and larger bounded format
remain independent work.

Unimplemented: camera smile/laughter and barcode detection, shake/spin/walking/
brightness, jumping game, flags/distorted text, dream-note task, location/travel
profiles, Android third-party app extension and Google image reCAPTCHA. No inactive
controls advertise them. Camera models need compatible model/code licensing and
real low-light/liveness/accessibility evidence. Google image challenges introduce
network, vendor keys/terms, rate limiting and lockout; they must never be a
required offline stop path. Native JSON packs cover custom authoring now.

The step-by-step [physical-device protocol](device-lab/challenges.md) records the remaining checks.

Physical gates include locked/reboot recovery, TalkBack, incoming ordinary calls
and emergency UI navigation (without calling), real NFC tag technologies/absence,
microphone privacy toggle/route contention, reference bleed-through, score false
accept/reject rates across speakers/languages, hearing/accessibility and measured
speaker/haptic output/volume ramp. Emulator/pure DSP tests do not establish these.

## Primary references researched

- Reference behavior: https://sleep.urbandroid.org/docs/alarms/captcha.html
- NFC activity reader mode: https://developer.android.com/reference/android/nfc/NfcAdapter
- AudioRecord reads and lifecycle: https://developer.android.com/reference/android/media/AudioRecord
- Privacy-sensitive input (API 30+): https://developer.android.com/reference/android/media/AudioRecord.Builder#setPrivacySensitive(boolean)
- Activity resource lifetime: https://developer.android.com/guide/components/activities/activity-lifecycle
- Audio input privacy/competition: https://developer.android.com/media/platform/sharing-audio-input
- Audio focus: https://developer.android.com/media/optimize/audio-focus
- Public dial intent: https://developer.android.com/guide/components/intents-common#Phone
- AndroidX isolated JavaScript alternative assessed, not added:
  https://developer.android.com/develop/ui/views/layout/webapps/jsengine
- Camera candidates, not shipped: https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker/android
  and https://developers.google.com/ml-kit/terms
