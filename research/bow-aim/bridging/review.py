"""Associate bow releases with owned arrow packets and inspect observed near misses."""
import argparse
import base64
from bisect import bisect_left, bisect_right
from collections import defaultdict
import csv
from datetime import datetime
from pathlib import Path
import gzip
import json
import math
import re
import struct
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'bedwars-forecast'))
from extract import read_varint

CAPTURES = Path.home()/'Library/Application Support/minecraft/config/pvp-practice/captures'
PART = re.compile(r'(?:-part(\d+))?\.jsonl\.gz$')


def load(path, session, stop):
    frames, arrows, release, hurt = [], [], [], defaultdict(list)
    files = sorted(path.glob(f'a-client-{session}-*.jsonl.gz'),
                   key=lambda name: int(PART.search(name.name)[1] or 1))
    if not files:
        raise ValueError(f'No parts for {session}')
    for f in files:
        try:
            with gzip.open(f, 'rb') as stream:
                for line in stream:
                    if line.startswith(b'{"type":"tick_end"'):
                        row = json.loads(line)
                        if row['time_ms'] > stop:
                            continue
                        view = row['data']
                        own = view.get('self')
                        if own is None:
                            continue
                        players, paths = {}, {}
                        for e in view.get('entities',[]):
                            if e.get('uuid') and e['id'] != own['id'] and not e.get('noClip') and e.get('health',0) > 0:
                                players[e['id']] = (e['pos'],e['box'],e.get('sneaking',False))
                            elif e.get('kind') == 'EntityArrow':
                                paths[e['id']] = e['pos']
                        frames.append((row['time_ms'],row['client_tick'],own,players,paths))
                        continue
                    if not line.startswith(b'{"type":"packet_wire"') or not any(k in line for k in
                            (b'S0EPacketSpawnObject',b'C07PacketPlayerDigging',b'S19PacketEntityStatus')):
                        continue
                    row = json.loads(line)
                    t = row['time_ms']
                    if t > stop:
                        continue
                    d = row['data']
                    raw = base64.b64decode(d['base64'])
                    if not raw:
                        continue
                    if d['packet']['kind']=='C07PacketPlayerDigging' and raw[:2] == b'\x07\x05':
                        release.append(t)
                    elif d['packet']['kind']=='S19PacketEntityStatus' and len(raw)==6 and raw[-1]==2:
                        hurt[struct.unpack_from('>i',raw,1)[0]].append(t)
                    elif d['packet']['kind']=='S0EPacketSpawnObject':
                        entity,k=read_varint(raw,1)
                        if raw[k] != 60:
                            continue
                        x,y,z,_,_,owner=struct.unpack_from('>iiiBBi',raw,k+1)
                        arrows.append((t,owner,entity,(x/32,y/32,z/32)))
        except (EOFError,OSError,json.JSONDecodeError):
            if f != files[-1]:
                raise
    return sorted(frames),sorted(arrows),sorted(set(release)),hurt


def nearest(frames, fired, owner, arrow, origin):
    previous = origin
    best = None
    seen = 0
    for t,_,own,players,arrows in frames:
        if own['id'] != owner:
            break
        position=arrows.get(arrow)
        if position is None:
            if seen:
                break
            continue
        seen += 1
        length=math.dist(previous,position)
        if length>6:
            previous=position
            continue
        samples=max(1,math.ceil(length*10))
        for step in range(1,samples+1):
            point=[p+(q-p)*step/samples for p,q in zip(previous,position)]
            for target,(feet,box,sneak) in players.items():
                launch_range=math.dist(origin,feet)
                if launch_range<5 or launch_range>78:
                    continue
                errors=[max(0,box[i]-.30-p,p-box[i+3]-.30) for i,p in enumerate(point)]
                miss=math.hypot(*errors)
                if best is None or miss<best[0]:
                    best=(miss,t,target,point[1]-feet[1],launch_range,sneak,*errors)
        previous=position
    return best,seen


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('session')
    p.add_argument('output',type=Path)
    p.add_argument('--end-ms',type=int,required=True)
    p.add_argument('--captures',type=Path,default=CAPTURES)
    args=p.parse_args()
    frames,arrows,releases,hurt=load(args.captures,args.session,args.end_ms)
    times=[f[0] for f in frames]
    rows=[]
    used=set()
    for rt in releases:
        k=bisect_right(times,rt)-1
        if k<0:
            continue
        _,_,current,_,_=frames[k]
        prior=[f[2] for f in frames[max(0,k-3):k+1] if f[2]['id']==current['id'] and f[2].get('using_item')
               and f[2].get('held_item_id')==261 and f[0]>=rt-200]
        if not prior: continue
        choices=[a for a in arrows if a[1]==current['id'] and rt-20<=a[0]<=rt+1000 and a not in used]
        if not choices: continue
        flight,owner,arrow,origin=min(choices,key=lambda a:abs(a[0]-rt))
        used.add((flight,owner,arrow,origin))
        best,visible=nearest(frames[bisect_left(times,flight)-1:bisect_right(times,flight+1950)],
                             flight,owner,arrow,origin)
        remaining = prior[-1]['use_ticks']
        elapsed = prior[-1].get('use_elapsed_ticks', 72000-remaining)
        row=dict(local_time=datetime.fromtimestamp(rt/1000).strftime('%H:%M:%S.%f')[:-3],draw_ticks=elapsed,
                 launch_ms=flight-rt,observed_arrow_ticks=visible)
        if best:
            miss,t,target,height,rng,sneak,ex,ey,ez=best
            row.update(target=f'P{target}',gap_blocks=round(miss,2),approx_range=round(rng,1),sneaking=sneak,
                       arrow_above_target_feet=round(height,2),vertical_gap=round(ey,2),
                       horizontal_gap=round(math.hypot(ex,ez),2),
                       nearby_hurt_event=any(t-100<=q<=t+150 for q in hurt[target]))
            nearby=frames[bisect_left(times,t-750):bisect_right(times,t+200)]
            poses=[(v[0],v[3][target][0],v[3][target][2]) for v in nearby if target in v[3]]
            if poses:
                row['target_rise_last_750ms']=round(poses[-1][1][1]-poses[0][1][1],2)
                row['target_horizontal_last_750ms']=round(math.dist([poses[-1][1][i] for i in (0,2)],
                                                                   [poses[0][1][i] for i in (0,2)]),2)
        rows.append(row)
    columns=list(dict.fromkeys(k for row in rows for k in row))
    with args.output.open('w') as f:
        writer=csv.DictWriter(f,fieldnames=columns)
        writer.writeheader()
        writer.writerows(rows)
    print(f'{len(releases)} release packets, {len(rows)} owned arrow launches, '
          f'{sum(row["observed_arrow_ticks"]>0 for row in rows)} with observed flight samples')


if __name__=='__main__':
    main()
