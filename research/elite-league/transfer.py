"""Run registered prospective evaluations for the boxing and robust sumo specialists."""
import argparse
import itertools
import json
import random
from pathlib import Path
from run import HERE,compile_model
from tournament import grid
from robust_select import field
from native_trials import run_batch,installed,ROOT
import hashlib
WT='research-wtap20';HOLD='research-hold20';V1='elite-v1-hybrid'
PPO='elite-ppo';POP='elite-population'
DIRECTIONS=('wtap-tick-window','kb-displace-pull','kb-displace-edge','phase-spacing')

def install_robust(selected):
    cfg=json.loads((selected/'selection.json').read_text())
    for kind,arm in (('ppo',PPO),('population',POP)):
        path=selected/(kind+'.net')
        assert hashlib.sha256(path.read_bytes()).hexdigest()==cfg['weights'][kind]
        dst=ROOT/'policies'/(arm+'.properties')
        base=[s for s in (ROOT/'policies/research-hold20.properties').read_text().splitlines() if not s.startswith('description=')]
        data='\n'.join(base+[f'description=Frozen {kind} elite sumo tournament finalist. Normal keys and attacks.',
            f'duel_network={path.read_text().strip()}','duel_network_landmark=0'])+'\n'
        if dst.exists() and dst.read_text()!=data:raise ValueError(f'{dst.name} has different weights')
        dst.write_text(data)
    return PPO if cfg['chosen']['ppo']==cfg['chosen']['champion'] else POP

def study(which,selected,clocks,out):
    out.mkdir(parents=True,exist_ok=False)
    if which=='boxing':
        grid(out,'matched-forks',{'specialist':selected/'specialist.net'},{'wtap':None},clocks,mode='boxing')
    else:
        arms={k:v for k,v in field().items() if k in ('wtap','hold','v1-hybrid','anti-hold','anti-hybrid')}
        arms|={'ppo':selected/'ppo.net','population':selected/'population.net'}
        opps=field()|dict.fromkeys(DIRECTIONS)|{'ppo':selected/'ppo.net','population':selected/'population.net'}
        grid(out,'regulation',arms,opps,clocks)
        grid(out,'asymmetric',arms,{'wtap':None,'anti-hybrid':field()['anti-hybrid']},clocks,mode='asymmetric')
        grid(out,'hypixel',arms,{'wtap':None,'hold':None},clocks,profile='hypixel')

def live(which,selected,out):
    arms=[WT,'elite-boxing'] if which=='boxing' else [WT,HOLD,V1,PPO,POP]
    groups=[]
    if which=='boxing':groups=[('boxing','mmc','combo-wake-2000',arms,8,[(0,0),(80,80)])]
    else:
        winner=install_robust(selected)
        groups=[('sumo','mmc',opp,arms,2,[(0,0),(80,80)]) for opp in (WT,HOLD,V1,'elite-br-hold','elite-br-hybrid','research-zest-strafe')]
        for opp in DIRECTIONS:groups.append(('sumo','mmc',opp,[WT,HOLD,V1,winner],2,[(0,0),(80,80)]))
        for opp in (WT,'elite-br-hybrid'):groups.append(('sumo','mmc',opp,[WT,HOLD,V1,winner],2,[(40,100),(100,40)]))
        for opp in (WT,HOLD):groups.append(('sumo','hypixel',opp,[WT,HOLD,V1,winner],2,[(0,0),(80,80)]))
    rows=[]
    for mode,profile,opp,group,repeats,pings in groups:
        for arm,rep,(own,other),side in itertools.product(group,range(repeats),pings,'ab'):
            tag='ebx' if which=='boxing' else 'erb'
            req=f'{tag}-{profile}-{opp}-{arm}-{rep}-{own}-{other}-{side}'
            a,b,p,q=(arm,opp,own,other) if side=='a' else (opp,arm,other,own)
            start=f'lab sumo {a} {b} {p} {q}' if mode=='sumo' else f'lab scenario {a} {b} {p} {q} 20000'
            rows.append((req,dict(mode=mode,profile=profile,opponent=opp,arm=arm,rep=rep,rtt=own,opponent_rtt=other,side=side,
                command=f'{start} {req} preset={profile} reach=3 cps=20 tapdelay=0 aimdelay=0 aimnoise=0 aimrate=0')))
    random.Random(634811).shuffle(rows);run_batch(dict(rows),out)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('which',choices=('boxing','robust'));p.add_argument('selected',type=Path);p.add_argument('out',type=Path)
    p.add_argument('--clocks',type=Path);a=p.parse_args()
    if a.clocks:
        compile_model();study(a.which,a.selected,sorted(a.clocks.glob('*.txt.gz')),a.out)
    else:live(a.which,a.selected,a.out)
