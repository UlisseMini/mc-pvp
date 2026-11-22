"""Reconstruct terrain timelines from the same recorded 1.8.9 matches as the forecasts."""
import argparse
import base64
from bisect import bisect_left, bisect_right
from collections import Counter
from concurrent.futures import ProcessPoolExecutor
import gzip
import itertools
import json
import math
from pathlib import Path
import re
import struct

import numpy as np

from extract import DEFAULT, read_varint


HEIGHT = 256
CHUNK = 16
KIND = re.compile(rb'"kind":"(S(?:01|07|21|22|23|26)Packet[A-Za-z]+)"')
STAMP = re.compile(rb'"time_ms":(\d+)')
CHUNK_HEADER = struct.Struct('>ii?H')
BULK_HEADER = struct.Struct('>iiH')
SECTION_BYTES = 8192
LIT_SECTION_BYTES = 10240
SKY_SECTION_BYTES = 12288
# Non-full blocks must not hide the blocks behind them in surface extraction.
NON_OCCLUDING = [
    0, 6, 8, 9, 10, 11, 18, 20, 26, 27, 28, 30, 31, 32, 34, 37, 38, 39,
    40, 44, 50, 51, 53, 54, 55, 59, 60, 63, 64, 65, 66, 67, 68, 69, 70,
    71, 72, 75, 76, 77, 78, 79, 81, 83, 85, 90, 92, 93, 94, 95, 96, 101,
    102, 104, 105, 106, 107, 108, 109, 111, 113, 114, 115, 116, 117, 118,
    119, 120, 126, 127, 128, 130, 131, 132, 134, 135, 136, 138, 139, 140,
    141, 142, 143, 144, 145, 146, 147, 148, 149, 150, 151, 154, 156, 157,
    160, 161, 163, 164, 167, 171, 175, 176, 177, 178, 180, 182, 183, 184,
    185, 186, 187, 188, 189, 190, 191, 192, 193, 194, 195, 196, 197,
]
OCCLUDES = np.ones(4096, dtype=bool)
OCCLUDES[NON_OCCLUDING] = False


def unpack_sections(data, mask):
    sections = [index for index in range(16) if mask & (1 << index)]
    blocks = np.frombuffer(data, '<u2', count=len(sections) * 4096).reshape(-1, 16, 16, 16)
    return zip(sections, blocks)


def read_chunks(raw):
    """Return (chunk X, chunk Z, full, sections); skip cache unload notifications."""
    if raw[0] == 0x21:
        x, z, full, mask = CHUNK_HEADER.unpack_from(raw, 1)
        size, start = read_varint(raw, 1 + CHUNK_HEADER.size)
        if start + size != len(raw):
            raise ValueError('invalid chunk byte count')
        if full and not mask and size in (0, 256):
            return
        expected = mask.bit_count()
        if size not in (expected * LIT_SECTION_BYTES + int(full) * 256,
                        expected * SKY_SECTION_BYTES + int(full) * 256):
            raise ValueError(f'invalid section byte count ({size}, {expected} sections, full={full})')
        if mask or not full:
            yield x, z, full, unpack_sections(memoryview(raw)[start:], mask)
        return
    skylight = bool(raw[1])
    count, offset = read_varint(raw, 2)
    if count > 1024:
        raise ValueError('too many chunk columns in bulk packet')
    start = offset + count * BULK_HEADER.size
    for index in range(count):
        x, z, mask = BULK_HEADER.unpack_from(raw, offset + BULK_HEADER.size * index)
        size = mask.bit_count() * (SKY_SECTION_BYTES if skylight else LIT_SECTION_BYTES) + 256
        if start + size > len(raw):
            raise ValueError('incomplete bulk chunk')
        if mask:
            yield x, z, True, unpack_sections(memoryview(raw)[start:start + size], mask)
        start += size
    if start != len(raw):
        raise ValueError('unexpected bulk chunk trailer')


def signed26(value):
    return value - (1 << 26) if value >= (1 << 25) else value


def block_updates(raw):
    if raw[0] == 0x23:
        packed = struct.unpack_from('>Q', raw, 1)[0]
        x, y, z = signed26(packed >> 38), (packed >> 26) & 4095, signed26(packed & 67108863)
        state, end = read_varint(raw, 9)
        if end != len(raw):
            raise ValueError('unexpected block change trailer')
        yield x, y, z, state
        return
    cx, cz = struct.unpack_from('>ii', raw, 1)
    count, offset = read_varint(raw, 9)
    if count > 65536:
        raise ValueError('too many packed block changes')
    for _ in range(count):
        packed = struct.unpack_from('>H', raw, offset)[0]
        state, offset = read_varint(raw, offset + 2)
        yield cx * 16 + (packed >> 12), packed & 255, cz * 16 + ((packed >> 8) & 15), state
    if offset != len(raw):
        raise ValueError('unexpected multi-block change trailer')


class Column:
    def __init__(self):
        shape = (HEIGHT, CHUNK, CHUNK)
        self.state = np.zeros(shape, dtype=np.uint16)
        self.first = np.zeros(shape, dtype=np.uint16)
        self.known = np.zeros(16, dtype=bool)
        self.appear = np.full(16, -1, dtype=np.int32)
        self.changed = np.zeros(shape, dtype=bool)


class Arena:
    def __init__(self, region):
        self.region = region
        self.columns = {}
        self.events = []
        self.stats = Counter()

    def allowed(self, cx, cz):
        min_x, max_x, min_z, max_z = self.region
        return min_x <= cx <= max_x and min_z <= cz <= max_z

    def first_frame(self):
        for col in self.columns.values():
            col.first[:] = col.state
            col.appear[col.known] = 0

    def chunk(self, cx, cz, full, sections, t):
        if not self.allowed(cx, cz):
            return
        self.stats['chunk_columns'] += 1
        data = dict(sections)
        if (cx, cz) not in self.columns:
            if not any(np.any(section) for section in data.values()):
                return
            self.columns[cx, cz] = Column()
        col = self.columns[cx, cz]
        selected = range(16) if full else data
        empty = np.zeros((16, 16, 16), dtype=np.uint16)
        for section in selected:
            base_y = section * 16
            sl = slice(base_y, base_y + 16)
            blocks = data.get(section, empty)
            current = col.state[sl]
            if t >= 0:
                if col.known[section]:
                    different = blocks != current
                else:
                    # Explicit edits can arrive before the first complete section. Keep
                    # their history instead of constructing it from a later snapshot.
                    different = (blocks != current) & col.changed[sl]
                    np.copyto(col.first[sl], blocks, where=~col.changed[sl])
                    col.appear[section] = t
                if different.any():
                    col.changed[sl] |= different
                    for y, z, x in np.argwhere(different):
                        self.events.append((t, cx * 16 + int(x), base_y + int(y),
                                            cz * 16 + int(z), int(blocks[y, z, x])))
                    self.stats['chunk_diff_updates'] += int(different.sum())
            current[:] = blocks
            col.known[section] = True

    def edit(self, t, x, y, z, state):
        cx, bx = divmod(x, 16)
        cz, bz = divmod(z, 16)
        if not self.allowed(cx, cz) or not 0 <= y < HEIGHT or not 0 <= state <= 65535:
            return
        self.stats['point_updates'] += 1
        col = self.columns.get((cx, cz))
        if col is None:
            if not state:
                return
            self.columns[cx, cz] = col = Column()
        if col.state[y, bz, bx] != state:
            col.state[y, bz, bx] = state
            if t >= 0:
                self.events.append((t, x, y, z, state))
                col.changed[y, bz, bx] = True
            else:
                col.known[y // 16] = True

    def surface(self):
        """Keep exposed blocks and any block that may become exposed during playback."""
        arrays = []
        for (cx, cz), col in sorted(self.columns.items()):
            if not col.first.any():
                continue
            opaque = OCCLUDES[col.first >> 4] & ~col.changed
            avail = np.repeat(col.appear, 16)
            padded = np.zeros((HEIGHT + 2, CHUNK + 2, CHUNK + 2), dtype=bool)
            padded[1:-1, 1:-1, 1:-1] = opaque
            for dx, dz, into, edge in (
                (-1, 0, (slice(1, -1), slice(1, -1), 0), (slice(None), slice(None), -1)),
                (1, 0, (slice(1, -1), slice(1, -1), -1), (slice(None), slice(None), 0)),
                (0, -1, (slice(1, -1), 0, slice(1, -1)), (slice(None), -1, slice(None))),
                (0, 1, (slice(1, -1), -1, slice(1, -1)), (slice(None), 0, slice(None))),
            ):
                other = self.columns.get((cx + dx, cz + dz))
                if other is not None:
                    adjacent = OCCLUDES[other.first[edge] >> 4] & ~other.changed[edge]
                    known_at = np.repeat(other.appear, 16)
                    padded[into] = adjacent & (known_at <= avail)[:, None]
            covered = (padded[1:-1, 1:-1, :-2] & padded[1:-1, 1:-1, 2:]
                       & padded[1:-1, :-2, 1:-1] & padded[1:-1, 2:, 1:-1]
                       & padded[:-2, 1:-1, 1:-1] & padded[2:, 1:-1, 1:-1])
            later = np.r_[False, avail[1:] < avail[:-1]] | np.r_[avail[:-1] < avail[1:], False]
            y, z, x = np.nonzero((col.first != 0) & (~covered | later[:, None, None] | col.changed))
            arrays.append(np.column_stack((x + cx * 16, y, z + cz * 16,
                                           col.first[y, z, x], avail[y])))
        return np.concatenate(arrays, axis=0) if arrays else np.empty((0, 5), dtype=int)


def file_epoch(path):
    with gzip.open(path, 'rb') as stream:
        for line in itertools.islice(stream, 12):
            if match := STAMP.search(line[:360]):
                return int(match[1])
    return None


def events(files, low, high):
    for path in files:
        with gzip.open(path, 'rb') as stream:
            for line in stream:
                head = line[:380]
                if not head.startswith(b'{"type":"packet_wire"') or not KIND.search(head):
                    continue
                stamp = STAMP.search(head)
                if stamp is None or not low <= (ms := int(stamp[1])) <= high:
                    continue
                data = json.loads(line)['data']
                if data['packet']['direction'] == 'in' and data['base64']:
                    yield ms, base64.b64decode(data['base64'], validate=True)


def export_match(task):
    game, paths, epochs, out = task
    start = game['start_ms']
    samples = [int(t) for t in game['frames']]
    end = max(samples)
    baseline = start + min(samples) * 1000
    # Current pose paths bound the exported viewing area, with spare chunks
    # for adjoining islands and orbit shots beyond the recorded movements.
    points = [p[1:4] for row in game['frames'].values() for p in row[2].values()]
    spawn_y = [p[2] for t, row in game['frames'].items() if int(t) < min(samples) + 8
               for p in row[2].values()]
    xyz = np.asarray(points)
    lo = np.floor(xyz.min(axis=0) / 16).astype(int) - 3
    hi = np.floor(xyz.max(axis=0) / 16).astype(int) + 3
    low = start - 90000
    high = start + end * 1000
    begin = max(0, bisect_left(epochs, low) - 1)
    stop = bisect_right(epochs, high)
    region = lo[0], hi[0], lo[2], hi[2]
    arena = Arena(region)
    entered = False
    for ms, raw in events(paths[begin:stop], low, high):
        t = math.ceil((ms - start) / 1000)
        # The last pre-snapshot state includes removal of the waiting lobby
        # during the transition to the actual arena.
        if ms >= baseline and not entered:
            arena.first_frame()
            entered = True
        if not raw:
            continue
        if raw[0] in (0x01, 0x07):
            if ms < start:
                arena = Arena(region)
            continue
        try:
            if raw[0] in (0x21, 0x26):
                for cx, cz, full, sections in read_chunks(raw):
                    arena.chunk(cx, cz, full, sections, t if entered else -1)
            elif raw[0] in (0x22, 0x23):
                for x, y, z, state in block_updates(raw):
                    arena.edit(t if entered else -1, x, y, z, state)
        except (IndexError, ValueError, struct.error) as exc:
            raise ValueError(f'{game["id"]} terrain packet 0x{raw[0]:02x} at {t}s: {exc}') from exc
    if not entered:
        arena.first_frame()
    blocks = arena.surface()
    # Coalesce packets for the same voxel within one replay step. List order
    # is capture order for overlapping arrivals, as in the player recorder.
    final = {(t, x, y, z): state for t, x, y, z, state in arena.events}
    changes = sorted((*key, value) for key, value in final.items())
    if not len(blocks):
        raise ValueError(f'{game["id"]}: no terrain captured for requested arena')
    live = blocks[:, :3]
    if changes:
        live = np.concatenate((live, np.asarray(changes)[:, 1:4]))
    bounds = np.concatenate((live.min(axis=0), live.max(axis=0))).tolist()
    info = {**arena.stats, 'columns': len(arena.columns), 'surface_blocks': len(blocks),
            'changes': len(changes), 'last_second': end,
            'baseY': int(np.floor(np.median(spawn_y))) if spawn_y else int(np.floor(np.median(xyz[:, 1])))}
    content = {
        'version': 1, 'game': game['id'], 'bounds': bounds,
        'blocks': blocks.ravel().tolist(),
        'changes': [value for row in changes for value in row], 'stats': info,
    }
    target = out / f'terrain-{game["id"]}.json.gz'
    temp = target.with_suffix('.tmp')
    with temp.open('wb') as compressed, gzip.GzipFile(filename='', mode='wb', fileobj=compressed,
                                                     compresslevel=5, mtime=0) as stream:
        stream.write(json.dumps(content, separators=(',', ':')).encode('utf-8'))
    temp.replace(target)
    print(f'{game["id"]}: {len(blocks):,} visible blocks, {len(changes):,} timeline changes, '
          f'{len(arena.columns)} reconstructed columns, {target.stat().st_size:,} bytes', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('tracks', type=Path, help='Frozen extract.py position archive')
    parser.add_argument('--captures', type=Path, default=DEFAULT)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--games', nargs='+', default=[f'game-{n:02}' for n in range(7, 13)])
    parser.add_argument('--workers', type=int, default=4)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    with gzip.open(args.tracks, 'rt') as stream:
        manifest = json.load(stream)
    selected = [m for m in manifest['matches'] if m['id'] in args.games]
    if len(selected) != len(set(args.games)):
        raise ValueError('A requested game is not in the frozen archive')
    indexed = {}
    for game in selected:
        origin = game['capture']
        if origin not in indexed:
            names = next(group for group in manifest['source_files'] if group[0].split('-')[2] == origin)
            inputs = [args.captures / name for name in names]
            ordered = sorted((epoch, path) for path in inputs if (epoch := file_epoch(path)) is not None)
            indexed[origin] = ([path for _, path in ordered], [epoch for epoch, _ in ordered])
    tasks = [(game, *indexed[game['capture']], args.out) for game in selected]
    with ProcessPoolExecutor(max_workers=max(1, min(args.workers, len(tasks)))) as executor:
        list(executor.map(export_match, tasks))


if __name__ == '__main__':
    main()
