"""Freeze season finalists, run the native selection tournament, and confirm the chosen entry independently."""
import argparse
import hashlib
import itertools
import json
import random
import shutil
from pathlib import Path

from challenge_native import install
from human_sparring import samples
from ladder import MILESTONES
from native_trials import run_batch
from run import HERE, ROOT, compile_model
from tournament import grid

WT='research-wtap20'
HOLD='research-hold20'
REFERENCE='elite-population'
SELECTION=(WT,HOLD,'elite-br-hybrid',REFERENCE,'kb-displace-edge')
CONFIRMATION=(WT,HOLD,'elite-br-hybrid','elite-br-hold','elite-m08-search','human-pressure-151121','elite-m12-neural')
BUILTINS={WT:'wtap',HOLD:'hold','kb-displace-edge':'kb-displace-edge'}

def read(p):return json.loads(p.read_text())
def dump(p,v):
    with p.open('x') as f:json.dump(v,f,indent=2);f.write('\n')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()

def freeze(source,summary,out):
    out.mkdir(parents=True,exist_ok=False)
    models={
        REFERENCE:HERE/'robust-frozen/population.net','elite-v1-hybrid':HERE/'frozen/hybrid.net',
        'elite-human-trained':HERE/'human-challenge-frozen/human.net',
        'elite-control-trained':HERE/'human-challenge-frozen/control.net',
        'elite-first-hurt':HERE/'router-frozen/router.net'}
    pool={}
    for i in MILESTONES:
        for t in ('explicit','neural'):
            pool[f'elite-m{i:02d}-{t}']=source/f'season-{i:02d}'/(t+'.net')
    for i in (3,8):pool[f'elite-r{i:02d}-table']=HERE/f'ladder-refinements/round-{i}/table.net'
    for t in ('explicit','neural'):pool['elite-common-'+t]=summary/(t+'.net')
    field={'elite-m08-search':source/'season-08/search.net','elite-br-hybrid':HERE/'challenge-frozen/anti-hybrid.net',
           'elite-br-hold':HERE/'challenge-frozen/anti-hold.net'}
    field.update({n:p for n,p in samples(out,'prospective').items() if n in CONFIRMATION})
    if 'human-pressure-151121' not in field:raise ValueError('Missing predeclared sparring seed')
    paths=models|pool|field
    entrants=[WT,HOLD];canonical={};copies={}
    for name,p in paths.items():
        h=sha(p)
        # All named opponents also get a stable file even if two entrants have identical weights.
        dest=out/(name+'.net')
        if dest.resolve()!=p.resolve():shutil.copyfile(p,dest)
        copies[name]=dict(sha256=h,path=dest.name)
        if name not in models and name not in pool:continue
        if h not in canonical:canonical[h]=name;entrants.append(name)
    dump(out/'frozen.json',dict(entrants=entrants,files=copies,aliases={n:canonical[x['sha256']] for n,x in copies.items() if x['sha256'] in canonical},selection=SELECTION,confirmation=CONFIRMATION,
        milestones={str(i):read(source/f'season-{i:02d}/results.json')['selected'] for i in MILESTONES},
        ranked_on_development=read(summary/'finalists.json')['picks']))

def models(folder):
    d=read(folder/'frozen.json');paths={}
    for n,v in d['files'].items():
        p=folder/v['path']
        if sha(p)!=v['sha256']:raise ValueError(f'Frozen fighter {n} has changed')
        paths[n]=p
    return d,paths

def native_install(paths):
    # Existing aliases are immutable even when their descriptive text differs.
    missing={}
    for n,p in paths.items():
        f=ROOT/'policies'/(n+'.properties')
        if not f.exists():missing[n]=p;continue
        lines=f.read_text().splitlines()
        if 'duel_network='+p.read_text().strip() not in lines or 'duel_network_landmark=0' not in lines:
            raise ValueError('Native weights differ: '+n)
    install(missing)

def plan(tag,arms,opps,repeats):
    trials=[]
    for arm,opp,rep,rtt,side in itertools.product(arms,opps,range(repeats),(0,80),'ab'):
        req=f'{tag}-{arm}-{opp}-{rep}-{rtt}-{side}';a,b=(arm,opp) if side=='a' else (opp,arm)
        trials.append((req,dict(mode='sumo',profile='mmc',arm=arm,opponent=opp,rep=rep,rtt=rtt,side=side,
               command=f'lab sumo {a} {b} {rtt} {rtt} {req} preset=mmc reach=3 cps=20 tapdelay=0 aimdelay=0 aimnoise=0 aimrate=0')))
    random.Random(719311 if tag=='elad' else 983041).shuffle(trials)
    return dict(trials)

def study(folder,out,clocks,decision):
    d,paths=models(folder)
    arms=d['entrants'] if decision is None else list(dict.fromkeys([WT,HOLD,REFERENCE,read(decision)['winner']]))
    opps=SELECTION if decision is None else CONFIRMATION
    if clocks:
        compile_model();out.mkdir(exist_ok=False,parents=True)
        def sub(names):return {BUILTINS.get(n,n):paths.get(n) for n in names}
        tapes=sorted(clocks.glob('*.txt.gz'))
        if len(tapes)!=8:raise ValueError('Expected eight prospective native donor clocks')
        grid(out,'regulation',sub(arms),sub(opps),tapes)
    else:
        native_install(paths);run_batch(plan('elad' if decision is None else 'efin',arms,opps,2 if decision is None else 6),out)

def choose(folder,manifest,out):
    d,paths=models(folder);data=read(manifest)
    if data['plan']!=plan('elad',d['entrants'],SELECTION,2) or data['plan'].keys()!=data['matches'].keys():
        raise ValueError('Incomplete or different native selection')
    records={n:{o:[] for o in SELECTION} for n in d['entrants']}
    for req,mid in data['matches'].items():
        t=data['plan'][req];r=read(ROOT/'runtime/results'/(mid+'.json'));side=t['side']
        if r['request']!=req or r['policy_'+side]!=t['arm'] or r['reason'] not in ('ring_out','double_ring_out','timeout_draw'):
            raise ValueError('Invalid regulation match '+mid)
        records[t['arm']][t['opponent']].append(.5 if not r['winner'] else int(r['winner']==r[side]))
    ranking=[]
    for n,vs in records.items():
        cells={o:sum(a)/len(a) for o,a in vs.items()}
        groups=[cells[WT],cells[HOLD],(cells['elite-br-hybrid']+cells[REFERENCE])/2,cells['kb-displace-edge']]
        mean=sum(groups)/len(groups)
        ranking.append(dict(name=n,merit=.6*mean+.4*min(groups),mean=mean,worst=min(groups),cells=cells))
    ranking.sort(key=lambda r:(-r['merit'],-r['mean'],r['name']))
    winner=ranking[0]['name']
    dump(out,dict(winner=winner,sha256=sha(paths[winner]) if winner in paths else sha(ROOT/'policies'/(winner+'.properties')),
                  native_selection_sha256=sha(manifest),ranking=ranking))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);s=p.add_subparsers(dest='cmd',required=True)
    f=s.add_parser('freeze');f.add_argument('source',type=Path);f.add_argument('summary',type=Path);f.add_argument('out',type=Path)
    g=s.add_parser('study');g.add_argument('folder',type=Path);g.add_argument('out',type=Path);g.add_argument('--clocks',type=Path);g.add_argument('--decision',type=Path)
    h=s.add_parser('choose');h.add_argument('folder',type=Path);h.add_argument('manifest',type=Path);h.add_argument('out',type=Path)
    a=p.parse_args()
    if a.cmd=='freeze':freeze(a.source,a.summary,a.out)
    elif a.cmd=='study':study(a.folder,a.out,a.clocks,a.decision)
    else:choose(a.folder,a.manifest,a.out)
