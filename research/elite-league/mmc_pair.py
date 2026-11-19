"""Learn opponent-conditional expert switching on measured-map full-state forks."""
import argparse
from collections import defaultdict
import gzip
import json
import subprocess
from pathlib import Path
import champions
import focus
from adaptation import dump,freeze,pair_model,sha
from continuation import labels
from history_cup import mirrors
from mmc_league import setup,finalists,groups,FROZEN,EVIDENCE,WORK,CURRENT,OLD_TARGET,contest
from run import HERE,ROOT,calendars,java

def expert_field():
    selection,new,final,_=finalists()
    entrants=focus.unique(focus.BASE|groups()['modern']|final)
    for i in (2,3):
        for t in ('explicit','neural'):entrants[f'mmc13-{i:02d}-{t}']=FROZEN/f'round-{i:02d}/{t}.net'
    return focus.unique({n:p for n,p in entrants.items() if p and focus.track(p) is not None})

def arrange(d,experts,foes,tapes,target):
    probe=d/'components.jsonl.gz';focus.play_model(probe,{'wtap':None}|experts,foes,tapes)
    vals=defaultdict(dict)
    with gzip.open(probe,'rt') as f:
        for r in map(json.loads,f):vals[r['arm']][(r['opponent'],r['rtt'],r['side'],r['clock'])]=r['score']
    def average(seq):return sum(seq)/len(seq)
    wkeys=[cell for cell in vals['wtap'] if cell[0]=='wtap']
    hard=[c for c in vals['wtap'] if c[0] in target]
    def rank(n):
        fixed=[average([vals[n][c] for c in wkeys if (c[1],c[2])==(t,s)]) for t in (0,80) for s in 'ab']
        return (-min(fixed),-average(fixed),-average([vals[n][c] for c in hard]),n)
    a=min(experts,key=rank)
    def complement(n):
        def delta(keys):
            return average([max(0,vals[n][c]-vals[a][c]) for c in keys])
        return (-.60*delta(hard)-.25*delta(vals[a])-.15*average([vals[n][c] for c in wkeys]),n)
    b=min((n for n in experts if n!=a),key=complement)
    dump(d/'components.json',dict(first=a,second=b,first_sha256=sha(experts[a]),second_sha256=sha(experts[b]),
        measured_common_fixtures=len(vals[a]),second_winner_from_first_losses=sum(vals[b][c]>vals[a][c] for c in vals[a]),
        first_wins=sum(vals[a].values()),second_wins=sum(vals[b].values())))
    return a,b

def train():
    selected,new,prior,hard=finalists();modern=groups()['modern']
    initial=json.loads((FROZEN/'target-selection.json').read_text())['target']
    focus.TARGET=initial
    train=calendars('training_clocks');dev=calendars('development_selection_clocks');experts=expert_field()
    allfoes=modern|{k:v for k,v in prior.items() if v is not None and k not in modern}
    evidence=[];active=None;past={};last=selected
    for stage in (4,5):
        name=f'mmc13-{stage:02d}';d=FROZEN/f'round-{stage:02d}';d.mkdir(exist_ok=True)
        tmp=WORK/name;tmp.mkdir(exist_ok=True)
        panel=list(dict.fromkeys(['wtap',*hard,initial,OLD_TARGET,CURRENT,'hold','zest-strafe','phase-spacing','kb-displace-edge']))
        foes={n:allfoes[n] for n in panel}
        if stage==4:
            a,b=arrange(d,experts,foes,train,hard)
            active=d/'starting-pair.net';freeze(active,pair_model(experts[a],experts[b],2,1,0,0,202918))
        else:
            p=FROZEN/'round-04'
            components=json.loads((p/'components.json').read_text());a,b=components['first'],components['second']
            active=p/'contender.net'
        controls=focus.unique(prior|past|{a:experts[a],b:experts[b]})
        source=tmp/'labels.tsv.gz';evidence.append(source)
        if not source.exists():labels(active,allfoes,source,730911+stage,train)
        mapping=tmp/'opponent-groups.txt';target=set(['wtap',*hard,initial])
        freeze(mapping,''.join(f'{n} {n if n in target else "mixed-field"}\n' for n in allfoes).encode())
        if not (tmp/'nets/fit-222.net').exists():
            subprocess.run(java('ContinuationFit',active,tmp/'nets','--groups',mapping,*evidence),cwd=ROOT,check=True)
        pool={p.stem:p for p in sorted((tmp/'nets').glob('*.net'))}
        narrow=d/'screen.jsonl.gz';focus.play_model(narrow,controls|pool,foes,train[::2])
        short=[r['name'] for r in focus.score(narrow,foes,True) if r['name'] in pool][:4]
        contenders=controls|{n:pool[n] for n in short}
        table=d/'development.jsonl.gz';focus.play_model(table,contenders,allfoes,dev)
        ranks=focus.score(table,allfoes);best=ranks[0]['name'];winner=best
        chosen=next(r['name'] for r in ranks if r['name'] in pool)
        freeze(d/'contender.net',pool[chosen].read_bytes())
        champions.install(name+'-pair',pool[chosen],f'Measured MMC sumo round {stage} fitted adaptive opponent selector ({chosen})',name,'adaptive')
        if best in pool:best=name+'-pair'
        champions.install(name,focus.source(winner,contenders[winner]),f'Measured MMC sumo round {stage} overall winner ({best})',name)
        if contenders[winner]:freeze(d/'winner.net',contenders[winner].read_bytes())
        weak=next(o for o in ranks[0]['weakest'] if focus.identity(o,allfoes[o])!=focus.identity(winner,contenders[winner]))
        dump(d/'selection.json',dict(winner=best,winning_checkpoint=winner,contender=chosen,weakest_distinct=weak,
            targets=sorted(target),teaching=json.loads(source.with_suffix('.json').read_text()),ranking=ranks))
        print(name,best,'weakest',weak,'fit',chosen,flush=True)
        if weak not in hard:hard=[weak,*hard]
        past[name+'-pair']=d/'contender.net';allfoes[name+'-pair']=d/'contender.net'

def confirm():
    n,new,prior,hard=finalists();d=FROZEN/'round-05';record=json.loads((d/'selection.json').read_text())
    r4=FROZEN/'round-04';candidates=focus.unique(prior|{'mmc13-04-pair':r4/'contender.net','mmc13-05-pair':d/'contender.net'})
    winner=record['winner'];which=next(r['name'] for r in record['ranking'] if r['name'].startswith('mmc13-04-pair') or r['name']==record['contender'])
    best='mmc13-05-pair' if which==record['contender'] else which
    # Each overall round alias carries its actual champion, including a component that retained the crown.
    if winner not in candidates and (d/'winner.net').exists():candidates[winner]=d/'winner.net'
    arms=focus.unique({k:candidates[k] for k in dict.fromkeys(['wtap','elite-population',n,best,winner])})
    allfoes=groups()['modern']|prior|candidates
    repeats={'wtap':12,**{o:4 for o in dict.fromkeys([hard[0],record['weakest_distinct'],'hold']) if o!='wtap'}}
    dump(FROZEN/'pairs-confirmation.json',dict(arms={a:focus.identity(a,p) for a,p in arms.items()},repeats=repeats))
    tapes=mirrors('mmc13-pairs',WORK)
    contest('pairs',arms,{n:allfoes[n] for n in repeats},repeats,tapes)
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('stage',choices=('train','confirm'));a=p.parse_args();setup()
    (train if a.stage=='train' else confirm)()
