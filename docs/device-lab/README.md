# Physical qualification handoff

This is a repeatable measurement protocol, not a completed lab run. All physical
cases start **NOT RUN**. Software/virtual-device results in STATUS.md do not
satisfy acoustic, OEM, sensor, energy or hardware power-loss gates.

Use a dedicated reference install. Instrumentation clears/uninstalls debug data;
CI debug signing identities may change. Before any installation/signing transition
on a device holding personal records, export, verify and independently reopen the
copy. This protocol does not install, uninstall, clear data, reboot, change settings,
start recording, enable permissions or send reports automatically.

## Identify the actual build

Requirements: Bash, Android platform-tools (`adb`), `sha256sum`; optionally place
SDK build-tools `apksigner` in PATH. Choose an online authorized reference phone
from `adb devices -l`. Do not infer a physical phone from a cloud emulator job.

```sh
bash docs/device-lab/collect-info.sh DEVICE_SERIAL /path/outside/checkout/new-lab-run
```

The directory must be new. The collector reads a fixed hardware/build property
list and SolSleep package information, pulls **only the installed base APK**, and
hashes it. It excludes personal app data, sensor vectors, audio and serial numbers.
Compare the installed APK digest against the exact CI/local APK whose source
commit is recorded in the result sheet. Package version alone does not identify
these development commits. APK signatures are public metadata; no signing key is
read. A failed/incomplete collection is not a test pass. Keep reports private until
reviewed; build fingerprints and device models identify the test environment.

Use one API 29 reference and one current-API reference, then record OEM/build
coverage separately. Record actual hardware, storage/heap configuration and power
settings; a newer API on a virtual device does not qualify the oldest phone.

## Alarm and interaction cases

| Case | Procedure and evidence | Completion rule |
| --- | --- | --- |
| Speaker onset | Use intentionally audible alarm volume and speaker route. Schedule real UI alarms. Record the original deadline, independent clock synchronization uncertainty, OS dispatch evidence and first acoustic sample from an external recording. Keep every attempt and raw timing observation. | Initial repetitions are smoke evidence. PLAN.md proposes physical p99 onset ≤2 s; report sample count/distribution and uncertainty, never estimate p99 from one or two successes. |
| Locked reboot | Schedule a future alarm, reboot the dedicated phone manually, and leave it before first credential unlock through the deadline. Record lock/boot state and physical output; inspect journal only after unlock. | Delivery must use device-protected alarm state without opening health or export-history stores. Do not label an unlocked boot as this case. |
| Background and OEM | Observe naturally idle/screen-off alarm delivery, process loss and the phone's documented battery policies. Separate any forced-Doze command run from natural-idle evidence. | Original due/ringing-start timestamps, independent deadline and user-visible recovery remain correct. Keep misses and original deadlines. |
| Force-stop/power-off boundary | Record these as explicit unavailable conditions on the reference setup. | Do not claim the app can deliver while powered off or bypass Android force-stop. |
| Sound and haptics | Test audible ramp, vibration, snooze/dismiss and actual routes, including headset/Bluetooth/call interactions and route loss. Record initial volume, route and observed output. | Notification/service existence is insufficient evidence of audible or haptic delivery. |
| Access repair | Deny/regrant exact alarms, notifications and full-screen access through actual settings and revisit readiness UI. | The saved definition remains; status explains missing access and reconciliation does not fabricate delivery. |
| Accessibility | Run TalkBack, 200% text, landscape, RTL/long translations and wide layouts through actual alarm/journal/save/restore/history actions. | Record focus order, usable actions and clipped/ambiguous content. Emulator large-text success does not replace physical assistive interaction. |

Do not mix within-boot monotonic timestamps from different boots. A camera/audio
recording and host/device wall clocks need a recorded synchronization method;
unknown timing uncertainty cannot be treated as zero. Keep source measurements
separate from the pass/fail interpretation and proposed engineering targets.

## Persistence and portability cases

- Use synthetic fixtures on the dedicated install for controlled interruption,
  low-space and corruption work. Preserve original files and compare all prior
  records/chunks after restart. Do not infer hardware flush behavior from a
  thrown exception or process kill alone.
- Exercise a real local document provider and each intended remote provider.
  Reopen the saved document after app/device restart, test available/denied/offline
  states, and inspect it on a clean install with a compatible signer/workflow.
  Compare every revision, note/tag/rating/zone and deleted identity. Restore newer
  deletion-bearing copies first; incoming markers never delete current local work.
- Journal export readback proves the provider response at that moment. Observe
  provider remote-upload completion separately; it does not certify future
  availability. Test a later failed attempt retaining the earlier verified receipt,
  a process-lost pending attempt remaining unverified, and unavailable auxiliary
  history still allowing actual journal recovery.
- Current v2 export limits are explicit. Oversized histories must fail without
  omissions. Streaming/partitioned large-history backup and UI pagination remain
  unfinished; a format-limit error is not a completed large-history qualification.

## Tracking, crypto and watch dependencies

The release has **no production recorder**. The debug motion probe is limited to
five minutes and stores counters, not a night. Use its visible Start/Stop flow only
for that bounded screen-off delivery check; never represent it as a 12-hour run or
sleep accuracy result. A lawful durable recorder/ownership workflow must exist
before real overnight capture, sample continuity, battery/thermal and algorithm
validation. Human reports are not physiological ground truth.

The encrypted-archive design has maintained implementation candidates and test
requirements, but encryption is not implemented. Finish the selected-library
vector/interop/envelope/secret-lifecycle work, then measure the actual Argon2
profile's latency, sampled/peak memory method, cancellation behavior and thermal
cost on the minimum reference phone. Do not silently weaken a KDF or call desktop
numbers phone qualification. Crypto/format review and physical measurements remain
gates; this collector does not run a cryptographic benchmark.

Wear/Health Connect acquisition still needs implementation and hardware evidence.
Reference CSV compatibility requires a consented, sanitized real reference export;
synthetic SolSleep fixtures establish neither reference-app compatibility nor
clinical tracking accuracy. Do not upload personal night/audio fixtures as part
of an automatic lab report.

## Result record

Copy `result-template.md` outside the checkout for each source/device/scenario.
Use PASS, FAIL, BLOCKED or NOT RUN and retain exact observations and failed runs.
Link evidence only after reviewing it for personal data. No provided template or
collector output closes a release gate by itself.
