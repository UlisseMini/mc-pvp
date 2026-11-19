"""Screen robust policies on development clocks and freeze the challenger before transfer."""
import argparse
from collections import defaultdict
from pathlib import Path
import hashlib
import json
import shutil
from tournament import grid,read
from run import HERE,FAMILIES,calendars

def groups(opponent):
    if opponent in ('wtap','hold','trade','pressure'):return 'basic'
    if opponent in ('select','counterstrafe','reset','zest-strafe'):return 'timing'
    if opponent.startswith('v1-'):return 'v1-architectures'
    if opponent.startswith('anti-'):return 'targeted-counters'
    return 'later-self-play'

def rank(rows):
    scores=defaultdict(lambda:defaultdict(list))
    for r in rows:scores[r['arm']][groups(r['opponent'])].append(r['score'])
    result=[]
    for name,buckets in scores.items():
        v={k:sum(vals)/len(vals) for k,vals in buckets.items()};avg=sum(v.values())/len(v);worst=min(v.values())
        result.append(dict(name=name,merit=.6*avg+.4*worst,avg=avg,worst=worst,groups=v))
    return sorted(result,key=lambda r:(-r['merit'],-r['avg'],r['name']))

def field():
    return dict.fromkeys(('wtap','hold','trade','pressure','select','counterstrafe','reset','zest-strafe'))|{f'v1-{f.lower()}':HERE/'frozen'/(f.lower()+'.net') for f in FAMILIES}|{
        'anti-'+n:HERE/'challenge-frozen'/('anti-'+n+'.net') for n in ('hold','hybrid')}

def select(fit,out):
    out.mkdir(parents=True,exist_ok=False);clocks=calendars('development_selection_clocks')
    refs=field();base={k:refs[k] for k in ('wtap','hold','v1-hybrid','anti-hybrid','anti-hold')}
    ppo={p.parent.name+'-'+p.stem:p for p in sorted(fit.glob('ppo-*/*.net'))
         if int(p.stem[1:])%32==0 or int(p.stem[1:])==767}
    assert len(ppo)==50
    pop={p.parent.name+'-'+p.stem:p for p in sorted(fit.glob('population/epoch*/*.net'))}
    assert len(pop)==192
    stages={}
    for f in sorted((fit/'population').glob('epoch*/stage-selection.json')):
        n=json.loads(f.read_text())['winner'];stages['league-'+f.parent.name]=f.parent/(n+'.net')
    assert len(stages)==12
    pool=ppo|pop
    # Initial stage includes predeclared population champions at epochs 0, 5 and 11 plus both late PPO runs.
    anchors={k:v for k,v in stages.items() if any(s in k for s in ('epoch00-','epoch05-','epoch11-'))}
    anchors.update({k:v for k,v in ppo.items() if k.endswith('g0767')})
    first=read(grid(out,'screen',base|pool,refs|anchors,clocks));screen=rank(first)
    best={}
    for family in ('ppo','linear','mlp','recurrent','hybrid'):
        chosen=0;weights=set()
        for r in screen:
            n=r['name']
            if n not in pool or (not n.startswith('ppo-') if family=='ppo' else '-'+family+'-' not in n):continue
            blob=pool[n].read_bytes()
            if blob in weights:continue
            best[n]=pool[n];weights.add(blob);chosen+=1
            if chosen==3:break
    final=read(grid(out,'crossplay',base|best,refs|best,clocks));ranked=rank(final)
    champ=next(r['name'] for r in ranked if r['name'] in best)
    ppo_pick=next(r['name'] for r in ranked if r['name'].startswith('ppo-'))
    pop_pick=next(r['name'] for r in ranked if r['name'].startswith('epoch'))
    names={'champion':champ,'ppo':ppo_pick,'population':pop_pick}
    for key,n in names.items():shutil.copyfile(pool[n],out/(key+'.net'))
    d=dict(chosen=names,weights={k:hashlib.sha256(pool[n].read_bytes()).hexdigest() for k,n in names.items()},
           screen=screen,crossplay=ranked,bouts=len(first)+len(final))
    (out/'selection.json').write_text(json.dumps(d,indent=2)+'\n');return names

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('fit',type=Path);p.add_argument('out',type=Path);a=p.parse_args();print(select(a.fit,a.out))
