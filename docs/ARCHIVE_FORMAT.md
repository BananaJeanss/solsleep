# Alarm archive v1 / v2

The initial portability slice exports only existing alarm definitions. It is a
UTF-8 JSON document (`application/json`), independent of Android, SQLite, accounts,
and a backend. It is deliberately named an alarm archive, not a complete sleep
backup. Health records, recordings, device permissions, credentials, current
snooze/ringing state, OS PendingIntents and playback tokens are excluded.

Container fields are `format` (`solsleep-alarm-archive`), `version` (1, or 2 when challenges are present),
`createdAt` (ISO instant), `alarmCount`, `alarmsSha256`, and `alarms` (array).
Definitions use the shared logical rule format: v1 without a challenge, v2 with a frozen challenge policy, pack and optional PCM voice reference. Both versions are readable. Definitions use the same format as the alarm database; every recurrence
and user-configurable definition field is retained, including optional zone,
skip date, source identity and revision. Null zone means follow the device zone.

The SHA-256 is lowercase hex over the array's compact JSON representation, as
emitted by kotlinx.serialization, in the original property/element order. The
writer sorts definitions by identity. This is a format-specific corruption check,
not a general JSON canonicalization scheme or digital signature. Editing and
reformatting an archive can invalidate it. A hostile party can replace both data
and checksum: do not describe the checksum as authentication. The preview also
shows the file's byte fingerprint internally for later fixture comparisons.

Challenge/voice content counts toward the same limit; oversized exports fail before writing and never omit a rule or clip. Duplicate JSON keys are rejected, including escaped duplicate names.

The parser consumes at most 512 KiB plus one sentinel byte, permits at most 500
alarm definitions, caps structural nesting at twelve before JSON parsing, decodes
UTF-8 strictly, checks counts/hashes/identities/types/versions, validates dates,
time zones and settings, and refuses unknown fields rather than silently losing
future behavior. It accepts no embedded paths, URLs, executable entries, ZIP
members, or external references. No archive content is extracted to a path.

Export takes a single logical database read, including committed WAL data, rather
than copying a live SQLite main file. It writes through a user-chosen document
provider. Completion is recorded only after output and close succeed. Provider
failure can leave a partial newly created document; that attempt is reported as
failed. The previous successful timestamp is retained. Local audit timestamps
are not proof that an external file still exists or remains accessible.

Inspection fully validates the bounded document before presenting a record count,
creation time, some labels/times, and the count of existing identities. Merely
inspecting is also the test-restore validation action and does not write records.
The explicit **Add missing alarms** action recalculates conflicts under the engine
lock, then inserts all missing definitions in one SQLite transaction. On any
failure the whole insert batch rolls back. Existing identities are skipped,
including differing revisions, so an import cannot overwrite or silence a live
alarm. Restoring the same archive is idempotent. Imported definitions are always
disabled and have no pending occurrences; enabling one later uses normal fresh
scheduling and permission checks. The restore preview survives activity rotation
in its ViewModel; process death discards the preview without changing the store.

## ADR-007: initial scope and encryption gate

This initial alarm-only format is **plaintext**, stated before export. Android
credential/device storage protection does not encrypt an exported document. No
portable encryption, password recovery, automatic destination retention, general recording/audio
inclusion (v2 includes only deliberately selected short challenge references), reference-app CSV compatibility, or complete health restore is claimed.

Before health/audio archives are implemented, define and review authenticated
encryption, password KDF and parameter limits, format negotiation, wrong-password
behavior, recovery/key-loss UX and portable independent readers. Do not use a
Keystore-only key for portable archives. Future formats must retain a reader for
supported earlier archives or provide an explicit loss report; never silently
reinterpret this version as a complete backup.

Sources read for implementation:
- [Android document contracts](https://developer.android.com/reference/kotlin/androidx/activity/result/contract/ActivityResultContracts)
- [CreateDocument MIME contract](https://developer.android.com/reference/kotlin/androidx/activity/result/contract/ActivityResultContracts.CreateDocument)
- [Kotlin JSON element handling](https://kotlinlang.org/docs/serialization-json-elements.html)
- [kotlinx.serialization stable 1.11.0](https://github.com/Kotlin/kotlinx.serialization/releases/tag/v1.11.0)
