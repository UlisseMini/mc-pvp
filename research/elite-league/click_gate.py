"""Compare the first learned Hold-W counter with the same network's click output pinned open."""
import argparse
import collections
import gzip
import itertools
import json
import math
from pathlib import Path
import random
from challenge_native import install
from mechanism import encode,unpack
from native_trials import run_batch
from run import HERE,ROOT,compile_model
from tournament import grid

WT='research-wtap20'
HOLD='research-hold20'
BASE='elite-br-hold'
OPEN='elite-br-hold-click-open'
OTHER='elite-br-hybrid'

def fixed_gate(base):
    family,w=unpack(base.read_text())
    if family!='MLP' or len(w)!=8*23+5*9:raise ValueError('Expected the frozen 22 × 8 × 5 MLP')
    at=len(w)-3*9
    w[at:at+9]=[1.]+[0.]*8
    return encode(family,w)+'\n'

def diagnose(evidence):
    result=collections.defaultdict(lambda:dict(heads=0,closed=0,before_cue_far=0,before_cue_near=0,after_cue_far=0,after_cue_near=0))
    with gzip.open(evidence,'rt') as f:games=json.load(f)['matches']
    for g in games:
        record=json.loads((ROOT/'runtime/results'/(g['mid']+'.json')).read_text())
        for side,x in g['bindings'].items():
            name=g['arm'] if side==g['side'] else g['opponent']
            stats=result[name];stats['heads']+=x['active_heads'];stats['closed']+=x['gate_closed']
            if not x['gate_closed']:continue
            path,=(ROOT/'runtime/pov'/g['mid']).glob(side+'-client-*-events.jsonl.gz')
            counted=0
            with gzip.open(path,'rt') as f:
                for line in f:
                    if '"duel_network"' not in line:continue
                    r=json.loads(line)
                    if r['type']!='duel_network' or not record['start']<=r['time']<=record['finish'] or r['action']//12%2:continue
                    q=r['observation'];gap=math.hypot(q[3]-q[0],q[5]-q[2])
                    stage='before_cue' if max(q[11:13])<0 else 'after_cue'
                    stats[stage+('_far' if gap>3.31 else '_near')]+=1;counted+=1
            if counted!=x['gate_closed']:raise ValueError('Missing gate observation')
    return result

def paths():
    base=HERE/'challenge-frozen/anti-hold.net';p=HERE/'click-gate-frozen/always-open.net'
    if p.read_text()!=fixed_gate(base):raise ValueError('The ablation must change only the click output')
    return {BASE:base,OPEN:p,OTHER:HERE/'challenge-frozen/anti-hybrid.net'}

def study(out,clocks):
    models=paths()
    if clocks:
        tapes=sorted(clocks.glob('*.txt.gz'))
        if len(tapes)!=8:raise ValueError('Expected the eight prospective robust-panel mirrors')
        compile_model();out.mkdir(parents=True)
        grid(out,'regulation',dict.fromkeys(('wtap','hold'))|{n:models[n] for n in (BASE,OPEN)},
             dict.fromkeys(('wtap','hold'))|{OTHER:models[OTHER]},tapes)
        return
    install({OPEN:models[OPEN]})
    trials=[]
    for arm,opp,rtt,seat,rep in itertools.product((WT,HOLD,BASE,OPEN),(WT,HOLD,OTHER),(0,80),'ab',range(2)):
        a,b=(arm,opp) if seat=='a' else (opp,arm)
        req=f'egc-{arm}-{opp}-{rep}-{rtt}-{seat}'
        trials.append((req,dict(mode='sumo',profile='mmc',arm=arm,opponent=opp,rtt=rtt,side=seat,rep=rep,
            command=f'lab sumo {a} {b} {rtt} {rtt} {req} preset=mmc reach=3 cps=20 tapdelay=0 aimdelay=0 aimnoise=0 aimrate=0')))
    random.Random(738131).shuffle(trials);run_batch(dict(trials),out)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('out',type=Path);p.add_argument('--clocks',type=Path)
    p.add_argument('--diagnose',type=Path)
    a=p.parse_args()
    if a.diagnose:
        with a.out.open('x') as f:json.dump(diagnose(a.diagnose),f,indent=2)
    else:study(a.out,a.clocks)
