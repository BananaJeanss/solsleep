# SPDX-License-Identifier: GPL-3.0-or-later
import hashlib
import io
from pathlib import Path
import struct
import unittest
import uuid
from inspect_recording_deletions import inspect, MAX_BYTES

FIXTURE = Path(__file__).resolve().parents[1] / "core/src/test/resources/recording-deletions-v1.hex"

class RecordingDeletionFormatTest(unittest.TestCase):
    def fixture(self):
        return bytes.fromhex(FIXTURE.read_text().strip())

    def signed(self, body):
        return body + hashlib.sha256(body).digest()

    def test_independent_producer_matches_exact_kotlin_fixture(self):
        pairs = [("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-00000000000b"),
                 ("80000000-0000-0000-0000-000000000002", "ffffffff-ffff-ffff-ffff-fffffffffffe")]
        body = struct.pack(">4sIqI", b"SOLD", 1, 0, len(pairs))
        body += b"".join(uuid.UUID(s).bytes + uuid.UUID(r).bytes for s, r in pairs)
        self.assertEqual(self.fixture(), self.signed(body))
        result = inspect(io.BytesIO(self.fixture()))
        self.assertEqual(pairs, [(p["sessionId"], p["requestId"]) for p in result["markers"]])
        self.assertEqual(0, result["createdAtEpochMillis"])

    def test_every_truncation_and_changed_byte_is_rejected(self):
        data = self.fixture()
        for n in range(len(data)):
            with self.assertRaises(ValueError):
                inspect(io.BytesIO(data[:n]))
        for n in range(len(data)):
            changed = bytearray(data)
            changed[n] ^= 1
            with self.assertRaises(ValueError):
                inspect(io.BytesIO(changed))

    def test_resigned_schema_count_order_and_identity_violations(self):
        original = self.fixture()[:-32]
        variants = [b"FAIL" + original[4:], original[:4] + struct.pack(">I", 2) + original[8:],
                    original[:16] + struct.pack(">I", 10001) + original[20:],
                    original[:16] + struct.pack(">I", 1) + original[20:],
                    original[:20] + original[52:84] + original[20:52],
                    original[:52] + original[20:36] + original[68:],
                    original[:68] + original[36:52]]
        for body in variants:
            with self.assertRaises(ValueError):
                inspect(io.BytesIO(self.signed(body)))

    def test_empty_maximum_and_oversized_inputs(self):
        empty = self.signed(struct.pack(">4sIqI", b"SOLD", 1, -1, 0))
        self.assertEqual([], inspect(io.BytesIO(empty))["markers"])
        body = struct.pack(">4sIqI", b"SOLD", 1, 0, 10000)
        body += b"".join(uuid.UUID(int=i).bytes + uuid.UUID(int=10000+i).bytes for i in range(10000))
        data = self.signed(body)
        self.assertEqual(MAX_BYTES, len(data))
        self.assertEqual(10000, len(inspect(io.BytesIO(data))["markers"]))
        with self.assertRaises(ValueError):
            inspect(io.BytesIO(data+b"x"))
