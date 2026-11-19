"""Generate reproducible human-derived sparring opponents, and test equal-budget challenge/control arms."""
import argparse
import hashlib
import itertools
import json
import random
import shutil
import subprocess
from pathlib import Path
from run import HERE, ROOT, calendars, compile_model, java
from mechanism import unpack,encode
from tournament import grid,read
from native_trials import run_batch
from robust_select import field
from challenge_native import install

PROXY = HERE/'human-frozen/clone.net'
REFERENCE = HERE/'robust-frozen/champion.net'
PROTOCOL = HERE/'human-sparring-protocol.json'


def samples(folder, which):
    seeds=json.loads(PROTOCOL.read_text())['random_streams'][which]
    family, w=unpack(PROXY.read_text())
    assert family=='IMITATION' and len(w)==570
    models={}
    for s,hard in itertools.product(seeds,(False,True)):
        weights=w[:-2]+[s,int(hard)]
        name=f'human-{"pressure" if hard else "paced"}-{s}'
        path=folder/(name+'.net')
        data=encode(family,weights)+'\n'
        if path.exists() and path.read_text()!=data:raise ValueError('Different frozen human opponent '+name)
        path.write_text(data);models[name]=path
    return models


def controls():
    return {'wtap':None,'hold':None,'v1-hybrid':HERE/'frozen/hybrid.net','reference':REFERENCE,
            'anti-hybrid':field()['anti-hybrid'],'anti-hold':field()['anti-hold']}


def screen(out):
    out.mkdir(exist_ok=False,parents=True)
    proxies=samples(out,'selection')
    grid(out,'proxy',controls(),proxies,calendars('development_selection_clocks'))


def fit(out):
    for family, warm, seed in (('mlp',REFERENCE,52187),('mlp',REFERENCE,71453),
                               ('recurrent',field()['anti-hybrid'],66347)):
        for with_human in ((True,False) if family=='mlp' else (True,)):
            name=f'{family}-{"human" if with_human else "control"}-{seed}'
            subprocess.run(java('HumanChallenge',out/name,warm,PROXY,int(with_human),seed,192,48,
                *calendars('training_clocks')),cwd=ROOT,check=True)


def bucket(n):
    if n.startswith('human-'):return n.rsplit('-',1)[0]
    if n in ('reference','anti-hybrid','anti-hold','v1-hybrid'):return 'learned'
    if n in ('wtap','hold','pressure'):return 'basic'
    return 'timing'


def ranking(rows):
    scores={}
    for r in rows:
        data=scores.setdefault(r['arm'],{});data.setdefault(bucket(r['opponent']),[]).append(r['score'])
    ranks=[]
    for name,data in scores.items():
        groups={g:sum(v)/len(v) for g,v in data.items()};avg=sum(groups.values())/len(groups)
        ranks.append(dict(name=name,groups=groups,merit=.6*avg+.4*min(groups.values()),mean=avg))
    return sorted(ranks,key=lambda r:(-r['merit'],-r['mean'],r['name']))


def select(fit_folder,out):
    out.mkdir(exist_ok=False,parents=True)
    b=controls()
    opponents=samples(out,'selection')|b|{n:field()[n] for n in ('pressure','select','reset','zest-strafe')}
    pool={p.parent.name+'-'+p.stem:p for p in sorted(fit_folder.glob('*/*.net'))}
    if len(pool)!=125:raise ValueError(f'Expected 125 checkpoints, got {len(pool)}')
    rows=read(grid(out,'selection',b|pool,opponents,calendars('development_selection_clocks')))
    ranked=ranking(rows);names={}
    for name in ('human','control'):
        names[name]=next(r['name'] for r in ranked if '-'+name+'-' in r['name'])
        shutil.copyfile(pool[names[name]],out/(name+'.net'))
    per_seed={}
    for seed in (52187,71453):
        for treatment in ('human','control'):
            prefix=f'mlp-{treatment}-{seed}-'
            per_seed[prefix]=next(r for r in ranked if r['name'].startswith(prefix))
    details=dict(names=names, paired_mlp_seed_selection=per_seed, ranking=ranked, bouts=len(rows), sha256={k:hashlib.sha256((out/(k+'.net')).read_bytes()).hexdigest() for k in names})
    (out/'selection.json').write_text(json.dumps(details,indent=2)+'\n')


def validate(selected,clocks,out):
    out.mkdir(exist_ok=False,parents=True)
    arms=controls()|{'human-trained':selected/'human.net','control-trained':selected/'control.net'}
    grid(out,'sumo',arms,samples(out,'prospective')|controls()|{'zest-strafe':None,'wtap-tick-window':None},clocks)


def live(selected,out):
    work=out.parent/'native-proxies';work.mkdir(exist_ok=True,parents=True)
    proxies=samples(work,'prospective')
    ids=json.loads(PROTOCOL.read_text())['random_streams']['prospective'][:2]
    proxies={k:v for k,v in proxies.items() if int(k.rsplit('-',1)[1]) in ids}
    install(proxies|{'elite-human-trained':selected/'human.net','elite-control-trained':selected/'control.net'})
    arms=('research-wtap20','research-hold20','elite-population','elite-human-trained','elite-control-trained')
    opps=(*proxies,'research-hold20','research-wtap20','elite-br-hybrid','elite-population')
    rows=[]
    for arm,opp,n,rtt,side in itertools.product(arms,opps,range(3),(0,80),'ab'):
        req=f'eh1-{arm}-{opp}-{n}-{rtt}-{side}';a,b=(arm,opp) if side=='a' else (opp,arm)
        rows.append((req,dict(mode='sumo',profile='mmc',arm=arm,opponent=opp,rep=n,rtt=rtt,side=side,
                    command=f'lab sumo {a} {b} {rtt} {rtt} {req} preset=mmc reach=3 cps=20 tapdelay=0 aimdelay=0 aimnoise=0 aimrate=0')))
    random.Random(59651).shuffle(rows);run_batch(dict(rows),out)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('mode',choices=('screen','fit','select','validate','native'))
    p.add_argument('out',type=Path);p.add_argument('--source',type=Path);p.add_argument('--clocks',type=Path)
    a=p.parse_args()
    if a.mode!='native':compile_model()
    if a.mode=='screen':screen(a.out)
    elif a.mode=='fit':fit(a.out)
    elif a.mode=='select':select(a.source,a.out)
    elif a.mode=='validate':validate(a.source,sorted(a.clocks.glob('*.txt.gz')),a.out)
    else:live(a.source,a.out)
