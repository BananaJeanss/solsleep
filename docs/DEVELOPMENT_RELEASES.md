# Development APKs and GitHub prereleases

Implementation under qualification, 19 October 2026. Final executed runs belong
in STATUS.md. These workflows distribute **debug builds**, not production releases.

## Download and verify

- [GitHub Releases](https://github.com/BananaJeanss/solsleep/releases) contains a
  versioned development prerelease after each successful qualifying main run.
  Download `solsleep-debug.apk`, with `SHA256SUMS`, `build-info.json` and
  `APK_SIGNER.txt`. Public release assets do not require an Actions login.
- [Android checks](https://github.com/BananaJeanss/solsleep/actions/workflows/android.yml)
  produces `solsleep-debug-<run number>-<attempt>` as a separate 30-day artifact
  on PR, main push and manual runs. GitHub requires authentication for Actions
  artifact downloads. PR bundles are previews; their existence does not imply
  the two device jobs have passed. Full reports remain separate artifacts.
- Verify with `sha256sum -c SHA256SUMS` in the downloaded directory. Compare
  source/run/certificate with release notes. The manifest records actual APK
  identity and signature verification, not just intended Gradle settings.

Only Android API 29+ is supported. The application ID is
`dev.bananajeans.solsleep.debug`; production uses a separate ID. The APK is
debuggable and experimental. Physical overnight wake-up, OEM, sensor usefulness,
energy and assistive-use qualification remain open. Sonar, sleep-stage inference
and watch acquisition are not supplied by the distribution workflow.

## Signing and personal records

By default, Android generates a new private debug key on the ephemeral build
runner. Those certificates can differ between runs. A matching package name is
not sufficient for an in-place update; the signing identity must also match.
The actual certificate SHA-256 and `runner-ephemeral` status accompany every
download and are prominent in the release notes. Checksums alone do not establish
the author's identity or guarantee a future update path.

Keep personal records on a stable signer. Finish any active recording and save
verified external alarm, journal and individual recording recovery copies before
changing signers/builds. These are separate export workflows; no combined backup
is implemented. An uninstall removes private records. Instrumentation installs
are disposable and must not hold irreplaceable data.

For a persistent **private CI development certificate**, a repository maintainer
can configure the Actions secret `CI_DEBUG_KEYSTORE_BASE64`:

```sh
keytool -genkeypair -keystore solsleep-ci-debug.keystore -storetype PKCS12 \
  -storepass android -keypass android -alias androiddebugkey \
  -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=SolSleep CI Development"
base64 -w0 solsleep-ci-debug.keystore | gh secret set CI_DEBUG_KEYSTORE_BASE64 \
  --repo BananaJeanss/solsleep
```

Retain a secure offline backup of the keystore. The standard debug passwords are
not the key's protection boundary; the complete keystore is a private repository
secret. No key is committed, logged or included in an APK download. It is supplied
only to this repository's trusted main push/manual build, never PRs, forks or
manually selected feature branches; decoded file creation is bounded, exclusive
and mode 0600, and cleanup runs even after build failure. Invalid configured
material fails the build rather than silently switching certificates. Metadata
labels actual configured signing `private-ci`. Installing the first such build
over an earlier runner certificate still needs the signer transition described
above. Do not rotate a working key casually.

This setup intentionally leaves production `release` unsigned. A stable production
signing identity, secure recovery, device gates, dependency notices/SBOM and
release-candidate qualification remain separate work. No Play distribution or
production-signing approval is claimed.

## Publication and retry contract

One `Android checks` workflow owns the counter and tests. Do not rename/recreate
its file without preserving version monotonicity. CI code is
`10000 + run_number * 100 + run_attempt`; attempts 1–99 and run numbers through
20,999,899 are supported, with failure before exceeding Android's limit. The
visible version includes run, attempt and the exact 12-character source prefix.
All jobs use the same full checkout SHA and version inputs. A later run has a
higher code; rerunning an old run does not make it newer than intervening runs.
Ordinary local builds retain version 1 and their normal local debug certificate.

Only a successful main push/manual build **and both full API 29/37 device jobs**
can reach `publish-debug`. This job alone has `contents: write`, plus artifact
read access. PRs have no release write path; there is no `pull_request_target` or
privileged `workflow_run` handoff. Download uses the exact same-run build artifact
ID. No downloaded artifact is executed and no APK is rebuilt in the publisher.
Source/run/build-attempt fields, exact allowed filenames, sizes, symlinks,
duplicate JSON fields, debug application configuration, hashes and certificate
report are checked before publication. This is not a cryptographic attestation.

Tags are `debug-<run>.<build attempt>-<source prefix>`, fixed to the complete
qualified commit. No moving tag, asset clobber, release deletion or stable Latest
promotion is used. A draft is created first; exact assets are uploaded, downloaded
and compared before publication as a prerelease. A failed upload/readback leaves
a recoverable draft. A retry may add missing draft assets only when all existing
assets match exactly. A changed asset/tag or incomplete public release is retained
and rejected, never overwritten. An identical public release is a read-only
success. Successful build outputs preserve the original artifact ID/attempt for
publication retries. If build output is unavailable/expired, rerun all jobs for
a new qualified bundle; never substitute an unverified APK.

## Primary documentation reviewed

- https://developer.android.com/studio/publish/app-signing
- https://developer.android.com/studio/publish/versioning
- https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax
- https://docs.github.com/en/actions/reference/security/secure-use
- https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows
- https://github.com/actions/download-artifact/tree/v7.0.0
- https://cli.github.com/manual/gh_release_create
