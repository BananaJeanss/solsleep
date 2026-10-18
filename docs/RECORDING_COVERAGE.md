# Recording provenance and observed coverage

Software implementation in progress. Exact qualification is recorded in STATUS.md.
This is data continuity, not sleep duration, wakefulness, stages or a sleep score.

## Acquisition provenance

Each new sensor segment freezes acquisition payload version 1: SHA-256 of the
installed **base APK**, the original Java ZoneId and requested sampling period
in microseconds. Hashing uses only this application's installed APK, on the
capture worker before registering the listener/clock anchor. It identifies those
APK bytes, not a device, source checkout, signing key or complete split package.
Requested cadence is not measured sensor delivery. Every Resume takes a new
snapshot; old recordings are never attributed to the build/settings doing a
restore or inspection.

Capture schema 3 adds nullable `streams.acquisition_payload`, with a 1 KiB bound.
Validated 1→3/2→3 migrations retain original rows/events/counters and leave the new
field null. Existing alarm/raw/journal stores remain independent and untouched.
Malformed, duplicate/unknown fields, invalid version/hash/zone/cadence reject
without defaults or deletion. A damaged/future payload remains retained for repair.

## Recording portability

SOLR v1 reading and byte-identical writing remain for records with no provenance.
SOLR v2 adds a Boolean and, when present, one bounded UTF-8 provenance JSON string
after each stream's counters. Mixed null and known streams remain distinguishable.
Everything else, including original SOLM raw chunk bytes, checksums, identities,
events, limits, provider readback and recoverable restore, keeps its contract.
Older applications reject v2; do not downgrade an installation holding new records.
This is still separate plaintext recording backup, not combined/encrypted backup.

The independent Python generator retains the original v1 fixture and adds a v2
fixture; the inspector validates both without Android or third-party Python code.
Both producers test exact cross-language rewrite and original float bits. Checksums
detect accidental changes; they do not authenticate the author of a rewritten file.

## Coverage policy 1

Only finished COMPLETED/FAILED recordings have an immutable coverage view. Input
is a bounded copied metadata snapshot and raw files streamed in UUID/sequence order,
one checksum-validated chunk at a time. Source/session/boot/anchors must match;
timestamps must strictly increase within each stream. No samples are fabricated,
filtered out or rewritten by this analysis.

For each segment, count actual validated readings and keep first/last monotonic
timestamps. A supported interval needs consecutive readings in the same stream
and consecutive chunk sequences. Its elapsed delta must be at most three times
the original requested interval, capped at two seconds. The policy is inclusive
at this threshold. Longer intervals, missing chunks and recorded interruption
boundaries stay unjoined. Without original cadence, duration is unknown even if
today's recorder would have requested 10 Hz.

Only first-to-last time is partitioned into joined intervals and other time.
Nothing is extrapolated from the anchor, to Stop, between segments or across boots.
The UI uses each original zone and device 12/24-hour preference, with millisecond
reading timestamps; unknown historical zones use explicitly labeled UTC.
Wall-clock rollback does not alter within-boot elapsed differences. Overlap
within one boot is flagged; segment durations are never added into a night total
or percentage. Rejected-input counts remain original counters, not zero readings.

Missing acknowledged readings and missing chunk sequences are visible warnings.
A published tail ahead of the SQLite checkpoint remains visible as actual data,
without silently rewriting the original index. Corrupt/unmatched raw files or
unresolved/unknown files make analysis unavailable; this is distinct from a valid
empty failed recording. Source files and metadata remain retained. Existing raw
file checks remain available alongside the coverage view.

The input SHA-256 includes a domain marker, policy and metadata-format versions,
length-prefixed exact encoded metadata and length-prefixed original chunk bytes.
The same immutable input rebuilds the same report. Any provenance/counter/event,
raw sample bit or order change changes its input identity. Later caller-list
mutation cannot change the frozen input or remove a stream mid-analysis. Derived reports are
not stored as authoritative health data and cannot overwrite raw observations.

## Execution and qualification

Core tests cover original unknowns, exact thresholds, missing sequences/indexed
samples, stream/boot/interruption boundaries, wall rollback, overlaps, corruption,
canonical ordering and fingerprint reproducibility. Native cases cover actual
SQLite fixtures/migration/reopen/rollback, acquisition snapshots, v1/v2 provider
portability and visible 200% text. Final source must pass both complete API 29/37
suites with actual reports/captures inspected before merge. No emulator continuity
result establishes useful mattress signals, overnight energy or sleep accuracy.

Primary references rechecked:

- Sensor observations and requested rate:
  https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview
- Sensor monotonic timestamps:
  https://developer.android.com/reference/android/hardware/SensorEvent#timestamp
- Within-boot elapsed clock, including sleep:
  https://developer.android.com/reference/android/os/SystemClock#elapsedRealtimeNanos()
- Base APK identity and distinction from split APKs:
  https://developer.android.com/reference/android/content/pm/ApplicationInfo#sourceDir
- Localized date/time skeletons and device clock preference:
  https://developer.android.com/reference/android/text/format/DateFormat
