#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Stream a plaintext SolSleep .solmotion file and print metadata/verified counts.
No Android runtime or third-party dependency required; no raw files are written.
Output contains sensitive original timing and sensor metadata. SHA-256 is not
password protection or authentication against malicious rewriting.
"""
import argparse
import hashlib
import io
import json
import math
import struct
import uuid

MAX_METADATA = 2 * 1024 * 1024
MAX_CHUNKS = 32768
MAX_RAW = 64 * 1024 * 1024
REASONS = set('START RESUME PAUSE STOP PROCESS_LOSS SERVICE_STOP NO_SENSOR STORAGE LOW_SPACE MAX_DURATION SENSOR_STALE INVALID_SAMPLE CLOCK_CHANGE SENSOR_GAP ACCURACY DELETE SETUP'.split())


def require(condition, message='Invalid recording archive'):
    if not condition:
        raise ValueError(message)


class Reader:
    def __init__(self, source, digest=None):
        self.source, self.digest = source, digest

    def read(self, size):
        require(0 <= size <= MAX_METADATA)
        parts = bytearray()
        while len(parts) < size:
            block = self.source.read(size - len(parts))
            require(bool(block), 'Truncated recording')
            parts.extend(block)
        data = bytes(parts)
        if self.digest is not None:
            self.digest.update(data)
        return data

    def number(self, fmt):
        return struct.unpack('>' + fmt, self.read(struct.calcsize('>' + fmt)))[0]

    def uuid(self):
        return str(uuid.UUID(bytes=self.read(16)))

    def text(self):
        length = self.number('i')
        require(0 <= length <= 2048)
        return self.read(length).decode('utf-8', errors='strict')

    def flag(self):
        value = self.number('B')
        require(value in (0, 1))
        return bool(value)

    def wall(self):
        value = self.number('q')
        require(-2208988800000 <= value < 253402300800000)
        return value

    def nonnegative(self):
        value = self.number('q')
        require(value >= 0)
        return value


def inspect(source):
    digest = hashlib.sha256()
    reader = Reader(source, digest)
    require(reader.number('i') == 0x534f4c52 and reader.number('i') == 1, 'Unsupported SOLR version')
    size = reader.number('i')
    require(1 <= size <= MAX_METADATA)
    metadata = Reader(io.BytesIO(reader.read(size)))
    session = dict(id=metadata.uuid(), request=metadata.uuid(), owner=metadata.uuid(), state=metadata.text(),
                   started_ms=metadata.wall(), updated_ms=metadata.wall(), revision=metadata.number('q'))
    require(session['state'] in ('COMPLETED', 'FAILED') and session['revision'] >= 1)
    count = metadata.number('i')
    require(0 <= count <= 512)
    streams = {}
    for _ in range(count):
        item = dict(id=metadata.uuid(), boot=metadata.uuid(), wall_ms=metadata.wall(), elapsed_ns=metadata.nonnegative(),
                    name=metadata.text(), vendor=metadata.text(), version=metadata.number('i'),
                    resolution=metadata.number('f'), maximum_range=metadata.number('f'), wake_up=metadata.flag(),
                    committed=metadata.nonnegative(), rejected=metadata.nonnegative(), gaps=metadata.nonnegative())
        require(item['id'] not in streams)
        require(1 <= len(item['name'].encode('utf-16-le')) // 2 <= 512 and len(item['vendor'].encode('utf-16-le')) // 2 <= 512 and '\0' not in item['name'] + item['vendor'])
        require(math.isfinite(item['resolution']) and item['resolution'] >= 0 and
                math.isfinite(item['maximum_range']) and item['maximum_range'] > 0)
        streams[item['id']] = item
    count = metadata.number('i')
    require(1 <= count <= 10000)
    events, previous = [], 0
    for _ in range(count):
        event = dict(sequence=metadata.number('q'), reason=metadata.text(), wall_ms=metadata.wall(),
                     elapsed_ns=metadata.nonnegative(), boot=metadata.uuid())
        event['detail'] = metadata.number('q') if metadata.flag() else None
        require(event['sequence'] > previous and event['reason'] in REASONS)
        previous = event['sequence']
        events.append(event)
    require(metadata.source.read(1) == b'')
    sequences, times, samples = {}, {}, {}
    chunks = raw_bytes = 0
    while True:
        length = reader.number('i')
        if length == 0:
            break
        require(chunks < MAX_CHUNKS and 136 <= length <= 128 * 1024 and length <= MAX_RAW - raw_bytes)
        data = reader.read(length)
        require(hashlib.sha256(data[:-32]).digest() == data[-32:], 'Chunk checksum differs')
        chunk = Reader(io.BytesIO(data[:-32]))
        require(chunk.number('i') == 0x534f4c4d and chunk.number('i') == 1)
        require(chunk.uuid() == session['id'])
        stream, boot, sequence, wall, elapsed = chunk.uuid(), chunk.uuid(), chunk.nonnegative(), chunk.wall(), chunk.nonnegative()
        require(stream in streams)
        descriptor = streams[stream]
        require((boot, wall, elapsed) == (descriptor['boot'], descriptor['wall_ms'], descriptor['elapsed_ns']))
        require(sequence == sequences.get(stream, 0))
        count = chunk.number('i')
        require(1 <= count <= 4096 and len(data) == 116 + count * 20)
        last = times.get(stream, -1)
        for _ in range(count):
            timestamp = chunk.nonnegative()
            axes = [chunk.number('f') for _ in range(3)]
            require(timestamp >= elapsed and timestamp > last and all(math.isfinite(axis) for axis in axes))
            last = timestamp
        require(chunk.source.read(1) == b'')
        sequences[stream], times[stream] = sequence + 1, last
        samples[stream] = samples.get(stream, 0) + count
        chunks += 1
        raw_bytes += length
    require(all(samples.get(key, 0) >= item['committed'] for key, item in streams.items()))
    actual = digest.digest()
    reader.digest = None
    require(reader.read(32) == actual and source.read(1) == b'', 'Archive checksum or trailing data')
    return dict(format='SOLR', version=1, session=session, streams=list(streams.values()), events=events,
                chunks=chunks, samples=sum(samples.values()), raw_bytes=raw_bytes, fingerprint=actual.hex())


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('file')
    args = parser.parse_args()
    try:
        with open(args.file, 'rb') as source:
            print(json.dumps(inspect(source), ensure_ascii=False, indent=2))
    except (OSError, ValueError, struct.error) as error:
        parser.exit(1, f'Cannot validate recording: {error}\n')
