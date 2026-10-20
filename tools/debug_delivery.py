#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Package and validate exact development APK bytes before artifact/publication."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

FILES = {"solsleep-debug.apk", "build-info.json", "APK_SIGNER.txt", "SHA256SUMS"}
HASH = re.compile(r"[0-9a-f]{64}")
COMMIT = re.compile(r"[0-9a-f]{40}")
REPO = "BananaJeanss/solsleep"


def command(*args: str) -> str:
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def unique_object(pairs):
    obj = {}
    for key, value in pairs:
        if key in obj:
            raise ValueError("Duplicate metadata field")
        obj[key] = value
    return obj


def validate_info(info: dict, *, repository: str = REPO) -> None:
    expected = {"schema", "repository", "commit", "tree", "runId", "runNumber", "buildAttempt",
                "applicationId", "versionCode", "versionName", "minSdk", "targetSdk", "debuggable",
                "apkSha256", "certificateSha256", "signing"}
    if set(info) != expected or type(info["schema"]) is not int or info["schema"] != 1:
        raise ValueError("Unknown build metadata schema")
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,39}/[A-Za-z0-9_.-]{1,100}", repository) or info["repository"] != repository or not all(isinstance(info[k], str) and COMMIT.fullmatch(info[k]) for k in ("commit", "tree")):
        raise ValueError("Wrong repository/source identity")
    for key in ("runId", "runNumber", "buildAttempt", "versionCode", "minSdk", "targetSdk"):
        if type(info[key]) is not int or info[key] <= 0:
            raise ValueError("Invalid build counter")
    if info["runNumber"] > 20_999_899 or info["buildAttempt"] > 99 or info["versionCode"] != 10_000 + info["runNumber"] * 100 + info["buildAttempt"]:
        raise ValueError("Wrong Android version code")
    name = f"0.1.0-dev.{info['runNumber']}.{info['buildAttempt']}.{info['commit'][:12]}-debug"
    if info["versionName"] != name or info["applicationId"] != "dev.bananajeans.solsleep.debug":
        raise ValueError("Only the CI development application may be published")
    if info["minSdk"] != 29 or info["targetSdk"] != 37 or info["debuggable"] is not True:
        raise ValueError("Unexpected development APK configuration")
    if not all(isinstance(info[k], str) and HASH.fullmatch(info[k]) for k in ("apkSha256", "certificateSha256")):
        raise ValueError("Invalid APK/certificate identity")
    if info["signing"] not in ("runner-ephemeral", "private-ci"):
        raise ValueError("Unknown signing policy")


def validate_bundle(directory: Path, *, commit: str, run_id: int, run_number: int, attempt: int, repository: str = REPO) -> dict:
    if directory.is_symlink() or {p.name for p in directory.iterdir()} != FILES:
        raise ValueError("Unexpected delivery files")
    for name in FILES:
        p = directory / name
        limit = 100 * 1024 * 1024 if name.endswith(".apk") else 16384
        if p.is_symlink() or not p.is_file() or not 0 < p.stat().st_size <= limit:
            raise ValueError("Invalid delivery file")
    sums = directory.joinpath("SHA256SUMS").read_text()
    wanted = "".join(f"{digest(directory / name)}  {name}\n" for name in sorted(FILES - {"SHA256SUMS"}))
    if sums != wanted:
        raise ValueError("Delivery checksum mismatch")
    info = json.loads(directory.joinpath("build-info.json").read_text(), object_pairs_hook=unique_object)
    validate_info(info, repository=repository)
    if (info["commit"], info["runId"], info["runNumber"], info["buildAttempt"]) != (commit, run_id, run_number, attempt):
        raise ValueError("Artifact belongs to a different workflow source/run")
    if digest(directory / "solsleep-debug.apk") != info["apkSha256"]:
        raise ValueError("APK identity mismatch")
    certs = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$",
                       directory.joinpath("APK_SIGNER.txt").read_text(), re.MULTILINE)
    if certs != [info["certificateSha256"]]:
        raise ValueError("Certificate identity mismatch")
    return info


def package(apk: Path, directory: Path) -> dict:
    sha = os.environ["SOLSLEEP_BUILD_SHA"]
    if command("git", "rev-parse", "HEAD").strip() != sha:
        raise ValueError("Checkout does not match build source")
    command("git", "diff", "--exit-code", "HEAD", "--")
    sdk = Path(os.environ["ANDROID_HOME"]) / "build-tools" / "36.0.0"
    signer = command(str(sdk / "apksigner"), "verify", "--verbose", "--print-certs", str(apk))
    badging = command(str(sdk / "aapt"), "dump", "badging", str(apk))
    identity = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.MULTILINE)
    certs = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$", signer, re.MULTILINE)
    if identity is None or len(certs) != 1:
        raise ValueError("Unrecognized APK or multiple signers")
    info = dict(schema=1, repository=os.environ["GITHUB_REPOSITORY"], commit=sha,
                tree=command("git", "rev-parse", "HEAD^{tree}").strip(),
                runId=int(os.environ["GITHUB_RUN_ID"]), runNumber=int(os.environ["SOLSLEEP_BUILD_NUMBER"]),
                buildAttempt=int(os.environ["GITHUB_RUN_ATTEMPT"]), applicationId=identity[1],
                versionCode=int(identity[2]), versionName=identity[3],
                minSdk=int(re.search(r"^sdkVersion:'(\d+)'", badging, re.MULTILINE)[1]),
                targetSdk=int(re.search(r"^targetSdkVersion:'(\d+)'", badging, re.MULTILINE)[1]),
                debuggable="application-debuggable" in badging.splitlines(), apkSha256=digest(apk),
                certificateSha256=certs[0], signing="private-ci" if os.environ.get("SOLSLEEP_DEBUG_KEYSTORE") else "runner-ephemeral")
    validate_info(info, repository=os.environ["GITHUB_REPOSITORY"])
    directory.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(apk, directory / "solsleep-debug.apk")
    directory.joinpath("build-info.json").write_text(json.dumps(info, indent=2, sort_keys=True) + "\n")
    directory.joinpath("APK_SIGNER.txt").write_text(signer)
    directory.joinpath("SHA256SUMS").write_text("".join(
        f"{digest(directory / name)}  {name}\n" for name in sorted(FILES - {"SHA256SUMS"})))
    return validate_bundle(directory, commit=sha, run_id=info["runId"], run_number=info["runNumber"], attempt=info["buildAttempt"], repository=info["repository"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    info = package(args.apk, args.directory)
    print(json.dumps(info, sort_keys=True))  # Public APK identity only; no key material.
