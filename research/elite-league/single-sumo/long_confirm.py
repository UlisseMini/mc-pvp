"""Out-of-sample confirmation of the default-map checkpoint selected by each training branch."""
import json
import hashlib
import random
import sys
import subprocess
import time
from pathlib import Path
from long_specialist import RUN,BASE,sources
from learner import RTTS,ROOT,HERE,Worker,write_json
from history_replication import FOLDER
from native_league import publish

def read(p):return json.loads(p.read_text())

def publish_improvements(key):
    """Give each new development high score its own immutable, playable seven-CPS policy."""
    folder = RUN/key
    label,opponent = {'incumbent':('frozen','the frozen best-sumo'), 'seven':('seven','Sumo Seven'),
                      'g1':('league001','league winner 001')}[key]
    best = -1
    for result in sorted((folder/'development').glob('*/comparison.json'),key=lambda p:int(p.parent.name)):
        row=read(result)
        budget=row['trained_fights']
        if row['current']['wins']<=best:continue
        best=row['current']['wins']
        if budget<=BASE:continue
        actor=folder/f'n{budget}.net'
        ref=dict(reference=str(actor.relative_to(ROOT)),sha256=hashlib.sha256(actor.read_bytes()).hexdigest())
        name=f'sumo-counter-{label}-{budget//1024}k'
        publish(name,ref,f'7 CPS, {budget}-fight specialist against {opponent}')
        write_json(folder/'public'/f'n{budget}.json',dict(name=name,weights=ref,budget=budget,
                  development=row['current'],matched_plain_wtap=row['plain_wtap'],
                  scope='specialist development high score on its fixed opponent, original training floor'),fixed=True)
def register():
    write_json(RUN/'confirmation.json',dict(seed=46197541,donors='32 nonoverlapping prospective timing calendars in recognition/payoff/replication/spaced; no specialist fitted or selected on these matches',
        arms='final frozen training-map selection, 16k control and plain W-tap; each against target and W-tap',
        cases='four fixed pairs plus four independently sampled RTT pairs in [0,150] per donor/rep, each mirrored across actor seats; eight replications per clock, 4096 games per matchup',
        score='outright wins, separate draws and losses, seat, donor and RTT group; these are simulator confirmations'),fixed=True)
def score(key,selected,clocks=None,seed=46197541,replications=8,destination=None):
    folder=RUN/key;_,_,_,target=sources(key)
    if clocks is None:
        clocks=read(FOLDER/'replication/spaced/manifest.json')['selected'];assert len(clocks)==32
    if destination is None:destination=folder/'confirmation'
    cases=[]
    for i,clock in enumerate(clocks):
        for rep in range(replications):
            rng=random.Random(seed+1009*i+23011*rep)
            pairs=RTTS+[(rng.randrange(151),rng.randrange(151)) for _ in range(4)]
            for j,(a,b) in enumerate(pairs):
                for seat in 'ab':
                    cases.append((i,rep,j,seat,a,b,clock,rng.getrandbits(60),rng.getrandbits(60)))
    w=Worker()
    try:
        for label in dict.fromkeys([str(selected),str(BASE),'wtap']):
            actor='wtap' if label=='wtap' else folder/f'n{label}.net'
            for tag,foe in [('target',target),('wtap','wtap')]:
                out=destination/label/(tag+'.json')
                if out.exists():continue
                plan=[[actor,foe,c,s,a,b,x,y] for _,_,_,s,a,b,c,x,y in cases]
                raw=[v for j in range(0,len(plan),32) for v in w.run(plan[j:j+32],False)]
                rows=[[i,r,j,s,a,b,v[0],v[1]] for (i,r,j,s,a,b,_,_,_),v in zip(cases,raw)]
                def tally(values):
                    v=[r[6] for r in values];return dict(wins=v.count(1),draws=v.count(.5),losses=v.count(0))
                write_json(out,dict(**tally(rows),seat={s:tally([r for r in rows if r[3]==s]) for s in 'ab'},
                    donor=[tally([r for r in rows if r[0]==i]) for i in range(len(clocks))],
                    rtt={**{f'{a}/{b}':tally([r for r in rows if r[2]==j]) for j,(a,b) in enumerate(RTTS)},
                         'randomized':tally([r for r in rows if r[2]>=4])},games=rows),fixed=True)
    finally:w.close()
def run():
    register();pending=set(('incumbent','seven','g1'))
    while pending:
        for key in sorted(pending):
            publish_improvements(key)
            p=RUN/key/'selection.json';manifest=FOLDER/'replication/spaced/manifest.json'
            if not p.exists() or not manifest.exists():continue
            d=read(p)
            if d['best_wins']!=d['panel_size'] and d['stale']<2:continue
            subprocess.run([sys.executable,HERE.parent/'cpu_limit.py','--terrain','nice','-n','8',sys.executable,
                            '-u',__file__,key,str(d['selected'])],check=True);pending.remove(key)
        if pending:time.sleep(10)
if __name__=='__main__':
    if len(sys.argv)==1:run()
    else:score(sys.argv[1],int(sys.argv[2]))
