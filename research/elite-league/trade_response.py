"""Fit short explicit tactical interventions using their own-prefix branch outcomes."""
import argparse
import json
from pathlib import Path
import subprocess
from run import compile_model,java,calendars,HERE,ROOT
from tournament import roster,grid,read
from human_sparring import controls,bucket
WORDS=('continue','release-ctrl','release-w','back','recoil-click','late-click','jump','left','right')

def fit(out,season,exchange):
    out.mkdir(exist_ok=False,parents=True);arms={'table':season/'table.net','reference':controls()['reference']}
    field=controls()|{'human-trained':HERE/'human-challenge-frozen/human.net','old-search':season/'search.net','old-neural':season/'neural.net'}
    if exchange==2:
        for base in ('table','reference'):
            field['first-trade-'+base]=HERE/'ladder-refinements/round-3'/(base+'.net')
        field['old-rule']=season/'rule.net'
    ar=out/'arms.txt';op=out/'field.txt';roster(ar,arms);roster(op,field)
    for split,key in [('train','training_clocks'),('dev','development_selection_clocks')]:
        with (out/(split+'.jsonl')).open('x') as f:
            subprocess.run(java('TradeWords',ar,op,*(['--second-trade'] if exchange==2 else []),*(['--phase-fit'] if split=='train' else []),*calendars(key)),stdout=f,check=True,cwd=ROOT)
        for arm in arms:
            with (out/(split+'.jsonl')).open() as inp,(out/(split+'-'+arm+'.txt')).open('x') as f:
                for l in inp:
                    r=json.loads(l)
                    if r['prefix']!=arm:continue
                    cs=r.get('continuations');nums=[bucket(r['opponent']),r['base'],cs['wtap'][0] if cs else r['base']]
                    if cs:nums.extend(r['x']);nums.extend(cs[n][0] for n in WORDS)
                    f.write(' '.join(map(str,nums))+'\n')
    for n,path in arms.items():
        subprocess.run(java('TradeFit',out/('train-'+n+'.txt'),out/('dev-'+n+'.txt'),path,out/n,exchange),check=True,cwd=ROOT)
    challengers={n+'-response':out/(n+'.net') for n in arms}
    raw=read(grid(out,'closed-loop',{'wtap':None,'hold':None}|arms|challengers,field,calendars('development_selection_clocks')))
    branch=[json.loads(l) for l in (out/'dev.jsonl').open()]
    for n in arms:
        expected=json.loads((out/(n+'.json')).read_text())['total_base_wtap_continuation']
        actual=sum(r['score'] for r in raw if r['arm']==n+'-response')
        if actual!=expected[0]:raise ValueError(f'{n}: closed-loop score {actual} differs from counterfactual labels {expected[0]}')
        print(n,'original and revised',expected[1],actual,'matched full W-tap',sum(r['score'] for r in raw if r['arm']=='wtap'),flush=True)
    return challengers

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('out',type=Path);p.add_argument('season',type=Path);p.add_argument('--exchange',type=int,choices=(1,2),default=1);a=p.parse_args()
    compile_model();fit(a.out,a.season,a.exchange)
