#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Publish one qualified main-run bundle, with draft recovery and no asset replacement."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
from debug_delivery import FILES, REPO, command, validate_bundle


def notes(info: dict) -> str:
    signing = ("Private CI development certificate. Compare the certificate SHA-256 before updating."
               if info["signing"] == "private-ci" else
               "**Runner-generated development certificate: signing identity can change between builds. "
               "An existing installation with another certificate cannot update in place. Export and verify "
               "all irreplaceable records before changing builds/signers; uninstalling deletes private data.**")
    return f"""Development APK, Android 10/API 29 or newer. Download **solsleep-debug.apk** below.

This is a debuggable, experimental build, not a production release or a validated sleep tracker.
Keep a separately trusted wake-up alarm. Physical overnight, acoustic/haptic, OEM and accessibility
qualification remains open. Sonar, sleep-stage inference and watch acquisition are not implemented.

{signing}

- Source: https://github.com/{REPO}/commit/{info['commit']}
- Successful build, lint, core/archive tests and full API 29/37 suites: https://github.com/{REPO}/actions/runs/{info['runId']}
- Application: `{info['applicationId']}`; version `{info['versionName']}` (code {info['versionCode']})
- APK SHA-256: `{info['apkSha256']}`
- Certificate SHA-256: `{info['certificateSha256']}`

`SHA256SUMS`, `build-info.json` and `APK_SIGNER.txt` accompany the exact APK.
Checksums detect changed bytes; use this repository's release page as the trusted download source.
Production signing and release qualification are separate work. No private key is included.
"""


def publish(directory: Path, *, commit: str, run_id: int, run_number: int, attempt: int, run=command) -> str:
    info = validate_bundle(directory, commit=commit, run_id=run_id, run_number=run_number, attempt=attempt)
    tag = f"debug-{run_number}.{attempt}-{commit[:12]}"
    endpoint = f"repos/{REPO}/releases/tags/{tag}"
    try:
        release = json.loads(run("gh", "api", endpoint))
    except subprocess.CalledProcessError as error:
        if "HTTP 404" not in str(error.output):
            raise
        with tempfile.TemporaryDirectory() as tmp:
            body = Path(tmp) / "notes.md"
            body.write_text(notes(info))
            run("gh", "release", "create", tag, "--repo", REPO, "--target", commit,
                "--draft", "--prerelease", "--latest=false", "--title", f"Development build {run_number}.{attempt}",
                "--notes-file", str(body))
        release = json.loads(run("gh", "api", endpoint))

    ref = json.loads(run("gh", "api", f"repos/{REPO}/git/ref/tags/{tag}"))
    if ref["object"].get("type") != "commit" or ref["object"].get("sha") != commit:
        raise ValueError("Release tag does not identify the qualified commit")
    if release["tag_name"] != tag or release["prerelease"] is not True:
        raise ValueError("Unexpected release identity/type")
    names = [asset["name"] for asset in release["assets"]]
    if len(set(names)) != len(names) or not set(names) <= FILES:
        raise ValueError("Unexpected assets retained; refusing publication")
    if release["draft"] is not True and set(names) != FILES:
        raise ValueError("Published release is incomplete; refusing mutation")

    def downloaded() -> None:
        with tempfile.TemporaryDirectory() as tmp:
            run("gh", "release", "download", tag, "--repo", REPO, "--dir", tmp)
            target = Path(tmp)
            for name in names:
                path = target / name
                if path.is_symlink() or not path.is_file() or path.read_bytes() != directory.joinpath(name).read_bytes():
                    raise ValueError("Existing release asset differs; refusing replacement")
            if set(names) == FILES:
                validate_bundle(target, commit=commit, run_id=run_id, run_number=run_number, attempt=attempt)

    if names:
        downloaded()
    if release["draft"] is True:
        missing = sorted(FILES - set(names))
        if missing:
            run("gh", "release", "upload", tag, "--repo", REPO, *(str(directory / name) for name in missing))
        names = sorted(FILES)
        downloaded()  # A draft only becomes public after exact remote readback.
        run("gh", "release", "edit", tag, "--repo", REPO, "--draft=false", "--prerelease", "--latest=false")
    return f"https://github.com/{REPO}/releases/tag/{tag}"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    # Independent of artifact-supplied fields. These come from the trusted job's
    # event and the successful build job's outputs, including its original attempt.
    url = publish(args.directory, commit=os.environ["SOLSLEEP_BUILD_SHA"],
                  run_id=int(os.environ["GITHUB_RUN_ID"]), run_number=int(os.environ["SOLSLEEP_BUILD_NUMBER"]),
                  attempt=int(os.environ["SOLSLEEP_BUILD_ATTEMPT"]))
    print(url)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as stream:
            stream.write(f"### Development APK\n\n[Download APK and checksums]({url})\n\n"
                         "Experimental debug build; read the release signing and data-recovery notes before installing.\n")
