#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Independent Python struct fixtures for SOLR v1/v2, not real sensor data."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import uuid


def fixture(version=1):
    assert version in (1, 2)
    long = lambda value: struct.pack('>q', value)
    integer = lambda value: struct.pack('>i', value)
    identity = lambda value: uuid.UUID(int=value).bytes
    text = lambda value: integer(len(value.encode('utf-8'))) + value.encode('utf-8')
    wall = 1792278000000
    metadata = identity(1) + identity(2) + identity(3) + text('COMPLETED')
    metadata += long(wall) + long(wall + 1000) + long(2)
    metadata += integer(1) + identity(4) + identity(5) + long(wall) + long(100)
    metadata += text('Independent 🌙') + text('Python struct fixture') + integer(7)
    metadata += struct.pack('>ff?', 0.01, 40, False) + long(1) + long(0) + long(0)
    if version == 2:
        provenance = dict(version=1, baseApkSha256='0123456789abcdef' * 4,
                          zoneId='Europe/Tallinn', requestedPeriodMicros=100000)
        metadata += b'\x01' + text(json.dumps(provenance, separators=(',', ':')))
    metadata += integer(1) + long(42) + text('STOP') + long(wall + 1000) + long(101)
    metadata += identity(5) + b'\x01' + long(-1)
    chunk = integer(0x534f4c4d) + integer(1) + identity(1) + identity(4) + identity(5)
    chunk += long(0) + long(wall) + long(100) + integer(1)
    chunk += long(101) + struct.pack('>fff', -0.0, 1.25, 9.81)
    chunk += hashlib.sha256(chunk).digest()
    archive = integer(0x534f4c52) + integer(version) + integer(len(metadata)) + metadata
    archive += integer(len(chunk)) + chunk + integer(0)
    return archive + hashlib.sha256(archive).digest()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    for version in (1, 2):
        path = Path(__file__).resolve().parents[1] / f'core/src/test/resources/motion-archive-v{version}.solmotion'
        data = fixture(version)
        if args.check:
            if path.read_bytes() != data:
                raise SystemExit(f'Independent SOLR v{version} fixture differs')
        else:
            path.write_bytes(data)
        print(f'Independent SOLR v{version} fixture:', hashlib.sha256(data).hexdigest())
