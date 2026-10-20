# Development APK downloads

20 October 2026. [Download the current debug APK](https://github.com/BananaJeanss/solsleep/releases/download/debug-71.1-b7afd0c9db9b/solsleep-debug.apk) or read
[its release notes and checksums](https://github.com/BananaJeanss/solsleep/releases/tag/debug-71.1-b7afd0c9db9b).
Version `0.1.0-dev.71.1.b7afd0c9db9b-debug` (code 17101), Android 10/API29+.

This public release URL replaces the Actions artifact route that returned 404.
All four release assets were fetched over HTTPS with HTTP200, without GitHub
authentication, and verified against the trusted successful main build in
[CI72 public readback](https://github.com/BananaJeanss/solsleep/actions/runs/39474809678). The original Actions 404 cause is unconfirmed;
it is not necessary to sign in to download these public release assets.

## Verify the download

| Field | Value |
| --- | --- |
| Source | `b7afd0c9db9b6c5150c2e879de0946469c87a8cf` |
| Source tree | `d3c74e911ca0f05b6b0998ff02764844e34d6eba` |
| Main checks | [CI71](https://github.com/BananaJeanss/solsleep/actions/runs/39471810590) |
| APK SHA-256 | `2e94f41f39258efeb491c487b1873344d7017032d658471fd0316ffc0d99bbb7` |
| Certificate SHA-256 | `cccd603ec9d9a76750bb6bc0a5b56148f0c5babde6e4f678802b5548fdfafc5b` |
| Package | `dev.bananajeans.solsleep.debug` |
| Build version / code | `0.1.0-dev.71.1.b7afd0c9db9b-debug` / `17101` |

The APK is accompanied by `SHA256SUMS`, `build-info.json` and `APK_SIGNER.txt`.
Download all four into one directory and run `sha256sum -c SHA256SUMS`.
APK signature/package/API/version data is checked using Android build tools;
the public byte verifier independently matches the APK to that trusted result.
Checksums detect changed bytes; use this repository's release page as the trusted
source. No private signing key is included.

## Installing and updating a development build

This is a debuggable preview, not a production release or validated sleep tracker.
Keep a separately trusted wake-up alarm. Phone motion saves raw observations;
sonar, sleep inference/stages and live watch acquisition remain unimplemented.

Default runner development signing is ephemeral. An existing installation with a
different certificate cannot update in place. **Uninstalling deletes private
app data.** Export and verify alarm definitions, journal revisions and each
finished recording before replacing a build/signer. Local export receipts are
not currently portable; they are also removed by uninstall. Use a stable private
development signing identity before relying on an installation for personal
records; setup and production-signing separation are described in
[DEVELOPMENT_RELEASES](DEVELOPMENT_RELEASES.md).

Journal JSON does not include raw recordings. Separate `.solmotion` exports
preserve raw recording bytes/provenance and provide staged validated restore.
Past verification receipts do not prove current cloud retention or availability.
Combined/encrypted backups and portable receipt/deletion propagation remain
planned work. See [the tonight checklist](TONIGHT_TEST.md).

## What passed

Current main CI71 passed all 213 core tests, 26 Python checks, lint, debug/test
APKs, shrunk unsigned release and all 126 native tests on each API29/37, with
zero failures/errors/skips. The original 200% receipt UI PNGs were visually
reviewed; source and full artifact evidence are in [TESTING](TESTING.md) and
[SCREENSHOTS](SCREENSHOTS.md). Physical overnight/accuracy/OEM and accessibility
qualification remains open.

PR #11's first public release, `debug-68.1-3be27815bb5c`, passed the same delivery
protocol and CI70 anonymous downloads. A live publisher-only retry retained its
original build attempt, tag/release ID and all asset IDs/hashes. PR #12's own
main checks and the current public readback qualify the latest software slice.
Temporary diagnostic PR #13 was closed without merge; no test experiment entered
the app and no release gate was bypassed.

## Actions artifacts

[Android checks](https://github.com/BananaJeanss/solsleep/actions/workflows/android.yml) also
retains separate `solsleep-debug-<run>-<attempt>` artifacts for 30 days. Actions
downloads require GitHub authentication and may be unavailable after retention.
A PR artifact is a preview, not proof its device suites passed. Prefer the actual
public prereleases for a normal download; their asset URLs are independent of
Actions artifact retention. A ZIP artifact must be extracted before installing
its `solsleep-debug.apk`.
