# SPDX-License-Identifier: GPL-3.0-or-later
"""Independent inspector regressions; fixture values are synthetic, never patient data."""
import hashlib
import io
import struct
import unittest
from generate_motion_fixture import fixture
from inspect_motion_archive import inspect, provenance


class RecordingInspectorTest(unittest.TestCase):
    def test_independent_fixture(self):
        value = inspect(io.BytesIO(fixture()))
        self.assertEqual(1, value['chunks'])
        self.assertEqual(1, value['samples'])
        self.assertEqual('Independent 🌙', value['streams'][0]['name'])
        self.assertEqual(-1, value['events'][0]['detail'])
        self.assertIsNone(value['streams'][0]['provenance'])

    def test_version_two_independent_provenance(self):
        value = inspect(io.BytesIO(fixture(2)))
        self.assertEqual(2, value['version'])
        self.assertEqual('0123456789abcdef' * 4, value['streams'][0]['provenance']['baseApkSha256'])
        self.assertEqual('Europe/Tallinn', value['streams'][0]['provenance']['zoneId'])
        self.assertEqual(100000, value['streams'][0]['provenance']['requestedPeriodMicros'])
        self.assertEqual(1, value['samples'])

    def test_version_two_truncation_and_bit_faults(self):
        original = fixture(2)
        for index in range(len(original)):
            with self.subTest(index=index):
                with self.assertRaises(ValueError):
                    inspect(io.BytesIO(original[:index]))
                modified = bytearray(original)
                modified[index] ^= 1
                with self.assertRaises((ValueError, UnicodeDecodeError)):
                    inspect(io.BytesIO(modified))

    def test_future_or_duplicate_provenance_and_invalid_fields_are_refused(self):
        import json
        value = dict(version=1, baseApkSha256='a' * 64, zoneId='Europe/Tallinn', requestedPeriodMicros=100000)
        for key, bad in [('version', 2), ('version', True), ('zoneId', 'Missing/Zone'),
                         ('baseApkSha256', 'b' * 63), ('requestedPeriodMicros', 0), ('requestedPeriodMicros', True)]:
            with self.subTest(key=key, bad=bad), self.assertRaises(ValueError):
                provenance(json.dumps(dict(value, **{key: bad})))
        with self.assertRaises(ValueError):
            provenance(json.dumps(value)[:-1] + ',"version":1}')

    def test_version_two_recomputed_hash_does_not_accept_future_metadata(self):
        original = fixture(2)
        data = original.replace(b'"version":1', b'"version":2')
        data = data[:-32] + hashlib.sha256(data[:-32]).digest()
        with self.assertRaises(ValueError):
            inspect(io.BytesIO(data))

    def test_original_zone_offsets_are_validated_without_normalizing_away_the_identifier(self):
        import json
        value = dict(version=1, baseApkSha256='a' * 64, zoneId='UTC', requestedPeriodMicros=100000)
        for zone in ('Z', 'UTC', 'GMT+03:00', '-02:30', '+18:00', 'Asia/Kathmandu'):
            self.assertEqual(zone, provenance(json.dumps(dict(value, zoneId=zone)))['zoneId'])
        for zone in ('+18:01', '-01:00:99', 'GMT+03::00', '+03:0', 'Imaginary/Place', 'UTC\n'):
            with self.subTest(zone=zone), self.assertRaises(ValueError):
                provenance(json.dumps(dict(value, zoneId=zone)))

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
