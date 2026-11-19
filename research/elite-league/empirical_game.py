"""Fit alternating explicit and neural best responses against a growing symmetric empirical game."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
from crossplay import matrix
from champions import publish_round
from ladder import initial,roster_fields,MILESTONES
from ladder_transfer import WT,HOLD,REFERENCE,models as frozen_roster,native_install,plan
from native_trials import run_batch
from run import HERE,ROOT,calendars,compile_model,java
from tournament import grid,roster
TRACKS=('rule','table','search','neural')
NEURAL='elite-game-neural'
EXPLICIT='elite-game-explicit'

def write(path,data):
    with path.open('x') as f:json.dump(data,f,indent=2);f.write('\n')

def payoff(path):
    with gzip.open(path,'rt') as f:return matrix(json.loads(l) for l in f)

def update_game(m):
    names=sorted({x for x,_ in m})
    if any(abs(m[a,b]+m[b,a]-1)>1e-12 for a in names for b in names):raise ValueError('Unbalanced seat results')
    game=[[m[a,b] for b in names] for a in names]
    regrets=[0.]*len(names);total=[0.]*len(names);steps=4096
    for i in range(steps):
        weight=sum(regrets)
        p=[r/weight for r in regrets] if weight else [1./len(names)]*len(names)
        support=[(j,w) for j,w in enumerate(p) if w]
        for j,row in enumerate(game):
            score=sum(row[k]*w for k,w in support)
            # Every mixture scores exactly one half against itself in this seat-balanced game.
            regrets[j]=max(0.,regrets[j]+score-.5);total[j]+=p[j]
    p=[v/steps for v in total]
    replies={n:sum(w*x for w,x in zip(p,row)) for n,row in zip(names,game)}
    if abs(sum(p)-1)>1e-9:raise AssertionError('Mixture does not sum to one')
    worst=min(sum(p[j]*game[j][i] for j in range(len(names))) for i in range(len(names)))
    return dict(mixture=dict(zip(names,p)),responses=replies,worst=worst,best=max(replies.values()),gap=max(replies.values())-worst)

def field(out):
    members,_=initial(out)
    for i in MILESTONES:
        for t in TRACKS:members[f's{i:02d}-{t}']=HERE/f'evidence/ladder-development/season-{i:02d}/{t}.net'
    if len(members)!=43:raise ValueError('Expected the frozen 43-strategy initial game')
    return members

def evaluate(m,n,opponents):
    vs=sorted(m[n,o] for o in opponents);avg=sum(vs)/len(vs);tail=sum(vs[:5])/5
    return dict(name=n,mean=avg,bottom_five=tail,merit=.5*(avg+tail),opponents={o:m[n,o] for o in opponents})

def response(out,round_id,members,m,frames,previous,feedback):
    stage=out/f'round-{round_id:02d}';stage.mkdir()
    equilibrium=update_game(m);mix=equilibrium['mixture']
    weights={n:.85*mix[n]+.15/len(members) for n in members}
    write(stage/'mixture.json',equilibrium|{'training_weights':weights})
    spec=stage/'field.txt';roster_fields(spec,members,dict.fromkeys(members,'game'),weights)
    family=('mlp','recurrent','hybrid')[(round_id-1)%3]
    origin={'mlp':members['reference'],'recurrent':members['anti-hybrid'],'hybrid':members['v1-hybrid']}[family]
    warm={'neural-origin':origin}
    for t in TRACKS[:3]:
        warm[t+'-origin-best']=max((members[f's{i:02d}-{t}'] for i in MILESTONES),key=lambda fp:
            equilibrium['responses'][f's{fp.parent.name[-2:]}-{t}'])
    for src in previous[-3:]:
        data=json.loads((src/'response.json').read_text())
        for t in TRACKS:
            if t!='neural' or family==data['family']:warm[t+'-'+src.name]=src/(t+'.net')
    roster(stage/'warm.txt',warm)
    subprocess.run(java('Ladder','response',stage/'checkpoints',700+round_id,64,32,spec,stage/'warm.txt',frames,feedback,*calendars('training_clocks')),check=True)
    candidates={p.stem:p for p in (stage/'checkpoints').glob('*.net')}
    arms=dict.fromkeys(('wtap','hold'))|candidates
    reward=payoff(grid(stage,'responses',arms,members,calendars('training_clocks')))
    ranks=sorted((dict(name=n,weighted=sum(weights[o]*reward[n,o] for o in members),
                       mean=sum(reward[n,o] for o in members)/len(members)) for n in arms),key=lambda r:(-r['weighted'],-r['mean'],r['name']))
    picked={t:next(r['name'] for r in ranks if r['name'].startswith(t+'-')) for t in TRACKS}
    picked['explicit']=next(r['name'] for r in ranks if r['name'].split('-')[0] in TRACKS[:3])
    for t,n in picked.items():shutil.copyfile(candidates[n],stage/(t+'.net'))
    additions={f'q{round_id:02d}-{t}':stage/(t+'.net') for t in ('neural','explicit')}
    updated=dict(m)
    for n in additions:
        checkpoint=picked[n.split('-')[1]]
        for other in members:updated[n,other]=reward[checkpoint,other];updated[other,n]=1.-reward[checkpoint,other]
    square=payoff(grid(stage,'new-crossplay',dict.fromkeys(('wtap','hold'))|additions,dict.fromkeys(('wtap','hold'))|additions,calendars('training_clocks')))
    for pair,v in square.items():
        if pair in updated and updated[pair]!=v:raise ValueError('Reverse seat payoff diverged')
        updated[pair]=v
    roster(stage/'choices.txt',additions)
    subprocess.run(java('Ladder','misses',stage/'choices.txt',spec,stage/'lost-states.txt',*calendars('training_clocks')[:4]),check=True)
    result=dict(family=family,picks=picked,ranks=ranks)
    write(stage/'response.json',result)
    publish_round(stage,result,'game')
    print(stage.name,'equilibrium gap',round(equilibrium['gap'],5),'responses',picked['explicit'],picked['neural'],flush=True)
    return stage,members|additions,updated

def train(out,frames,freeze):
    compile_model();out.mkdir(parents=True)
    members=field(out);clocks=calendars('training_clocks');m=payoff(grid(out,'initial',members,members,clocks))
    past=[];feedback=out/'no-prior-losses'
    for i in range(1,7):
        stage,members,m=response(out,i,members,m,frames,past,feedback);past.append(stage);feedback=stage/'lost-states.txt'
    write(out/'final-game.json',update_game(m))
    challengers={n:p for n,p in members.items() if n.startswith('q')};controls={n:members[n] for n in ('wtap','hold','reference','v1-hybrid','control-trained')}
    ev=payoff(grid(out,'development',controls|challengers,members,calendars('development_selection_clocks')))
    rank=sorted((evaluate(ev,n,members) for n in controls|challengers),key=lambda r:(-r['merit'],-r['mean'],r['name']))
    freeze.mkdir(parents=True)
    selection={}
    for t in ('neural','explicit'):
        n=next(r['name'] for r in rank if r['name'] in challengers and r['name'].endswith(t));dest=freeze/(t+'.net')
        shutil.copyfile(challengers[n],dest);selection[t]={'name':n,'sha256':hashlib.sha256(dest.read_bytes()).hexdigest()}
    write(freeze/'selection.json',dict(chosen=selection,ranking=rank))
    print('Frozen pure responses',selection,flush=True)

def study(out,clocks):
    from novel_field import target
    lib=HERE/'empirical-game-frozen';decision=json.loads((lib/'selection.json').read_text())
    winner,wp=target(HERE/'ladder-frozen');new={NEURAL:lib/'neural.net',EXPLICIT:lib/'explicit.net'}
    for n,p in new.items():
        if hashlib.sha256(p.read_bytes()).hexdigest()!=decision['chosen'][p.stem]['sha256']:raise ValueError('Response changed')
    probes={n:HERE/'novel-frozen'/(n+'.net') for n in ('probe-hit-select','probe-targeted')}
    arms={WT:None,HOLD:None,winner:wp}|new;opponents={WT:None,HOLD:None,winner:wp}|probes
    if clocks:
        compile_model();out.mkdir(parents=True)
        tapes=sorted(clocks.glob('*.txt.gz'))
        if len(tapes)!=8:raise ValueError('Eight prospective mirrors required')
        def aliases(field):return {{WT:'wtap',HOLD:'hold'}.get(k,k):p for k,p in field.items()}
        grid(out,'regulation',aliases(arms),aliases(opponents),tapes,mode='mouse')
    else:
        native_install(new|probes);run_batch(plan('egame',list(arms),list(opponents),3),out)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);sub=p.add_subparsers(dest='cmd',required=True)
    t=sub.add_parser('train');t.add_argument('out',type=Path);t.add_argument('frames',type=Path);t.add_argument('freeze',type=Path)
    s=sub.add_parser('study');s.add_argument('out',type=Path);s.add_argument('--clocks',type=Path)
    a=p.parse_args()
    if a.cmd=='train':train(a.out,a.frames,a.freeze)
    else:study(a.out,a.clocks)
