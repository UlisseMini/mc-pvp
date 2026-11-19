"""Play a normal default sumo duel, a legacy override, and a return to default. Compare the live world with the captured block states."""
from pathlib import Path
import hashlib
import json
import struct
import sys
ROOT=Path(__file__).resolve().parents[2];HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'research/elite-league'))
from native_trials import run_batch
from ladder_transfer import WT,plan

def expected(source,diameter):
    captured=source['surface_states']
    rotated=list(zip(*reversed(captured)))
    def state(x,z):
        if diameter==13:return rotated[z+6][x+6] if abs(x)<=6 and abs(z)<=6 else 0
        return 112 if (x+.5)**2+(z+.5)**2<=(diameter/2)**2 else 0
    return hashlib.sha256(b''.join(struct.pack('>H',state(x,z)) for z in range(-8,8) for x in range(-8,8))).hexdigest()

def run(work,out):
    work.mkdir(exist_ok=True,parents=True);compare=json.loads((HERE/'evidence/platform.json').read_text());receipts=[]
    for i,diameter in enumerate((13,10,13)):
        tag='m13floor'+str(i);manifest=work/(tag+'.json');tasks=plan(tag,[WT],[WT],1)
        q,t=next((k,v) for k,v in tasks.items() if v['side']=='a' and v['rtt']==0)
        if i==1:t['command']+=' diameter=10'
        t.update(diameter=diameter,start_gap=8 if diameter==13 else 6)
        run_batch({q:t},manifest)
        mid=json.loads(manifest.read_text())['matches'][q];g=json.loads((ROOT/'runtime/results'/(mid+'.json')).read_text());arena=g['sumo_arena']
        if g.get('request')!=q or g['mode']!='sumo' or g['reason'] not in ('ring_out','double_ring_out','timeout_draw'):
            raise ValueError('Live check did not complete a regulation sumo match: '+mid)
        if (arena['diameter'],arena['start_gap'],arena['observed_floor_sha256'])!=(diameter,t['start_gap'],expected(compare,diameter)):raise ValueError('Native surface differs from recording: '+mid)
        with (ROOT/'runtime/replays'/(mid+'.jsonl')).open() as f:
            frames=[e for line in f if (e:=json.loads(line))['type']=='tick' and e['time']<g['start']]
        center=(g['arena']+1)*4096+(.5 if diameter==13 else 0);z=.5 if diameter==13 else 0
        positions=[[center-t['start_gap']/2,12,z],[center+t['start_gap']/2,12,z]]
        seated=[e for e in frames if all([e[s][k] for k in ('posX','posY','posZ')]==p for s,p in zip('ab',positions))]
        if not frames or len(seated)!=len(frames) or frames[-1]['time']<g['start']-150:
            raise ValueError('Both spawns must stay on their recorded centers through release: '+mid)
        receipts.append(dict(mid=mid,command=t['command'],geometry=arena,spawn_positions=positions,countdown_frames=len(seated),arena_slot=g['arena'],finish=g['reason']))
    out.write_text(json.dumps(dict(rotation='clockwise 90 degrees',floor_bytes='16 by 16, Z major then X, unsigned big endian block state IDs',cases=receipts),indent=2)+'\n')
    print([(r['mid'],r['geometry']['diameter'],r['countdown_frames']) for r in receipts])
if __name__=='__main__':run(ROOT/'build/elite-league/mmc13',HERE/'evidence/live-verification.json')
