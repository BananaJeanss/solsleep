# Development APK download status

Checked 20 October 2026. SolSleep remains a development preview. Keep an
independently trusted wake-up alarm. Do not uninstall or change signing
certificates without verified external journal and separate recording backups.

## Permanent download remains pending

The user reported 404 from the previously supplied Actions artifact URL. The
repository is public, and the connected artifact endpoint returned preview ZIP
bytes, but the original 404 cause is unconfirmed. Actions access can depend on
GitHub sign-in and artifact retention. A temporary recovered URL is not a durable
replacement.

There are **no public releases** at this checkpoint. The
[releases page](https://github.com/BananaJeanss/solsleep/releases) is the planned
destination, not a claim an APK is already there. PR #11 must first qualify, merge
and pass its own main-run build and both full native suites. Its publisher then
verifies the exact same-run artifact remotely before making the prerelease public.
Only after checking the actual published assets should the APK URL be supplied.

For Actions troubleshooting, sign into an account that can view the repository,
open [CI52](https://github.com/BananaJeanss/solsleep/actions/runs/39173044993)
and select `solsleep-debug-52-1`. If accessible, extract the ZIP and install
`solsleep-debug.apk`, not the ZIP itself. This is an access troubleshooting route,
not a verified permanent replacement or a newly qualified build.

## Original recovered preview identity

The previously independently inspected CI52 bundle is retained here to identify
a preview someone may already have installed. Both native jobs failed; successful
packaging alone does not qualify it.

| Field | Value |
| --- | --- |
| Artifact ID | `12153431857` |
| ZIP SHA-256 | `7d14c3cbb13485eb90ca910a3e912971fe69e8b8efb7d9c23c41ee3820ec6b1c` |
| APK SHA-256 | `b9731de5a5d3fb1ad77885af48c918ef376b12cab1d807ee66b98dfe9d67e61f` |
| Certificate SHA-256 | `89565e7cfdf3052964e8440f7fa6dbd014f7ae955bf0b272f031c4c8f04e8949` |
| Version / code | `0.1.0-dev.52.1.6cfbbfdc07b4-debug` / `15201` |
| Checkout | `6cfbbfdc07b449d0391f23a2e48b980298c9d1d0` |
| Tree | `97e500aff34c2c9f240b5bf3322858a050c4ddd1` |

Default runner development signing is ephemeral. Android can reject an update
from a different certificate. Uninstalling deletes private app data; do not use
it to resolve a mismatch before securing verified recovery copies.

## Current software verification

[CI62, APK delivery](https://github.com/BananaJeanss/solsleep/actions/runs/39463089168)
passes 203 core/26 Python checks, lint/builds and all 116 API29 cases with no
failure/error/skip. Its first API37 attempt failed SDK unpacking before tests;
that job alone has been retried and is running.

[CI61, receipts](https://github.com/BananaJeanss/solsleep/actions/runs/39463085604)
passes 213 core/26 Python checks, lint/builds and all 126 API37 cases. API29 has
126 cases and one earlier ChallengeUiTest control-readiness failure; every
recording/receipt case passed. The test helper now waits for the actual visible,
enabled scroll-and-click, with all existing state assertions retained. Full
[CI66](https://github.com/BananaJeanss/solsleep/actions/runs/39466387300) is pending.
Neither feature PR is merged or fully qualified yet.

Earlier CI52/53 XML retained cleanup failures. Read-only CI54/60 inspection checked
the actual XML, matching artifact digests and logcat; logcat also exposed setup
failures before recording bodies. The ViewModel's initial busy state preceded its
first STARTED lifecycle load. The corrected helper waits for the real initial
load before stopping the Activity and drains launched work without stale-root
idle. CI62 and CI61's recording passes now demonstrate that correction. Earlier
clock-only/superseded attempts are not counted as qualification.

| Retained native archive | Artifact ID | ZIP SHA-256 |
| --- | --- | --- |
| CI52 / API29 | 12153178379 | `2c822e4f2371a95f3e6262e8fb541901cd50003381429a45d7d98265847ffe65` |
| CI52 / API37 | 12153014272 | `74cd03bae6b57fb4ebdfdb786f5d6a58beb1922d7662ae6fc8aa70eb9ce758d3` |
| CI53 / API29 | 12154450592 | `1bb2bd99bab1f9c04db706fffd36cb978c2b5140b7059327e439a30e22261cb4` |
| CI53 / API37 | 12153543067 | `d3798115861de82dcbfd7f7664baf95ffbe7924f88b331a0be8991f585afa8dc` |
| CI61 / API29 | 12284332627 | `817268e7f7099779dcb11fea4a7683e7bb72be0403c3d2d0523d4e05e714b66d` |
| CI61 / API37 | 12285600790 | `fb36c99eca4610aab72e0358c640f9736d89cdd8730fde3c2ec3dca510449a64` |

Local command execution is unavailable; hosted CI is the working route for
compilation and read-only artifact inspection. Both original CI61 receipt PNGs
were visually reviewed and retained in [SCREENSHOTS](SCREENSHOTS.md). Temporary
inspection PR #13 was closed without merge. No release gate was bypassed.

## Public download acceptance

After qualification and merge, verify the actual main-run prerelease and its
public asset URLs, source/run identity, APK/certificate hashes and unchanged
retry behavior. Tags or uploaded asset bytes must never be replaced. PR #12
requires its own qualification and main publication after retargeting.
