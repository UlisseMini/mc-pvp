"""Write reproducible sideways and stair-bridge bot movement for the bow study."""
import argparse
import gzip
import json
import math
from pathlib import Path
import random
import struct

PROFILES = ("side_sneak", "side_release", "stair_sneak", "stair_release", "mixed_side_stairs")


def jump_curve(t):
    y, v = 0, .42
    for _ in range(t):
        y += v
        v = (v-.08)*.98
    return y


def tracks(profile, count, seed):
    for index in range(count):
        rng = random.Random(seed + (index+1)*107 + (profile+1)*2731)
        heading = rng.uniform(0,2*math.pi)
        ux, uz = math.cos(heading), math.sin(heading)
        blocks, stairs, time = 0, 0, 0
        x0, z0 = rng.randrange(-25,26)+.5,rng.randrange(-25,26)+.5
        floor = rng.randrange(62,93)
        pace = (index%5 + rng.random()) / 5
        bridge_phase = rng.randrange(9)
        rows = []
        for step in range(28 + index%7):
            upward = profile in (2,3) or profile == 4 and ((step+bridge_phase)//4)%2==1
            release = profile in (1,3,4) and (step + bridge_phase) % rng.randrange(2,6) != 0
            if upward:
                delay = rng.randint(2,5)
                move = round((15 if release else 20) - 5*pace) + rng.randint(-1,3)
                before_release = rng.randint(1,3)
                for t in range(delay+move):
                    airborne = max(0,t-delay)
                    y = 0 if airborne == 0 else max(1 if airborne >= 4 else 0, jump_curve(airborne))
                    f = min(1, airborne / max(8,move-2))
                    # Sneak during placement, optionally accelerate through the rest of the step.
                    sneaking = not release or airborne < before_release or airborne >= move-3
                    offset = blocks+f
                    rows.append((time,round((x0+ux*offset)*32)/32,round((floor+stairs+y)*32)/32,
                                 round((z0+uz*offset)*32)/32,6+int(sneaking)))
                    time += 1
                stairs += 1
            else:
                move = round((11 if release else 18) - 5*pace) + rng.randint(-1,3)
                pause = rng.randint(1,4)
                for t in range(move+pause):
                    f = min(1,t/move)
                    sneaking = not release or t >= move-2 or t == 0
                    offset = blocks+f
                    rows.append((time,round((x0+ux*offset)*32)/32,floor+stairs,round((z0+uz*offset)*32)/32,
                                 6+int(sneaking)))
                    time += 1
            blocks += 1
        yield rows


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('output',type=Path)
    p.add_argument('--count', type=int, default=44)
    p.add_argument('--seed', type=int, default=186713259)
    args=p.parse_args()
    with gzip.open(args.output,'wb') as stream:
        stream.write(b'BRG1'+struct.pack('>i',len(PROFILES)))
        for index, name in enumerate(PROFILES):
            stream.write(struct.pack('>ii',20+index,args.count))
            for rows in tracks(index,args.count,args.seed):
                stream.write(struct.pack('>i',len(rows)))
                for row in rows:
                    stream.write(struct.pack('>i3fB',*row))
    args.output.with_suffix('.json').write_text(json.dumps(
        dict(seed=args.seed,tracks_per_pattern=args.count,kind={20+i:n for i,n in enumerate(PROFILES)}),indent=2)+'\n')


if __name__ == '__main__':
    main()
