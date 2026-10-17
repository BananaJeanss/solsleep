# Experimental phone motion capture

Implementation checkpoint: 18 October 2026. Qualification is pending; the previous
107-core/61-per-API evidence does not cover this new source. See STATUS.md for the
exact final tested revision and results when available.

## What the feature records

A user explicitly starts the Phone motion screen from Tonight. The screen explains
battery, physical qualification and portability limits before starting. It saves
actual accelerometer SI vectors (m/s²), their original monotonic timestamps, a
millisecond wall-clock anchor, stream/boot identities and source name, vendor,
version, resolution, range and wake-up capability. It requests a 100 ms interval;
this is not a guaranteed observed rate. Actual timestamps remain available.

There are no sleep/stage/heart-rate/breathing/snore estimates, microphone capture,
watch connection, network upload or automatic start. This does not create a manual
journal entry or claim a measured night. Do not place a phone under bedding or
pillows. Physical overnight energy, sensor usefulness and accuracy are unqualified.

## Execution and permissions

A private, non-Direct-Boot `specialUse` foreground service runs only after the
visible user action. Its manifest subtype describes movement-only overnight
recording. Android's documented `health` type requires actual applicable runtime
or high-rate prerequisites; this implementation does not request unrelated body,
activity or high-rate permissions to make itself eligible. `specialUse` needs
review for Play distribution; no approval is claimed. Notification access and the
recording channel are required and rechecked during capture. The notification
opens recording controls and contains a session/revision-scoped Finish action.
A stale action cannot finish a newer revision/session.

The production manifest now deliberately includes SPECIAL_USE and this recorder.
The earlier debug-only diagnostic remains separate and absent from release. Alarm
mediaPlayback/direct-boot behavior remains independent; no capture database or
sensor initialization is added to the alarm path. No network/microphone/health/
Bluetooth/location permission is introduced.

A process-wide Handler serializes sensor callbacks, commands and old-service
cleanup. No sensor processing, chunk flushing or SQLite work runs on the main
thread. A bounded partial wake lock is released on pause, finish or interruption;
its timeout is one minute beyond the 12-hour segment limit. START_NOT_STICKY and
no boot receiver mean capture never silently resumes. Reopening reconciles a dead
execution owner to INTERRUPTED. Only an explicit Resume creates a new stream and
clock anchor. Paused time is not recorded or filled.

## Durable state and observation authority

The separate credential-protected `motion-capture.db` schema 1 uses FULL/WAL,
foreign keys, an expected revision, unique start request, and a partial unique
index allowing only one open session. State and events commit together. Corrupt
and unknown-schema files are retained, not reset. The checked-in SQL fixture is
`schemas/capture/1.sql`. Process identity is distinct from the per-service owner;
boot identity is random and persisted against Android BOOT_COUNT. If boot count
is unavailable, identity is conservatively limited to the process, not an
invented cross-process boot relationship. No stable device ID is stored.

PREPARING becomes RECORDING only after the first checksummed raw batch publishes.
The session has distinct paused, interrupted, completed, failed and deleting
states. A durable recording state means last-known acquisition, not proof a killed
process is still running. Reconciliation does not invent missing observations.
Wall times can move backwards; event sequence and sensor monotonic time preserve
ordering. A new anchor is required after a clock discontinuity.

`MotionCaptureBuffer` admits only finite, increasing readings at/after the anchor.
It aggregates rejected readings and detected gaps greater than two seconds.
Sensor accuracy changes and timestamped gaps are retained as events. Original
accepted values are never replaced by normal-looking zeros. The bounded batch is
at most 128 samples, with a four-second timer and time-based/full-buffer flush.
File publication uses the existing checksummed v1 chunk protocol, file sync,
same-directory atomic rename and directory sync. A failed publish retains exact
batch/sequence for an identical retry. Published files precede the rebuildable
SQLite progress acknowledgement; no file/database atomicity is claimed.

A saved-file check validates one bounded chunk at a time and retains only sequence
and per-stream counts. It does not materialize all sample vectors for a night.
Corrupt files, sequence gaps, missing source metadata and progress claiming absent
observations are reported. They block resuming that damaged session; they are not
silently deleted. The index can legitimately lag a process-interrupted file
publication. UI counts distinguish actual validated file content from that index.
Source/event lists and file-path/sequence metadata are not yet fully paginated.

## Bounds, interruption and deletion

A segment stops after 12 hours, 15 seconds without fresh samples, loss of visible
notification capability, a wall-clock difference greater than two seconds, or a
storage failure. The monotonic stale/time limits are distinct from wall-clock
checks. Before each batch publication, 64 MiB free space is required and the entire
next chunk must fit a 64 MiB per-session raw bound. Previously published files are
retained; low-space reserve cannot guarantee an OS/hardware write will succeed.
Five-second crash-tail and filesystem durability targets remain physical gates.

Pause/Finish unregister acquisition, attempt final-tail publication, commit state
and release resources. A failed final write is interrupted, not a fabricated
complete result. Service destruction is recorded as interruption when possible;
an unavailable database is preserved and the UI reports storage unavailable.

Only completed/failed recordings can enter DELETING. The intent is durable before
raw removal. Under the chunk store's existing process/OS lock, deletion first
validates that the dedicated UUID directory contains only owned regular motion
files plus its lock. Unknown files or symlinks cause retained failure. Partial
unlink failure leaves retryable DELETING state. The directory is flushed; only
after raw deletion succeeds is session/source/event metadata removed atomically.
The empty lock inode/directory is retained so a held lock is never unlinked and
replaced by a parallel writer. Unlink is not secure erasure of flash storage.
Manual journals and alarm definitions are unaffected.

Raw capture has **no portable export or restore yet** and is not in Journal JSON.
The consent text states this before data acquisition. Uninstall removes these
private files. Retention settings, combined encrypted archives, independent raw
recovery tooling and automatic deletion propagation remain M2 work. Recording
history uses bounded pages, newest first, independently of changing wall clocks;
the open session stays accessible while browsing older history.

## Qualification to run

- Core: exact batching/finite values/monotonic rejection/gaps, size/time bounds,
  publish/rename retry, streaming summary and conservative ownership deletion.
- SQLite: actual reopen, complete source fields, idempotency, simultaneous cold
  starts, stale/wrong-owner actions, orphan reconciliation, transaction rollback,
  monotonic counters, retryable deletion, SQL fixture/future schema/corrupt bytes
  and history pagination.
- Android API 29 and 37: actual SensorManager callbacks with screen off, visible
  pause/resume/finish, readback of vectors/chunks/metadata, stale action rejection,
  unexpected service stop, reopening without auto-resume, native deletion, 200%
  consent/controls and independent AlarmManager deadline preservation. Actual UI
  captures must come from the tested source, not synthetic samples/mockups.
- Physical: audible alarm/haptics and OEM behavior, real sensor latency/placement,
  12-hour energy/temperature, process kill/reboot/power-loss, storage exhaustion,
  TalkBack/RTL and Play subtype review. Emulator callbacks prove code paths only.

## Primary documentation reviewed

- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview
- https://developer.android.com/reference/android/hardware/SensorEvent
- https://developer.android.com/reference/android/os/PowerManager.WakeLock
- https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase
