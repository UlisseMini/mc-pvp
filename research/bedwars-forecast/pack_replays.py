"""Make display-only replay fields compact. Benchmark and runtime probabilities stay full precision."""
import argparse
import gzip
from pathlib import Path
import shutil
import struct

import numpy as np


def pack(source, target):
    raw = source.read_bytes()
    buffer = gzip.decompress(raw)
    magic, n, models, tiles = struct.unpack_from('<4sIHH', buffer)
    if magic != b'BW11' or tiles != 256 or len(buffer) != 12 + n * (4 + models * tiles):
        raise ValueError(f'Invalid or partial forecast archive {source.name}')
    unpacked = np.frombuffer(buffer, np.uint8, offset=12).reshape(n, 4 + models*tiles)
    order = np.lexsort((unpacked[:, 0], unpacked[:, 1], unpacked[:, 2]))
    raw_fields = unpacked[order, 4:]
    # 4-bit relative log mass. Each increment is 0.55 nats, with a transparent tail.
    q = np.rint((raw_fields.astype(np.float32)-90)/11).clip(0, 15).astype(np.uint8)
    packed = (q[:, 0::2] << 4) | q[:, 1::2]
    delta = packed.copy()
    delta[1:] = packed[1:] - packed[:-1]
    with gzip.open(target, 'wb', compresslevel=8) as out:
        out.write(struct.pack('<4sIHH', b'BWC1', n, models, tiles))
        out.write(unpacked[order, :4].tobytes())
        out.write(delta.tobytes())
    return n


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('assets', type=Path)
    parser.add_argument('--store-originals', type=Path)
    args = parser.parse_args()
    if args.store_originals:
        args.store_originals.mkdir(parents=True, exist_ok=True)
    for part in sorted(args.assets.glob('game-*.bin.gz')):
        if '-view.' in part.name:
            continue
        target = part.with_name(part.name.replace('.bin.gz', '-view.bin.gz'))
        n = pack(part, target)
        if args.store_originals:
            shutil.move(part, args.store_originals / part.name)
        print(target.name, n, f'{target.stat().st_size / 1024**2:.2f} MB', flush=True)


if __name__ == '__main__':
    main()
