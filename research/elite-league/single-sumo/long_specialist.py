"""Continue frozen single-opponent PPO runs without changing their original budget comparisons."""
import fcntl
import hashlib
import json
import subprocess
import sys
from pathlib import Path

from dedicated import DIR, budgets, _train_target
from id_evaluate import make_panel
from id_native import native_name
from learner import HERE, ROOT, EVIDENCE, BASE_COUNTER, INCUMBENT, CLOCKS, Worker, freeze, write_json, atomic
from league_tournament import stats
from native_league import fixtures, cohort, alias, WT
from forge_window import cohort_window
from train import fit

RUN=EVIDENCE/'long-specialists'
BASE=16384

def sources(key):
    first=EVIDENCE/'g001'
    if key=='g1':
        origin=first/'winner-response'
        target=json.loads((origin/'config.json').read_text())['incumbent']
        return origin,'trainer.pt','final.net',target
    target=BASE_COUNTER if key=='seven' else INCUMBENT
    origin=DIR/(('ringout-only-' if key=='seven' else 'dedicated-')+target)
    return origin,'optimizer.pt','n16384.net',target

def initialize(key):
    src,state,net,target=sources(key)
    dest=RUN/key
    dest.mkdir(parents=True,exist_ok=True)
    digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
    write_json(dest/'protocol.json',dict(original=str(src.relative_to(ROOT)),optimizer_sha=digest(src/state),
        original_net_sha=digest(src/net),target=target,baseline='plain W-tap',cap=7,training_geometry='original MMC 137 only',
        target_sha=digest(ROOT/target) if target.endswith('.net') else next(line.split('\t')[2] for line in (HERE.parent/'sumo-inputs/archive-field.tsv').read_text().splitlines() if line.split('\t')[0]==target),
        budget='continue the same Adam state and fixture sequences at 32k, 64k, then double while increasing',
        advancement='stop this optimizer branch after two consecutive doublings without a new best development ring-out win count, or a perfect 512-game development panel; confirm selected checkpoint separately',
        selection='512 fixed development bouts per matchup (eight timing donors, both seats and four paired RTTs); ring-outs primary; draws reported separately',
        native='original, selected checkpoint and plain W-tap, all at 7 CPS against target and against plain W-tap, each seat and RTT; native independent bouts'),fixed=True)
    freeze(dest/f'n{BASE}.net',(src/net).read_bytes())
    if not (dest/state).exists():
        atomic(dest/state,(src/state).read_bytes())
    if key=='g1':freeze(dest/'config.json',(src/'config.json').read_bytes())
    if not (dest/'progress.json').exists():atomic(dest/'progress.json',(src/'progress.json').read_bytes())
    return dest,target

def limited(*args,policy=False):
    subprocess.run([sys.executable,HERE.parent/'cpu_limit.py',*(['--policy'] if policy else []),'nice','-n','8',sys.executable,'-u',__file__,*map(str,args)],check=True)

def tranche(key,until):
    dest,target=initialize(key)
    if key=='g1':
        fit(dest,until//64)
        freeze(dest/f'n{until}.net',(dest/'current.net').read_bytes())
    else:
        early,matched,orig=budgets(target)
        _train_target(target,16,early,matched,orig,until,dest,key=='seven')

def evaluate(key,n):
    dest,target=initialize(key)
    cells=make_panel(CLOCKS['dev'],8)
    worker=Worker()
    try:
        for label,actor in [(str(n),dest/f'n{n}.net'),('wtap','wtap')]:
            for name,foe in [('target',target),('wtap','wtap')]:
                path=dest/'development'/label/(name+'.json')
                if path.exists():continue
                plan=[[actor,foe,clock,s,a,b,x,y] for _,_,s,a,b,clock,x,y in cells]
                rows=[]
                for j in range(0,len(plan),32):rows.extend(worker.run(plan[j:j+32],False))
                values=[[i,rep,s,a,b,r[0],r[1]] for (i,rep,s,a,b,_,_,_),r in zip(cells,rows)]
                write_json(path,dict(aggregate=stats(values),games=values),fixed=True)
    finally:worker.close()

def native(key,selected):
    dest,target=initialize(key)
    foe=native_name(ROOT/target) if target.endswith('.net') else alias(target)
    arms=list(dict.fromkeys([native_name(dest/f'n{selected}.net'),native_name(dest/f'n{BASE}.net'),WT]))
    plan=fixtures(f'long-{key}-{selected}',arms,[foe,WT],16,7)
    with cohort_window(dest/f'native-{selected}') as bridge:records=cohort(dest/f'native-{selected}',plan,bridge)
    results={}
    for opp in (foe,WT):
        results[opp]={}
        for arm in arms:
            rows=[(q,t) for q,t in plan.items() if t['opponent']==opp and t['arm']==arm]
            def count(items):
                scores=[records[q]['score'] for q,t in items]
                return dict(wins=scores.count(1),draws=scores.count(.5),losses=scores.count(0))
            results[opp][arm]=dict(**count(rows),seat={s:count([r for r in rows if r[1]['side']==s]) for s in 'ab'},
                rtt={f'{a}/{b}':count([r for r in rows if (r[1]['rtt'],r[1]['opponent_rtt'])==(a,b)]) for a,b in ((31,31),(109,109),(31,109),(109,31))})
    write_json(dest/f'native-{selected}/summary.json',results,fixed=True)

def supervise(key):
    dest,_=initialize(key)
    with (dest/'supervisor.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        best=-1;selected=BASE;stale=0;n=BASE
        while True:
            while (current:=json.loads((dest/'progress.json').read_text())['duels'])<n:
                until=min(n,current+1024) if key=='g1' else n
                limited(key,'train',until,policy=True)
            limited(key,'score',n)
            wins=json.loads((dest/'development'/str(n)/'target.json').read_text())['aggregate']['wins']
            if wins>best:best=wins;selected=n;stale=0
            else:stale+=1
            write_json(dest/'selection.json',dict(latest=n,latest_wins=wins,panel_size=512,best_wins=best,selected=selected,stale=stale))
            if stale>=2 or best==512:break
            n*=2
        native(key,selected)

if __name__=='__main__':
    key=sys.argv[1]
    if key not in ('g1','seven','incumbent'):raise ValueError(key)
    if len(sys.argv)==2:supervise(key)
    elif sys.argv[2]=='train':tranche(key,int(sys.argv[3]))
    elif sys.argv[2]=='score':evaluate(key,int(sys.argv[3]))
