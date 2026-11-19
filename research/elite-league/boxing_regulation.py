"""Run scored first-to-100 on the native referee and mirrored simulation, alongside the maintenance drill."""
import argparse
import gzip
import itertools
import random
import subprocess
from pathlib import Path
from run import HERE,compile_model,java
from tournament import roster
from native_trials import run_batch

def study(clocks,out):
    out.mkdir(exist_ok=False,parents=True)
    path=out/'field.txt';roster(path,{'wtap':None,'hold':None,'specialist':HERE/'boxing-frozen/specialist.net','reference':HERE/'robust-frozen/champion.net'})
    with gzip.open(out/'boxing.jsonl.gz','xb') as f:
        with subprocess.Popen(java('BoxingRegulation',path,*clocks),stdout=subprocess.PIPE) as p:
            import shutil
            shutil.copyfileobj(p.stdout,f)
            if p.wait():raise RuntimeError('Boxing regulation failed')

def native(out):
    wt='research-wtap20';hold='research-hold20';rows=[]
    for arm,opp,n,rtt,side in itertools.product((wt,hold,'elite-boxing','elite-population'),(wt,hold),range(2),(0,80),'ab'):
        req=f'eb100-{arm}-{opp}-{n}-{rtt}-{side}';a,b=(arm,opp) if side=='a' else (opp,arm)
        rows.append((req,dict(mode='boxing',match_format='first-to-100',profile='mmc',arm=arm,opponent=opp,rep=n,rtt=rtt,side=side,
                command=f'lab match {a} {b} {rtt} {rtt} {req} preset=mmc cps=20 reach=3 tapdelay=0 aimdelay=0 aimnoise=0 aimrate=0')))
    random.Random(470081).shuffle(rows);run_batch(dict(rows),out)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('out',type=Path);p.add_argument('--clocks',type=Path);a=p.parse_args()
    if a.clocks:compile_model();study(sorted(a.clocks.glob('*.txt.gz')),a.out)
    else:native(a.out)
