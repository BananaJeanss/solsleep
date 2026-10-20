#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Optional private development key. Never prints or uploads the key material."""
import base64
import os
from pathlib import Path


def prepare(encoded: str, directory: Path) -> Path | None:
    if not encoded:
        return None
    if len(encoded) > 131072:
        raise ValueError("CI development keystore exceeds limit")
    data = base64.b64decode(encoded, validate=True)
    if not 256 <= len(data) <= 65536:
        raise ValueError("Invalid CI development keystore size")
    target = directory / "solsleep-ci-debug.keystore"
    # Exclusive creation, no symlink traversal or permissive intermediate file.
    with open(target, "xb", opener=lambda path, flags: os.open(path, flags, 0o600)) as stream:
        stream.write(data)
    return target


if __name__ == "__main__":
    path = prepare(os.environ.get("CI_DEBUG_KEYSTORE_BASE64", ""), Path(os.environ["RUNNER_TEMP"]))
    if path is not None:
        with Path(os.environ["GITHUB_ENV"]).open("a") as stream:
            stream.write(f"SOLSLEEP_DEBUG_KEYSTORE={path}\n")
    print("Private CI development signing enabled" if path else "Runner development signing; update identity is not persistent")
