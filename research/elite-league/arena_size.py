"""Compare legal sumo controllers as the distance to a solid ring edge changes."""
import argparse
import itertools
import random
from pathlib import Path
from run import HERE,compile_model
from native_trials import run_batch
from tournament import grid
from human_sparring import controls

def model(clocks,selected,out):
    out.mkdir(exist_ok=False,parents=True)
    arms={'wtap':None,'hold':None,'reference':controls()['reference'],'human-trained':selected/'human.net'}
    opponents={'wtap':None,'hold':None,'anti-hybrid':controls()['anti-hybrid']}
    for diameter in (8,10,12,14,16):
        grid(out,'disk-'+str(diameter),arms,opponents,clocks,mode='disk-'+str(diameter))

def native(out):
    wt='research-wtap20';hold='research-hold20';rows=[]
    for d,arm,opp,rep,rtt,side in itertools.product((8,10,12,14,16),(wt,hold,'elite-population','elite-human-trained'),
            (wt,hold,'elite-br-hybrid'),range(3),(0,80),'ab'):
        req=f'ed1-{d}-{arm}-{opp}-{rep}-{rtt}-{side}';a,b=(arm,opp) if side=='a' else (opp,arm)
        rows.append((req,dict(mode='sumo',profile='mmc',diameter=d,opponent=opp,arm=arm,rep=rep,rtt=rtt,side=side,
            command=f'lab sumo {a} {b} {rtt} {rtt} {req} preset=mmc diameter={d} reach=3 cps=20 tapdelay=0 aimdelay=0 aimnoise=0 aimrate=0')))
    random.Random(663791).shuffle(rows);run_batch(dict(rows),out)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('out',type=Path);p.add_argument('--clocks',type=Path);p.add_argument('--selected',type=Path)
    a=p.parse_args()
    if a.clocks:compile_model();model(sorted(a.clocks.glob('*.txt.gz')),a.selected,a.out)
    else:native(a.out)
