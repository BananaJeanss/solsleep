# SPDX-License-Identifier: GPL-3.0-or-later
"""Independent inspector regressions; fixture values are synthetic, never patient data."""
import hashlib
import io
import struct
import unittest
from generate_motion_fixture import fixture
from inspect_motion_archive import inspect


class RecordingInspectorTest(unittest.TestCase):
    def test_independent_fixture(self):
        value = inspect(io.BytesIO(fixture()))
        self.assertEqual(1, value['chunks'])
        self.assertEqual(1, value['samples'])
        self.assertEqual('Independent 🌙', value['streams'][0]['name'])
        self.assertEqual(-1, value['events'][0]['detail'])

    def test_each_truncation_and_bit_fault_rejects(self):
        original = fixture()
        for index in range(len(original)):
            with self.subTest(index=index):
                with self.assertRaises(ValueError):
                    inspect(io.BytesIO(original[:index]))
                modified = bytearray(original)
                modified[index] ^= 1
                with self.assertRaises((ValueError, UnicodeDecodeError)):
                    inspect(io.BytesIO(modified))

    def test_trailing_data_and_oversized_metadata(self):
        with self.assertRaises(ValueError):
            inspect(io.BytesIO(fixture() + b'x'))
        with self.assertRaises(ValueError):
            inspect(io.BytesIO(struct.pack('>iii', 0x534f4c52, 1, 2**31 - 1)))

    def test_recomputed_hash_does_not_make_malformed_utf8_valid(self):
        data = bytearray(fixture())
        data[data.index('🌙'.encode('utf-8'))] = 0xff
        data[-32:] = hashlib.sha256(data[:-32]).digest()
        with self.assertRaises(UnicodeDecodeError):
            inspect(io.BytesIO(data))


if __name__ == '__main__':
    unittest.main()
