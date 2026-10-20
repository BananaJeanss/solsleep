# Development APK download status

Checked 20 October 2026. SolSleep is a development preview; keep an independently
trusted wake-up alarm. Do not uninstall or switch signing certificates without
verified external journal and separate recording backups.

## Reported 404

The user could not open the previously supplied GitHub Actions artifact link.
The connected GitHub artifact endpoint still returned the ZIP, so the original
404 cause is not established. Actions artifact access can depend on the signed-in
GitHub account and artifact retention. A recovered temporary download is useful
for immediate testing, but it is not the permanent release delivery goal.

There are **no public releases** at this checkpoint. The
[repository releases page](https://github.com/BananaJeanss/solsleep/releases)
is a destination for future qualified downloads, not a claim an APK is there.

For Actions access, sign into GitHub with an account that can view the repository,
open [CI52](https://github.com/BananaJeanss/solsleep/actions/runs/39173044993),
and select the artifact named `solsleep-debug-52-1`. If accessible, extract the ZIP
and install `solsleep-debug.apk`; the ZIP itself is not installable. This is an
access troubleshooting route, not a verified replacement for the reported link.

Recovered preview identity, previously independently inspected:

- Artifact ID: `12153431857`; ZIP SHA-256:
  `7d14c3cbb13485eb90ca910a3e912971fe69e8b8efb7d9c23c41ee3820ec6b1c`.
- APK SHA-256:
  `b9731de5a5d3fb1ad77885af48c918ef376b12cab1d807ee66b98dfe9d67e61f`.
- Signing certificate SHA-256:
  `89565e7cfdf3052964e8440f7fa6dbd014f7ae955bf0b272f031c4c8f04e8949`.
- Version: `0.1.0-dev.52.1.6cfbbfdc07b4-debug`, code `15201`.
- CI checkout: `6cfbbfdc07b449d0391f23a2e48b980298c9d1d0`;
  tree `97e500aff34c2c9f240b5bf3322858a050c4ddd1`.

The preview is not newly qualified: both native jobs failed. Default CI debug
signing is ephemeral; Android can reject an update from another build's signer.
Do not solve that by uninstalling an install with unbacked data.

## Required native investigation

| Run | Successful job | API 29 | API 37 |
| --- | --- | --- | --- |
| [CI52, APK delivery](https://github.com/BananaJeanss/solsleep/actions/runs/39173044993) | Build | Failed, job 117809928468 | Failed, job 117809928588 |
| [CI53, recording receipts](https://github.com/BananaJeanss/solsleep/actions/runs/39173248195) | Build | Failed, job 117810596933 | Failed, job 117810596907 |

Both runs completed on 18 October UTC. Status and complete text job logs were
retrieved on 20 October. Each native log reports failing tests and points to its
HTML report. The command logs contain no failed-case assertions, so failure
details must be read from the retained JUnit/HTML/logcat artifacts. Do not infer
a new application defect, an emulator-only fault, or a successful fix without
that evidence. CI52 is not a pass just because its APK packaging succeeded.

Known native artifact digests from the job upload logs:

| Run / API | Artifact ID | ZIP SHA-256 |
| --- | --- | --- |
| CI52 / 29 | 12153178379 | `2c822e4f2371a95f3e6262e8fb541901cd50003381429a45d7d98265847ffe65` |
| CI52 / 37 | 12153014272 | `74cd03bae6b57fb4ebdfdb786f5d6a58beb1922d7662ae6fc8aa70eb9ce758d3` |
| CI53 / 29 | 12154450592 | `1bb2bd99bab1f9c04db706fffd36cb978c2b5140b7059327e439a30e22261cb4` |

The read-only diagnostic job in CI54 subsequently downloaded these archives;
the action's computed digests match the identities above. It parsed four actual
JUnit files: CI52 contains 116 cases/nine cleanup failures per API; CI53 contains
126 cases/eleven cleanup failures per API. Every reported failure points to the
same stopped-Activity busy wait in MotionCaptureTest.cleanup.

Both branches now advance the Compose test clock while awaiting that same
bounded state predicate. Full CI57/58 are active, with all core/Python checks,
lint and build variants passing. Native qualification remains pending; CI58
API 37 failed unpacking the upstream emulator image before tests and needs a
scoped retry after its remaining jobs finish. The new report gate correctly
rejected its missing results. Neither feature PR is merged.

Local command execution and persistent local APK attachment are still unavailable.
Read-only hosted inspection provides a practical route for reports, but failed
CI53 artifacts lacked the receipt screenshot; review fresh captures before
qualifying its UI. PR #13 is temporary inspection only and must not merge.

## Permanent download acceptance

PR #11's same-run publisher must remain gated on build and both complete native
suites. After qualification and merge, verify a real main-run release by anonymous
asset download and exact package/certificate/hash/source checks. Retrying must
retain the same tag and asset bytes; mismatches must fail without replacement.
Then replace preview guidance with the actual public asset URL. PR #12 requires
its own qualification and publication after retargeting to main.
