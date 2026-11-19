"""Fit a dedicated specialist and select on paired development forks."""
import argparse
import hashlib
import json
import math
import shutil
import subprocess
from pathlib import Path
from run import HERE,ROOT,calendars,compile_model,java
from tournament import grid,read

def publish(folder):
    from champions import install
    d=json.loads((folder/'selection.json').read_text());n=d['chosen'];net=folder/'specialist.net'
    if hashlib.sha256(net.read_bytes()).hexdigest()!=d['sha256']:raise ValueError('Changed boxing weights')
    improvement=sum(math.log1p(candidate)-math.log1p(wtap)+.15*((candidate>wtap)-(candidate<wtap))
                    for wtap,candidate in d['scores'][n])
    source=net if improvement>0 else ROOT/'policies/research-wtap20.properties'
    install('boxing-01',source,'First established-combo tournament winner','boxing-01',mode='boxing')

def duration(cut,arm,first):
    t=cut['landmark'];last=t;cycles=0
    for at,side_a,upgrade in cut['committed']+cut[arm]['hits']:
        if at-last>1000:break
        if side_a!=first:break
        if not upgrade:cycles+=1;last=at
    return cycles

def select(out,models):
    out.mkdir(parents=True,exist_ok=False)
    arms={p.parent.name+'-'+p.stem:p for p in sorted(models.glob('*/*.net'))}
    result=read(grid(out,'dev-boxing',arms,{'wtap':None},calendars('development_selection_clocks'),mode='boxing'))
    from collections import defaultdict
    scores=defaultdict(list)
    for r in result:
        if '3' not in r['forks']:continue
        cut=r['forks']['3']
        scores[r['arm']].append([duration(cut,arm,r['side']=='a') for arm in ('wtap','rl')])
    merit=lambda pair:math.log1p(pair[1])-math.log1p(pair[0])+.15*((pair[1]>pair[0])-(pair[1]<pair[0]))
    ranks=sorted(scores,key=lambda k:(-sum(merit(x) for x in scores[k])/len(scores[k]),k))
    chosen=ranks[0];net=out/'specialist.net';shutil.copyfile(arms[chosen],net)
    (out/'selection.json').write_text(json.dumps(dict(chosen=chosen,sha256=hashlib.sha256(net.read_bytes()).hexdigest(),rank=ranks,scores=scores),indent=2)+'\n')
    publish(out)
    print('Selected',chosen,'W-tap and specialist cycles',list(map(sum,zip(*scores[chosen]))))

if __name__=='__main__':
    p=argparse.ArgumentParser();subs=p.add_subparsers(dest='action',required=True)
    s=subs.add_parser('train');s.add_argument('out',type=Path)
    s=subs.add_parser('select');s.add_argument('models',type=Path);s.add_argument('out',type=Path)
    a=p.parse_args();compile_model()
    if a.action=='train':subprocess.run(java('BoxingFit',a.out,72,36,36,*calendars('training_clocks')),cwd=ROOT,check=True)
    else:select(a.out,a.models)
