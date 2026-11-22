"""Pack recorded BedWars terrain and reproducible clear shot starts for the bow arena."""
import argparse
import gzip
from hashlib import sha256
import itertools
import json
import math
from pathlib import Path
import random
import struct

# Plants, liquids, tripwire, redstone, torches, and other non-colliding decoration.
PASSABLE = {0, 6, 8, 9, 10, 11, 27, 28, 30, 31, 32, 37, 38, 39, 40, 50, 51, 55, 59,
            63, 65, 66, 68, 69, 75, 76, 77, 83, 90, 93, 94, 104, 105, 106, 111, 115, 119,
            127, 131, 132, 141, 142, 143, 147, 148, 149, 150, 157, 175, 176, 177}
# Models these with cube collision, but avoids making them initial standing surfaces.
DETAIL = {26, 44, 53, 64, 67, 71, 85, 96, 101, 102, 107, 108, 109, 113, 114, 126, 128,
          134, 135, 136, 139, 156, 160, 163, 164, 167, 171, 180, 182, 183, 184, 185,
          186, 187, 188, 189, 190, 191, 192, 193, 194, 195, 196, 197}

def records(values):
    return zip(*[iter(values)] * 5)


def snapshot(file, second):
    with gzip.open(file, 'rt') as stream:
        world = json.load(stream)
    initial = {(x, y, z): state for x, y, z, state, _ in records(world['blocks'])}
    for at, x, y, z, state in records(world['changes']):
        if at <= second:
            initial[x, y, z] = state
    return {point: state for point, state in initial.items() if state >> 4 not in PASSABLE}


def open_ray(blocks, a, b):
    dx, dy, dz = (b[i] - a[i] for i in range(3))
    n = math.ceil(math.sqrt(dx * dx + dy * dy + dz * dz) * 6)
    return all((math.floor(a[0] + .5 + dx * t/n), math.floor(a[1] + 1.28 + dy * t/n),
                math.floor(a[2] + .5 + dz * t/n)) not in blocks for t in range(1,n))


def pair_starts(blocks, seed):
    walking = {(x, y+1, z) for (x, y, z), state in blocks.items()
               if state >> 4 not in DETAIL and (x, y+1, z) not in blocks and (x,y+2,z) not in blocks}
    flat = sorted((x, y, z) for x, y, z in walking if all((x+dx, y, z+dz) in walking
                  for dx, dz in itertools.product(range(-2, 3), repeat=2)))
    if len(flat) < 14:
        raise ValueError(f'Only {len(flat)} wide standing points')
    rng = random.Random(seed)
    pairs = []
    for low, high in [(10,19),(20,33),(34,48)]:
        group = []
        for _ in range(42000):
            a, b = rng.sample(flat, 2)
            if abs(a[1] - b[1]) > 2 or not low <= math.hypot(a[0]-b[0],a[2]-b[2]) < high:
                continue
            if any(math.dist(a, p) < 4 and math.dist(b, q) < 4 for p,q in group):
                continue
            if open_ray(blocks, a, b):
                group.append((a,b))
                if len(group) == 8:
                    break
        if not group:
            raise ValueError(f'No starts for distance {low} to {high}')
        pairs.extend(group)
    return pairs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('assets', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--second', type=int, default=110)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    maps = [7,8,9,10,11]
    manifest = []
    with gzip.open(args.output, 'wb') as stream:
        stream.write(b'BWA1' + struct.pack('>i',len(maps)))
        for number in maps:
            file = args.assets / f'terrain-game-{number:02}.json.gz'
            blocks = snapshot(file, args.second)
            pairs = pair_starts(blocks, 571 + number)
            bounds = [fn(point[i] for point in blocks) for fn in (min,max) for i in range(3)]
            stream.write(struct.pack('>8i',number,*bounds,len(blocks)))
            for (x,y,z), state in sorted(blocks.items()):
                stream.write(struct.pack('>hhhH',x,y,z,state))
            stream.write(struct.pack('>i',len(pairs)))
            for a,b in pairs:
                stream.write(struct.pack('>6h',*a,*b))
            counts = [sum(low <= math.hypot(a[0]-b[0],a[2]-b[2]) < high for a,b in pairs)
                      for low,high in [(10,19),(20,33),(34,48)]]
            manifest.append(dict(game=number, source=file.name, sha256=sha256(file.read_bytes()).hexdigest(),
                                 second=args.second, blocks=len(blocks), spawn_pairs=pairs, distance_counts=counts))
            print(file.name, len(blocks), counts, flush=True)
    args.output.with_suffix('.json').write_text(json.dumps(manifest, indent=2)+'\n')


if __name__ == '__main__':
    main()
