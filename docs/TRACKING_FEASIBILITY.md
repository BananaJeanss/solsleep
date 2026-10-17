# M0 tracking feasibility gate

Research checked 17–18 October 2026. This is an engineering decision record; software qualification does not establish physical overnight performance or distribution approval.

## Current verdict

The build now has an experimental, user-started `specialUse` phone accelerometer
recorder with a specific manifest subtype. Its complete software matrix passes
at the exact PR #7 source in STATUS.md. Alarm audio remains a separate execution
path; it does not confer sensor access. No microphone, health, activity-recognition,
high-rate, Bluetooth or network permission is requested for motion. Physical
energy/signal/accuracy and Play subtype review remain open; service startup is not
an approval for distribution or a validated sleep measurement.

| Candidate | Decision and evidence needed |
| --- | --- |
| Plain background sensor listener | Reject for continuous screen-off capture. Android sensor guidance restricts background delivery; test in a foreground service. |
| `health` | Current prerequisites include actual permitted health/activity access or high-rate sensor declaration. A low-rate accelerometer-only design must not request unrelated permissions merely to pass that check. Reconsider when real health acquisition is implemented. |
| `specialUse` | Candidate for an otherwise unclassified, user-visible continuous task. Declare purpose in the service subtype and supply distribution review evidence. Runtime success alone does not establish Play acceptance. |
| `dataSync`, `shortService`, media playback | These do not describe overnight sensor acquisition. Keep file transfer and alarm sound in their own permitted paths. |
| WorkManager | Appropriate for deferrable follow-up work, not continuous motion capture or exact wake-up deadlines. |

## Original spike requirements and remaining physical gates

The initial debug probe preceded the actual recorder. The following requirements remain the acceptance framework for that recorder:
A visible Start action must request only required access, commit a session start,
start the foreground service, and immediately show an ongoing status/Stop action.
Use a bounded sensor-event buffer on a HandlerThread. Persist timestamped sample
chunks with boot identity, monotonic and wall-clock anchors, source/sensor metadata,
sequence numbers and explicit missing-data markers. A missing sensor is a visible
failure, not a synthetic successful night.

Run on API 37 and the oldest supported API. Verify:

1. Start while visible; continue with screen off; Stop unregisters listeners,
   closes the journal and removes the foreground notification.
2. Start/restart denial is reported; no broad retry loop starts services from
   the background or after force-stop.
3. The hard alarm remains independently registered when tracking starts/stops,
   crashes, is denied access, or exhausts its storage budget.
4. Kill the process at each file/database publication boundary, then compare
   committed data and gap reports. Preserve earlier valid chunks.
5. Capture a 12-hour physical-device run with sample continuity, actual sensor
   rates, battery/thermal measurements, Doze transitions and user stops. Emulator
   event injection is useful protocol evidence, not an accuracy or energy study.
6. Review current distribution requirements with the actual subtype/flow and
   evidence. Optional microphone acquisition needs its own visible-start and
   while-in-use checks; alarm or boot recovery does not silently re-enable it.

## Debug probe implementation

`src/debug` now contains an unexported diagnostic Activity and a `specialUse`
foreground service. Instrumentation starts it from a visible button, checks actual
accelerometer callback counters and monotonic timestamps, turns the emulator
screen off, verifies later persisted counters, stops acquisition, and verifies the
hard alarm definition/token and OS deadline are unchanged. An AtomicFile snapshot
stores only counters/timing/state, not sample vectors or sleep records. The probe
has a five-minute deadline and bounded partial wake lock, no sticky restart, a
visible Stop action, and no unrelated health/high-rate sensor permissions.

The probe service, Activity, strings and subtype remain debug-only. Release now intentionally includes the separate private recorder and SPECIAL_USE permission; the probe components remain excluded. The probe passed on API 29 and API 37 emulators; STATUS.md links the exact
source/run and test counts. The passing emulator probe does not close the chunk durability, 12-hour capture,
physical energy, algorithm accuracy or distribution-review gates above.

## Concrete dependencies

A physical Android reference phone, locked-reboot/acoustic test setup, and an
actual overnight sensor run are required to close M0/M1/M3 hardware gates. No
physical phone or Wear device is attached to this workspace. Software builds and
emulator tests can proceed; they cannot establish speaker onset, wrist delivery,
real overnight motion continuity, or OEM energy behavior. Full release readiness
remains blocked on those measurements. The manual journal, chunk-publication foundation, portable deletion/readback and
local export history are now implemented subsets of M2. Experimental phone capture now has local ownership/deletion, bounded storage and streaming file checks. Combined raw/audio archives, encryption, retention and physical recovery qualification still precede collecting irreplaceable nights or treating this as production. See
[the physical qualification protocol](device-lab/README.md) for exact build
identification and NOT RUN evidence records.

## Primary references

- [Foreground service types and prerequisites](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Sensor background limits and practices](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)
- [Foreground service launch rules](https://developer.android.com/develop/background-work/services/fgs/launch)
- [Background start and while-in-use restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Android 17 behavior changes](https://developer.android.com/about/versions/17/behavior-changes-all)


## Actual recorder checkpoint

[PR #7](https://github.com/BananaJeanss/solsleep/pull/7),
[CI36](https://github.com/BananaJeanss/solsleep/actions/runs/39073894634):
120 core tests and all 79 native tests on each of API 29/37 pass, including real
screen-off framework callbacks, pause/resume/readback and hard-alarm preservation.
No actual phone/watch was connected at this checkpoint. The native runtime path
is implemented; the 12-hour physical run, reference placement/calibration and
accuracy/distribution gates are not. See [the capture contract](MOTION_CAPTURE.md).

Single finished-recording plaintext portability is now qualified in PR #8. See
[MOTION_ARCHIVE.md](MOTION_ARCHIVE.md); no sonar, watch or physiological inference
is introduced by a raw-data archive.
