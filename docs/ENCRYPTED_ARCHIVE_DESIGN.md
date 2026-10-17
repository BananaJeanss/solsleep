# Encrypted archive implementation plan

17 October 2026. **Design and research only.** Current alarm and journal exports
are plaintext and say so in the app. No encrypted-export capability is implied.
This is the next portable-data work after the journal/chunk foundation.

## Required result and threat model

A complete native archive must restore logical records, their revisions and
provenance on a different installation without the old phone or its Keystore.
Optional raw/audio inclusion must be explicit, owned by records and covered by
integrity checks and restore parity. Local credential-encrypted storage does not
protect a document exported to a storage provider. A lost archive or a provider
that can read stored bytes is the principal offline confidentiality threat.

Authenticated encryption must detect a changed header, reordered/removed entry,
truncated payload or changed manifest before any authoritative local change.
It cannot secure a compromised unlocked phone, recover a forgotten secret without
an independent recovery key, or recall old copies after local deletion.
The implementation must preserve source data and report a failed attempt if a
provider fails during write, flush or close. Completion is not equivalent to an
independently verified provider round trip; expose the latter as a separate state.

## Cryptographic choices to qualify

Use maintained implementations, never an original cipher, MAC or password KDF.
Android's cryptography guidance recommends AES with 256-bit keys, including GCM.
Use a reviewed AEAD/streaming-AEAD implementation appropriate for large optional
recordings, with its required nonce and final-segment rules. Do not introduce
Jetpack security-crypto: its APIs are deprecated, and device-bound encrypted files
would not by themselves meet portable recovery requirements.

Prefer a memory-hard password KDF profile. RFC 9106 gives an Argon2id profile for
memory-constrained systems using 64 MiB, three passes and four lanes. Treat it as
a candidate to benchmark on the minimum supported phone, not a measured mobile
performance guarantee. OWASP's password-storage guidance also describes lower
memory profiles and PBKDF2 work factors; those are relevant research inputs rather
than automatic archive-security certification. Do not silently lower a profile
on a slow or low-memory device. A fallback, if required, needs its own documented
and interoperable profile/version and deliberate user choice.

Before selecting a library, compare maintenance, GPL compatibility, actual memory
use, Android/JVM behavior, supported ABIs, build reproducibility and independent
vectors. A portable pure-JVM dependency and a maintained native binding have
different performance and packaging costs. Record the selected version/license
and benchmark results in an ADR and dependency inventory. A physical low-memory
reference phone is needed to finish performance qualification.

## Envelope and secret ownership

1. Define a versioned bounded envelope containing a fixed magic, container and KDF
   profile versions, fresh random salt, algorithm identifiers and only the public
   metadata needed to decrypt. Authenticate this header as associated data.
2. Accept only release-approved exact KDF profiles. Check header size, memory/
   iteration/parallelism limits and ciphertext size **before** expensive work.
   Reject unknown profiles instead of allocating attacker-requested memory.
3. Obtain fresh cryptographic randomness for each archive. Let the selected AEAD
   library enforce nonce/segment rules. Never update ciphertext in place or reuse
   an archive key/nonce sequence for a new export attempt.
4. The encrypted logical container needs an authenticated terminal manifest with
   entry identity, type, schema, declared lengths and hashes. No untrusted path
   becomes a local filesystem path. Limit entries, expanded bytes, nesting and
   compression ratio before publication. Streaming segmentation must still
   detect missing final data and entry reorder/substitution.
5. Keep passphrases/recovery keys out of saved instance state, preferences, logs,
   filenames, intents and diagnostics. Use secure input, clear it after handoff,
   cancel and completion, and bound any in-memory pending-operation lifetime.
   Lost process state prompts for the secret again; it never triggers plaintext
   fallback. Do not offer to remember a secret by default.
6. Offer a strong portable recovery secret as an explicit alternative to a
   passphrase if the format supports it. Use the library's reviewed key wrapping
   mechanism; do not substitute plain SHA-256 for a password KDF. Keystore may
   protect an optional local convenience copy, but must not be the only recovery
   path. Confirm the user's recovery plan before first irreplaceable capture.
7. Explain secret loss and old-copy retention in the export/recovery workflow.
   Password confirmation and a local decrypt/semantic comparison catch typing
   and serialization mistakes. A clean-install restore drill remains required.

## Restore and deletion policy

Authenticate and validate into bounded staging before presenting the preview.
Restore publishes a complete validated batch transactionally; alarms remain
switched off until reviewed. Existing corrections and locally deleted identities
continue to win in additive mode. Never call a raw SQLite file copy a consistent
backup while its WAL can still contain committed rows.

The complete archive must include deletion identity markers and their provenance.
A restore should preview their effect explicitly; an older archive must not
silently delete newer local work, and restoring missing entries must not silently
resurrect a locally deleted identity. Specify snapshot age/origin, conflict and
full-replacement rules before implementing cross-installation deletion behavior.
Current journal v1 exports omit deletion markers; the app already explains that
older external copies can contain nights later deleted locally.

Raw-file staging is not a cross-file SQLite transaction. Reuse the chunk authority
contract: verified file publication first, rebuildable indexes, durable batch/
recovery markers, and no deletion of earlier valid data on an interrupted restore.
Add coordinated raw/derived/audio ownership and retention before those payloads
can be collected through a production workflow.

## Tests and release evidence

- Published KDF vectors and independent encryption/decryption fixtures, including
  non-ASCII passphrases with documented encoding and normalization policy.
- Cross-implementation decrypt of a complete versioned archive and clean-install
  semantic equality, including every correction, nullable field, source and unit.
- Wrong secret; modified header/salt/nonce/tag/ciphertext; incomplete final segment;
  entry reorder/removal/duplication; malformed lengths; unsupported/oversized KDF
  profiles; invalid UTF-8; path traversal and compression expansion.
- Cancellation/process interruption at derivation, entry boundaries, provider
  writes/close, authenticated staging and final publication. No incomplete restore
  becomes visible; all previously confirmed records remain intact.
- Full/quota-limited/revoked/offline document providers; attempt/completion/
  verification audits with no health values or secrets in their metadata.
- Android API 29/current API provider interoperability, release shrinking,
  minimum-phone latency/peak-memory/thermal measurements, and a real recovery drill.
- Review the complete envelope/protocol and secret lifecycle before advertising
  secure encrypted backups. Passing cipher round trips alone is insufficient.

## Primary research sources

Read for this checkpoint:

- Android cryptography and security-crypto deprecation:
  https://developer.android.com/privacy-and-security/cryptography
- RFC 9106 Argon2 profiles, parameter-selection process and test vectors:
  https://www.rfc-editor.org/rfc/rfc9106.html
- OWASP Password Storage Cheat Sheet (context: authentication password storage;
  archive KDF selection still needs its own threat model and mobile benchmarks):
  https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
- Android Keystore capabilities and key ownership:
  https://source.android.com/docs/security/features/keystore/features
