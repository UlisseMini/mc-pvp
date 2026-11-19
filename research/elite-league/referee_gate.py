"""Start boxed maintenance on the true native fresh-hit landmark; the playing policy sees a single drill start."""
import argparse
import itertools
from pathlib import Path
import random
from native_trials import run_batch
from run import ROOT

def native(out,smoke=False):
    arms=('research-wtap20','elite-boxing') if smoke else ('research-wtap20','elite-boxing','elite-boxing-student')
    landmarks=((3,2),) if smoke else ((3,6),(5,3),(10,2))
    prefix='egatesmoke' if smoke else 'egate'
    rows=[]
    for k,repeats in landmarks:
        name=f'combo-wake-ref{k}'
        base=(ROOT/'policies/combo-wake-2000.properties').read_text()
        text=base.rstrip()+'\nresearch_landmark_k='+str(k)+'\n'
        path=ROOT/'policies'/(name+'.properties')
        if path.exists() and path.read_text()!=text:raise ValueError('Conflicting referee drill settings')
        path.write_text(text)
        for arm,rep,rtt,side in itertools.product(arms,range(repeats),(0,80),'ab'):
            req=f'{prefix}-{k}-{arm}-{rep}-{rtt}-{side}';a,b=(arm,name) if side=='a' else (name,arm)
            aim={} if smoke else {'aim_input':'mouse'}
            rows.append((req,dict(mode='boxing',gate_k=k,profile='mmc',arm=arm,opponent=name,rep=rep,rtt=rtt,side=side,**aim,
                command=f'lab scenario {a} {b} {rtt} {rtt} 20000 {req} preset=mmc reach=3 cps=20 tapdelay=0 aimdelay=0 aimnoise=0 aimrate=0'+('' if smoke else ' aiminput=mouse'))))
    random.Random(551909).shuffle(rows);run_batch(dict(rows),out)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('out',type=Path)
    p.add_argument('--smoke',action='store_true',help='16 K3 instrument checks on W-tap and the original specialist')
    a=p.parse_args();native(a.out,a.smoke)
