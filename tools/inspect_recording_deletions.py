#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Independent bounded SOLD v1 inspector. Identity-only plaintext; not author authentication."""
import hashlib
import hmac
import json
import struct
import sys
import uuid

MAX_MARKERS = 10_000
MAX_BYTES = 20 + MAX_MARKERS * 32 + 32

def inspect(stream):
    data = bytearray()
    while True:
        block = stream.read(min(8192, MAX_BYTES + 1 - len(data)))
        if not block:
            break
        data.extend(block)
        if len(data) > MAX_BYTES:
            raise ValueError("Deletion history exceeds byte bound")
    if len(data) < 52:
        raise ValueError("Truncated deletion history")
    body, checksum = bytes(data[:-32]), bytes(data[-32:])
    if not hmac.compare_digest(hashlib.sha256(body).digest(), checksum):
        raise ValueError("Checksum mismatch")
    magic, version, created_ms, count = struct.unpack_from(">4sIqI", body)
    if magic != b"SOLD" or version != 1 or count > MAX_MARKERS or len(body) != 20 + 32 * count:
        raise ValueError("Unsupported format or invalid count")
    pairs = [(str(uuid.UUID(bytes=body[i:i + 16])), str(uuid.UUID(bytes=body[i + 16:i + 32])))
             for i in range(20, len(body), 32)]
    if pairs != sorted(pairs) or len({p[0] for p in pairs}) != count or len({p[1] for p in pairs}) != count:
        raise ValueError("Noncanonical or duplicate identities")
    return dict(format="SOLD", version=version, createdAtEpochMillis=created_ms,
                markers=[dict(sessionId=s, requestId=r) for s, r in pairs],
                fingerprint=hashlib.sha256(data).hexdigest())

if __name__ == "__main__":
    with open(sys.argv[1], "rb") as source:
        print(json.dumps(inspect(source), indent=2))
