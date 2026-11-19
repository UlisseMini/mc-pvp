"""Deploy the frozen best responses, play a balanced challenge, and test on separate fresh clocks."""
import argparse
import itertools
import json
import random
from pathlib import Path
from run import HERE,ROOT
from tournament import grid
from native_trials import run_batch
import hashlib

def opponents(selected):
    d=json.loads((selected/'selection.json').read_text());result={}
    for target in ('hold','hybrid'):
        file=selected/('anti-'+target+'.net');assert hashlib.sha256(file.read_bytes()).hexdigest()==d['selected'][target]['sha256']
        result['elite-br-'+target]=file
    return result

def install(opps):
    for n,m in opps.items():
        dest=ROOT/'policies'/(n+'.properties')
        template=(ROOT/'policies/research-hold20.properties').read_text().splitlines()
        content='\n'.join([l for l in template if not l.startswith('description=')]+[
            'description=Adversarial training opponent using normal client inputs','duel_network='+m.read_text().strip(),'duel_network_landmark=0'])+'\n'
        if dest.exists() and dest.read_text()!=content:raise ValueError('Frozen opponent has changed')
        dest.write_text(content)

def native(opps,out):
    install(opps)
    rows=[];arms=('research-wtap20','research-hold20','sumo-rl-pressure','elite-v1-hybrid')
    # Also test each best response attacking W-tap and Hold-W in both simulator and Minecraft.
    pairs={(a,b) for a in arms for b in opps}|{(a,b) for a in (*opps,arms[0],arms[1]) for b in arms[:2]}
    for (arm,opp),rep,rtt,side in itertools.product(sorted(pairs),range(2),(0,80),'ab'):
        name=f'elite-br2-{arm}-{opp}-{rep}-{rtt}-{side}';a,b=(arm,opp) if side=='a' else (opp,arm)
        rows.append((name,dict(mode='sumo',profile='mmc',opponent=opp,arm=arm,rep=rep,rtt=rtt,side=side,
            command=f'lab sumo {a} {b} {rtt} {rtt} {name} preset=mmc reach=3 cps=20 tapdelay=0 aimdelay=0 aimnoise=0 aimrate=0')))
    random.Random(819374).shuffle(rows);run_batch(dict(rows),out)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('selected',type=Path);p.add_argument('out',type=Path)
    p.add_argument('--clocks',type=Path,help='Run a simulated cross-play comparison on these calendars')
    a=p.parse_args();opps=opponents(a.selected)
    if a.clocks:
        a.out.mkdir(parents=True,exist_ok=False);field=dict.fromkeys(('wtap','hold','pressure'))|{'hybrid':HERE/'frozen/hybrid.net'}|opps
        grid(a.out,'prospective',field,field,sorted(a.clocks.glob('*.txt.gz')))
    else:native(opps,a.out)
