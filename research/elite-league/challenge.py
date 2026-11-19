"""Fit and select new opponents specifically to attack the frozen hybrid and Hold-W."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
from tournament import grid, read
from run import HERE,ROOT,calendars,compile_model,java
from collections import defaultdict

def train(out,threads):
    out.mkdir(parents=True,exist_ok=False)
    for target in ('hold','hybrid'):
        for seed in (71033,37577):
            for fam in ('MLP','RECURRENT'):
                dest=out/f'{target}-{fam.lower()}-{seed}'
                subprocess.run(java('BestResponse',dest,fam,seed,128,56,12,threads,
                    'hold' if target=='hold' else HERE/'frozen/hybrid.net',HERE/'frozen'/(fam.lower()+'.net'),
                    *calendars('training_clocks')),cwd=ROOT,check=True)

def select(fit,out):
    out.mkdir(parents=True,exist_ok=False)
    pool={p.parent.name+'-'+p.stem:p for p in sorted(fit.glob('*/*.net'))}
    assert len(pool)==2*2*2*17
    controls=dict.fromkeys(('wtap','hold','pressure'))
    refs=controls|{'hybrid':HERE/'frozen/hybrid.net'}
    rows=read(grid(out,'target-selection',controls|pool,refs,calendars('development_selection_clocks')))
    points=defaultdict(lambda:defaultdict(list))
    for r in rows:points[r['arm']][r['opponent']].append(r['score'])
    rates={arm:{k:sum(v)/len(v) for k,v in matchups.items()} for arm,matchups in points.items()}
    picks={}
    for target in ('hold','hybrid'):
        rank=lambda n:(-rates[n][target],-sum(rates[n][k] for k in ('wtap','hold','hybrid')),n)
        chosen=min((n for n in pool if n.startswith(target+'-')),key=rank)
        outnet=out/f'anti-{target}.net';shutil.copyfile(pool[chosen],outnet)
        picks[target]=dict(name=chosen,sha256=hashlib.sha256(outnet.read_bytes()).hexdigest())
    result=dict(selected=picks,scores=rates,bouts=len(rows))
    (out/'selection.json').write_text(json.dumps(result,indent=2)+'\n')
    return picks

if __name__=='__main__':
    p=argparse.ArgumentParser();subs=p.add_subparsers(dest='mode',required=True)
    s=subs.add_parser('train');s.add_argument('out',type=Path);s.add_argument('--workers',type=int,choices=(1,2,3,4),default=2)
    s=subs.add_parser('select');s.add_argument('fit',type=Path);s.add_argument('out',type=Path)
    a=p.parse_args();compile_model()
    if a.mode=='train':train(a.out,a.workers)
    else:print(select(a.fit,a.out))
