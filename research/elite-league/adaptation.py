"""Build an identity-blind switching field and train robust, repeatedly adapting fighters."""
import argparse
from collections import defaultdict
import gzip
import hashlib
import json
from pathlib import Path
import random
import shutil
import subprocess
import champions
from ladder_transfer import WT,HOLD,plan,native_install
from mechanism import encode,unpack
from run import ROOT,HERE,calendars,compile_model,java
from tournament import grid,roster
from native_trials import run_batch

TYPES=('MLP','RECURRENT','HYBRID','TACTIC','LINEAR')
BLOCK=8*(22+1+8)+5*(8+1)
GATE=1+22+2*7
BASELINES={'wtap':WT,'hold':HOLD,'pressure':'sumo-rl-pressure','trade':'trade-wtap',
           'zest-strafe':'research-zest-strafe','wtap-tick-window':'wtap-tick-window',
           'phase-spacing':'phase-spacing','kb-displace-pull':'kb-displace-pull','kb-displace-edge':'kb-displace-edge'}
ROOTS={'pressure':HERE/'challenge-frozen/anti-hold.net','recoil':HERE/'challenge-frozen/anti-hybrid.net',
       'table':HERE/'empirical-game-frozen/explicit.net','neural':HERE/'empirical-game-frozen/neural.net',
       'rule':HERE/'ladder-frozen/elite-common-rule.net','search':HERE/'ladder-frozen/elite-common-search.net',
       'opening':HERE/'robust-frozen/population.net','hybrid':HERE/'frozen/hybrid.net'}
TRAIN=(('pressure','recoil',1,2,0),('table','neural',0,22,15),('search','pressure',2,4,0),
       ('rule','opening',0,17,11),('recoil','table',2,1,7),('opening','hybrid',1,3,0),
       ('hybrid','search',2,1,12),('neural','rule',0,30,0))
TEST=(('recoil','pressure',1,5,0),('search','table',0,20,9),('opening','rule',2,0,0),('neural','hybrid',1,1,13))
EXPERT_PAIRS=(('opening','table'),('neural','pressure'),('table','recoil'),('rule','neural'),('pressure','search'),('hybrid','table'))
if {x[:2] for x in TRAIN}&{x[:2] for x in TEST}:raise ValueError('Switching pairs overlap')

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def read(path):return json.loads(path.read_text())
def freeze(path,data):
    if path.exists() and path.read_bytes()!=data:raise ValueError('Frozen publication differs: '+str(path))
    if not path.exists():
        with path.open('xb') as f:f.write(data)
def dump(path,data):freeze(path,(json.dumps(data,indent=2)+'\n').encode())

def pair_model(a,b,mode,trigger,delay,repeat,seed):
    header=[mode,0,0,trigger,delay,repeat,4,.15]
    rng=random.Random(seed)
    weights=[rng.uniform(-.5,.5) for _ in range(GATE)] if mode==2 else [0]*GATE
    for i,p in enumerate((a,b)):
        family,w=unpack(p.read_text());header[i+1]=TYPES.index(family)
        if len(w)>BLOCK:raise ValueError('Expert exceeds reserved block')
        weights.extend(w+[0]*(BLOCK-len(w)))
    return (encode('PAIR',header+weights)+'\n').encode()

def properties(p):
    return dict(l.split('=',1) for l in p.read_text().splitlines() if '=' in l and not l.startswith('#'))

def prepare(out):
    out.mkdir(exist_ok=False,parents=True);nets=out/'nets';nets.mkdir()
    inventory={};groups=defaultdict(list);sources={}
    # Freeze every existing name before any held-out style plays the entrants.
    for p in sorted((ROOT/'policies').glob('*.properties')):
        d=properties(p);note='legacy native policy'
        if p.stem in BASELINES.values():note='shared controller adapter'
        if d.get('duel_network') and d.get('duel_network_landmark','0')=='0':
            if d['kind']!='hold' or d.get('click_mode')!='fixed':raise ValueError('Network needs explicit input adapter '+p.stem)
            data=(d['duel_network']+'\n').encode();h=hashlib.sha256(data).hexdigest()
            groups[h].append(p.stem);sources[h]=data;note='shared network'
        elif d.get('duel_network'):note='boxing landmark specialist'
        inventory[p.stem]=dict(config=sha(p),kind=d['kind'],coverage=note,stage=d.get('league_stage'))
    field={k:dict(native=v,aliases=[v]) for k,v in BASELINES.items()}
    for h,names in sorted(groups.items()):
        n=next((x for x in names if x.startswith('league-')),names[0])
        p=nets/(n+'.net');freeze(p,sources[h]);field[n]=dict(native=n,aliases=names,net=p.name,sha256=h)
    for n,p in ROOTS.items():freeze(nets/('base-'+n+'.net'),p.read_bytes())
    for scope,styles in [('train',TRAIN),('sealed',TEST)]:
        for i,(a,b,trigger,delay,repeat) in enumerate(styles,1):
            n=f'shift-{scope}-{i:02d}';p=nets/(n+'.net')
            freeze(p,pair_model(ROOTS[a],ROOTS[b],1,trigger,delay,repeat,0))
            field[n]=dict(native=n,aliases=[n],net=p.name,sha256=sha(p),scope=scope,experts=[a,b],trigger=trigger,delay=delay,repeat=repeat)
    for i,(a,b) in enumerate(EXPERT_PAIRS):
        freeze(nets/f'warm-pair-{i}.net',pair_model(ROOTS[a],ROOTS[b],2,0,0,0,62177+i))
    dump(out/'field.json',field);dump(out/'policy-index.json',inventory)

def fields(folder,scope):
    d=read(folder/'field.json')
    return {n:(folder/'nets'/v['net'] if 'net' in v else None) for n,v in d.items()
            if v.get('scope')!='sealed' or scope=='all'}

def ranking(path):
    cells=defaultdict(lambda:defaultdict(list));switches=defaultdict(int)
    with gzip.open(path,'rt') as f:
        for r in map(json.loads,f):
            cells[r['arm']][r['opponent']].append(r['score'])
            switches[r['arm']]+=r.get('our_switches',0)
    result=[]
    for n,vs in cells.items():
        rates={o:sum(v)/len(v) for o,v in vs.items()};ordered=sorted(rates,key=lambda o:(rates[o],o))
        mean=sum(rates.values())/len(rates);tail=sum(rates[o] for o in ordered[:5])/min(5,len(ordered))
        result.append(dict(name=n,merit=(mean+tail+rates[ordered[0]])/3,mean=mean,tail=tail,
                           worst=rates[ordered[0]],weakest=ordered[:5],switches=switches[n],opponents=rates))
    return sorted(result,key=lambda r:(-r['merit'],-r['mean'],r['name']))

def train(folder,out):
    compile_model();out.mkdir(exist_ok=True,parents=True)
    fixed=fields(folder,'training');warm={p.stem:p for p in sorted((folder/'nets').glob('warm-pair-*.net'))}
    warm['recoil']=ROOTS['recoil'];warm['old-recurrent']=HERE/'frozen/recurrent.net'
    past={};controls={'wtap':None,'hold':None,'reference':ROOTS['opening'],'explicit-reference':ROOTS['table'],'neural-reference':ROOTS['neural']}
    for i in range(1,5):
        stage=out/f'round-{i:02d}';stage.mkdir(exist_ok=True)
        if not (stage/'result.json').exists():
            roster(stage/'field.txt',fixed|past);roster(stage/'warm.txt',warm)
            subprocess.run(java('AdaptationFit',stage/'checkpoints',171000+23*i,stage/'field.txt',stage/'warm.txt',*calendars('training_clocks')),check=True)
            pool={p.stem:p for p in sorted((stage/'checkpoints').glob('*.net'))}
            arms=controls|past|pool
            table=grid(stage,'development',arms,fixed|past,calendars('development_selection_clocks'),mode='mouse')
            ranked=ranking(table);best=ranked[0]['name'];picks={}
            for t in ('pair','recurrent'):
                n=next(r['name'] for r in ranked if r['name'].startswith(t+'-g'));picks[t]=n
                shutil.copyfile(pool[n],stage/(t+'.net'))
            source=arms[best] or ROOT/'policies'/(BASELINES[best]+'.properties')
            alias=f'adapt-{i:02d}'
            champions.install(alias,source,f'Adaptive tournament {i} winner ({best})',alias)
            for t in picks:champions.install(alias+'-'+t,stage/(t+'.net'),f'Adaptive tournament {i} {t} winner',alias,t)
            dump(stage/'result.json',dict(winner=best,sha256=sha(source),picks=picks,ranking=ranked))
        for t in ('pair','recurrent'):
            p=stage/(t+'.net');past[f'adapt-{i:02d}-{t}']=p
            warm['prior-'+t]=p
        print('Completed adaptive tournament',i,read(stage/'result.json')['winner'],flush=True)
    final=out/'common';final.mkdir()
    table=grid(final,'development',controls|past,fixed|past,calendars('development_selection_clocks'),mode='mouse')
    evidence=HERE/'evidence/adaptive-development';evidence.mkdir(exist_ok=True,parents=True)
    freeze(evidence/'common.jsonl.gz',table.read_bytes())
    ranked=ranking(table);chosen=next(r for r in ranked if r['name'] in past)
    dest=HERE/'adaptive-frozen';dest.mkdir(exist_ok=True)
    freeze(dest/'challenger.net',past[chosen['name']].read_bytes())
    overall=ranked[0]['name'];source=(controls|past)[overall] or ROOT/'policies'/(BASELINES[overall]+'.properties')
    champions.install('league-adaptive',source,'Four-round adaptive league development winner','league-adaptive')
    dump(dest/'selection.json',dict(challenger=chosen['name'],overall=overall,sha256=sha(dest/'challenger.net'),ranking=ranked))
    for stage in sorted(out.glob('round-*')):
        target=dest/stage.name;target.mkdir(exist_ok=True)
        for n in ('pair.net','recurrent.net','result.json'):freeze(target/n,(stage/n).read_bytes())
        freeze(target/'training.jsonl.gz',gzip.compress((stage/'checkpoints/training.jsonl').read_bytes(),mtime=0))
        freeze(target/'development.jsonl.gz',(stage/'development.jsonl.gz').read_bytes())

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);sub=p.add_subparsers(dest='cmd',required=True)
    sub.add_parser('prepare').add_argument('folder',type=Path)
    t=sub.add_parser('train');t.add_argument('folder',type=Path);t.add_argument('out',type=Path)
    a=p.parse_args()
    if a.cmd=='prepare':prepare(a.folder)
    else:train(a.folder,a.out)
