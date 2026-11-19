"""Run scored first-to-100 on the native referee and mirrored simulation, alongside the maintenance drill."""
import argparse
import gzip
import itertools
import json
import random
import subprocess
from pathlib import Path
from run import HERE,compile_model,java
from tournament import roster
from native_trials import run_batch
from adaptation import dump,sha
from history_cup import counts
import champions

NAMES={'wtap':'research-wtap20','hold':'research-hold20','specialist':'elite-boxing','reference':'elite-population'}

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
        rows.append((req,dict(mode='boxing',match_format='first-to-100',profile='mmc',arm=arm,opponent=opp,rep=n,rtt=rtt,side=side,aim_input='mouse',
                command=f'lab match {a} {b} {rtt} {rtt} {req} preset=mmc cps=20 reach=3 tapdelay=0 aimdelay=0 aimnoise=0 aimrate=0 aiminput=mouse')))
    random.Random(470081).shuffle(rows);run_batch(dict(rows),out)

def publish(folder):
    """Keep a distinct playable winner for the native first-to-100 tournament."""
    actual=folder/'native.json.gz';model=folder/'boxing.jsonl.gz'
    with gzip.open(actual,'rt') as f:n=json.load(f)
    with gzip.open(model,'rt') as f:sim=list(map(json.loads,f))
    rows=n['matches']
    expected={(a,o,r,t,s) for a,o,r,t,s in itertools.product(NAMES.values(),('research-wtap20','research-hold20'),range(2),(0,80),'ab')}
    if {(g['arm'],g['opponent'],g['rep'],g['rtt'],g['side']) for g in rows}!=expected or len(rows)!=len(expected):
        raise ValueError('Incomplete first-to-100 native field')
    if n['summary']['binding_disagreements'] or any(g['match_format']!='first-to-100' for g in rows):
        raise ValueError('First-to-100 input or referee format differs')
    donors={r['clock'] for r in sim}
    cells={(a,o,d,t,s) for a,o,d,t,s in itertools.product(NAMES,('wtap','hold'),donors,(0,80),'ab')}
    if len(donors)!=8 or len(sim)!=len(cells) or {(r['arm'],r['opponent'],r['clock'],r['rtt'],r['side']) for r in sim}!=cells:
        raise ValueError('Incomplete first-to-100 simulation')
    for r in sim:r.update(arm=NAMES[r['arm']],opponent=NAMES[r['opponent']])
    physical=counts(rows);forecast=counts(sim,True)
    # If scores tie, retain plain W-tap when it shares first place, then use the alias as a stable tie-break.
    winner=min(physical,key=lambda a:(-physical[a]['mean'],-physical[a]['worst'],a!=NAMES['wtap'],a))
    champions.install('boxing-regulation',champions.POLICIES/(winner+'.properties'),
                      'Native first-to-100 boxing tournament winner ('+winner+')','boxing-regulation',mode='boxing')
    dump(folder/'tournament.json',dict(winner=winner,native=physical,model=forecast,native_sha256=sha(actual),model_sha256=sha(model)))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('out',type=Path);p.add_argument('--clocks',type=Path);p.add_argument('--publish',action='store_true');a=p.parse_args()
    if a.publish:publish(a.out)
    elif a.clocks:compile_model();study(sorted(a.clocks.glob('*.txt.gz')),a.out)
    else:native(a.out)
