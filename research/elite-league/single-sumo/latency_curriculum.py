"""Matched continuation testing whether practicing an adverse RTT pairing improves a frozen best response."""
import fcntl
import hashlib
import json
import random
import subprocess
import sys
import time
from statistics import mean, stdev

from id_evaluate import make_panel
from id_native import native_name
from learner import CLOCKS,HERE,ROOT,Worker,atomic,freeze,training_schedule,write_json
from league_tournament import stats
from long_specialist import RUN,sources
from native_league import WT,fair_cohort,fixtures
from train import fit

BASE=32768
END=49152
SEED=2819453
PARENT=RUN/'g1'
OUT=PARENT/'latency-curriculum'

def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def read(p):return json.loads(p.read_text())

def schedule(cfg,weights,step):
    rows=training_schedule(cfg,weights,step)
    rng=random.Random(SEED+1033*step)
    for i in rng.sample(range(len(rows)),len(rows)//2):
        actor,foe,clock,seat,_,_,own_seed,other_seed=rows[i]
        own=rng.choice([r for r in range(85,151) if r!=109])
        other=rng.choice([r for r in range(56) if r!=31])
        rows[i]=(actor,foe,clock,seat,own,other,own_seed,other_seed)
    return rows

def register():
    _,_,_,target=sources('g1')
    state=PARENT/'milestones/n32768.pt'
    write_json(OUT/'protocol.json',dict(target=target,cap=7,training_floor=137,seed=SEED,start_budget=BASE,end_budget=END,
        optimizer_sha256=digest(state),actor_sha256=digest(PARENT/'n32768.net'),control='same G1 optimizer and 16384 additional PPO duels on the original uniform RTT schedule',
        intervention='in exactly 32 of each 64 fixtures replace the two sampled pings with uniform own 85-150 ms vs opponent 0-55 ms. Exclude 109 and 31 ms. All other fixture fields, PPO updates and action seeds are matched.',
        trigger='in the independent 32k timing panel the 109/31 cell won 82/192 for G1. These previously exposed clocks are development for this new intervention and are excluded from its confirmation.',
        gates='compare final 49152 checkpoints without early selection. Outright wins against the same target at 109/31 are primary; report overall and other cells, seats, base 32k and plain W-tap. Separate native repetitions at all eight seat/RTT cells.',
        native='four actors against the G1 winner and plain W-tap in eight seat and RTT blocks; 64 duels on the original 137-block floor',
        maps='no gradients, selection, or scoring on any named map'),fixed=True)
    freeze(OUT/'config.json',(PARENT/'config.json').read_bytes())
    if not (OUT/'trainer.pt').exists():atomic(OUT/'trainer.pt',state.read_bytes())

def train(until):
    register()
    fit(OUT,until//64,schedule=schedule)
    if until==END:freeze(OUT/f'n{END}.net',(OUT/'current.net').read_bytes())

def actors():return {'curriculum':OUT/f'n{END}.net','uniform':PARENT/f'n{END}.net','start':PARENT/f'n{BASE}.net','wtap':'wtap'}

def report():
    from scipy.stats import t
    labels=('start','uniform','curriculum','wtap')
    data={a:read(OUT/'model'/a/'target.json') for a in labels}
    def cell(r):return '/'.join(str(r[s]) for s in ('wins','draws','losses'))
    lines=['# Does extra adverse-ping practice help against the first league winner?','',
           'Both continuations start with the same 32k MLP and Adam optimizer. Each plays 16,384 additional seven-CPS training games. The curriculum changes only the ping assignment in half of them. Counts are outright wins/draws/losses in the simulator.','',
           '| Own / opponent RTT | 32k start | 49k uniform | 49k extra adverse pings | Plain W-tap |',
           '| --- | --- | --- | --- | --- |']
    for rtt in ('all','31/31','109/109','31/109','109/31'):
        row=[cell(data[a]['aggregate'] if rtt=='all' else data[a]['aggregate']['rtt'][rtt]) for a in labels]
        lines.append('| '+' | '.join([rtt,*row])+' |')
    by_donor={}
    for key,target_rtt in [('overall',None),('109/31',[109,31])]:
        bins={}
        for a,b in zip(data['curriculum']['games'],data['uniform']['games'],strict=True):
            if a[:5]!=b[:5]:raise ValueError('Different comparison fixtures')
            if target_rtt is None or a[3:5]==target_rtt:
                bins.setdefault(a[0],[]).append(int(a[5]==1)-int(b[5]==1))
        donors=list(map(mean,bins.values()));delta=mean(donors)
        h=float(t.ppf(.975,len(donors)-1))*stdev(donors)/len(donors)**.5
        by_donor[key]=dict(delta=delta,donor_deltas=donors,interval=[delta-h,delta+h])
        lines.extend(['',f"The curriculum changed outright wins {key} by {100*delta:+.1f} percentage points (approximate paired 95% interval {100*(delta-h):+.1f} to {100*(delta+h):+.1f})."])
    write_json(OUT/'model/paired.json',by_donor,fixed=True)
    lines.extend(['','## Main Minecraft server',''])
    native_out=OUT/'native/summary.json'
    if native_out.is_file():
        lines.extend(['| Opponent | 32k start | 49k uniform | 49k extra adverse pings | Plain W-tap |',
                      '| --- | --- | --- | --- | --- |'])
        for opponent,group in read(native_out).items():
            name='W-tap' if opponent==WT else 'sumo-league-001'
            lines.append('| '+' | '.join([name,*[cell(group[a]['all']) for a in labels]])+' |')
    else:lines.append('The 64 matched native games are pending.')
    lines.extend(['','[Prespecified comparison](protocol.json) · [Per-opponent, seat and RTT scores](model)'])
    atomic(OUT/'README.md',('\n'.join(lines)+'\n').encode())

def model():
    _,_,_,target=sources('g1')
    cells=make_panel(CLOCKS['dev'],8)
    w=Worker()
    try:
        for name,actor in actors().items():
            for tag,foe in [('target',target),('wtap','wtap')]:
                dst=OUT/'model'/name/(tag+'.json')
                if dst.exists():continue
                plan=[[actor,foe,c,s,a,b,x,y] for _,_,s,a,b,c,x,y in cells]
                raw=[v for j in range(0,len(plan),32) for v in w.run(plan[j:j+32],False)]
                rows=[[i,r,s,a,b,v[0],v[1]] for (i,r,s,a,b,_,_,_),v in zip(cells,raw,strict=True)]
                if name in ('start','wtap'):
                    baseline=PARENT/'development'/('32768' if name=='start' else 'wtap')/(tag+'.json')
                    if rows!=read(baseline)['games']:raise ValueError('Unchanged comparator did not reproduce its native-clock fixture')
                write_json(dst,dict(aggregate=stats(rows),games=rows),fixed=True)
    finally:w.close()
    report()

def native():
    _,_,_,target=sources('g1')
    names={k:native_name(p) for k,p in actors().items()}
    foe=native_name(ROOT/target)
    plan=fixtures('g1-rtt-49k',list(names.values()),[foe,WT],8,7)
    # Use the same archived main release and Forge binary as the 7-CPS map panel.
    sys.path.insert(0,str(ROOT/'research/mmc-arena'))
    from validation import assessment_bridge
    bridge,stamp=assessment_bridge()
    folder=OUT/'native'
    rows=fair_cohort(folder,plan,bridge,server=stamp['server'])
    def count(qs):
        v=[rows[q]['score'] for q in qs]
        return dict(wins=v.count(1),draws=v.count(.5),losses=v.count(0))
    totals={}
    for opponent in (foe,WT):
        totals[opponent]={}
        for name,arm in names.items():
            qs=[q for q,t in plan.items() if t['opponent']==opponent and t['arm']==arm]
            totals[opponent][name]=dict(all=count(qs),seat={s:count([q for q in qs if plan[q]['side']==s]) for s in 'ab'},
                 rtt={f'{a}/{b}':count([q for q in qs if (plan[q]['rtt'],plan[q]['opponent_rtt'])==(a,b)]) for a,b in ((31,31),(109,109),(31,109),(109,31))})
    write_json(folder/'summary.json',totals,fixed=True)
    report()

def run():
    register()
    with (OUT/'supervisor.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        supervise()

def supervise():
    current=read(OUT/'progress.json')['duels'] if (OUT/'progress.json').is_file() else BASE
    while current<END or not (OUT/f'n{END}.net').is_file():
        subprocess.run([sys.executable,HERE.parent/'cpu_limit.py','--policy','nice','-n','8',
                        sys.executable,'-u',__file__,'train',str(min(current+1024,END))],check=True)
        current=read(OUT/'progress.json')['duels']
    while not (PARENT/f'n{END}.net').is_file():time.sleep(10)
    subprocess.run([sys.executable,HERE.parent/'cpu_limit.py','--terrain','nice','-n','8',sys.executable,
                    '-u',__file__,'model'],check=True)
    native()

if __name__=='__main__':
    if len(sys.argv)==1:run()
    elif sys.argv[1]=='train':train(int(sys.argv[2]))
    elif sys.argv[1]=='model':model()
    elif sys.argv[1]=='report':report()
