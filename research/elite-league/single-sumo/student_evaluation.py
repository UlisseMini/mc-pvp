"""Paired regulation games for the oracle-distilled students, including opponents absent from the ID vocabulary."""
import argparse
import fcntl
import json
import random
import subprocess
import sys
import time
from pathlib import Path
from learner import BASE_COUNTER, CLOCKS, EVIDENCE, FIELD, HERE, INCUMBENT, ROOT, RTTS, Worker, np, write_json
from oracle_student import OUT, ROLES, TEACHERS, sha
from opponent_id import DIR
from id_evaluate import make_panel
from league_tournament import stats
from scipy import stats as statistics

STUDY = OUT / 'evaluation'
NEW = ('sg001-mlp','sg001-winner-response')
SPLITS = ('development','spaced')
STAGES = ('imitation','ppo')
ARMS = (*ROLES,'blind-trained','known-id','dedicated-matched','dedicated-full','wins-only','wtap')
PRIMARY = ('gru-id-loss','gru','mlp','blind-trained','known-id','wtap')

def register():
    p=STUDY/'assay.json'
    if p.exists():return json.loads(p.read_text())
    latest=max(int(f.stem[1:]) for f in (ROOT/'runtime/results').glob('m[0-9]*.json') if f.stem[1:].isdigit())
    d=dict(first_future_id=latest+12,calendar_count=32,minimum_gap_ms=60000,
           roles=list(ROLES),train_seed=920147,opponents=FIELD,transfer_opponents=list(NEW),
           stages=list(STAGES),primary='final PPO GRU with auxiliary recognition versus known-ID teacher, blind trained MLP and same GRU without the auxiliary loss',
           primary_clock_split='32 prospective nonoverlapping recordings, at least 60 seconds between games, both seats and all four RTT combinations',
           gap_targets='upper one-sided 95% bound for known-ID minus legal student below .02 field mean and .05 at Sumo Seven; report unresolved differences; familywise adjustment on the two margins',
           per_match='one point for a ring-out win and half for a regulation draw; record W/D/L, by seat and both RTTs; plain W-tap on all fixtures',
           specialists='equal target exposure and full 16,384 target bouts for the two predeclared targets; wins-only is a separate reward study',
           transfer='new learned opponents absent from the 20-way ID vocabulary; no true-ID score defined. Both have already appeared in separate league experiments.',
           native='8 native seat/RTT settings per finalist arm at each of W-tap, Sumo Seven, the old incumbent and a new winner; one per cell is exploratory, formal publications require their own full test',
           restrictions='freeze candidates and run the prospective evaluation once. Dev and prospective fixtures use separate action seeds. Human grade empty.')
    write_json(p,d,fixed=True); return d

def cases(split):
    if split=='development': return make_panel(CLOCKS['dev'],4)
    clocks=json.loads((STUDY/'calendars/manifest.json').read_text())['selected']
    out=[]
    for i,tape in enumerate(clocks):
        for side in 'ab':
            for a,b in RTTS:
                rng=random.Random(f'oracle-student-spaced-v1/{i}/{side}/{a}/{b}')
                out.append((i,0,side,a,b,tape,rng.getrandbits(60),rng.getrandbits(60)))
    return out

def opponents():
    response=json.loads((EVIDENCE/'g001/winner-response/result.json').read_text())
    champ=json.loads((EVIDENCE/'g001/nomination.json').read_text())['field']['sg001-mlp']['reference']
    return {**{f:f for f in FIELD},NEW[0]:champ,NEW[1]:response['policy']['reference']}

def models(stage,foe):
    refs={s: OUT/s/stage/('initial.net' if stage=='imitation' else 'final.net') for s in ROLES}
    refs.update({'blind-trained':DIR/'blind/u256/unknown.net','wtap':'wtap'})
    if foe in FIELD:refs['known-id']=TEACHERS/(foe+'.net')
    if foe in (INCUMBENT,BASE_COUNTER):
        p=DIR/('dedicated-'+foe)
        refs['dedicated-matched']=p/('n00820.net' if foe==INCUMBENT else 'n00819.net')
        refs['dedicated-full']=p/'n16384.net'
    if foe==BASE_COUNTER:refs['wins-only']=DIR/('ringout-only-'+foe)/'n16384.net'
    return refs

def score(stage,split,foe):
    refs=models(stage,foe);fixtures=cases(split);opp=opponents()[foe]
    folder=STUDY/stage/split;dest=folder/(foe+'.json')
    if dest.exists():return
    results={};fingerprints={}
    w=Worker()
    try:
        for role,model in refs.items():
            fingerprints[role]=sha(model) if isinstance(model,Path) else str(model)
            schedule=[[model,opp,tape,s,a,b,x,y] for _,_,s,a,b,tape,x,y in fixtures];games=[]
            for j in range(0,len(schedule),32):games.extend(w.run(schedule[j:j+32],False))
            results[role]=[list(cell[:5])+[v[0],v[1]] for cell,v in zip(fixtures,games)]
    finally:w.close()
    write_json(dest,dict(foe=foe,fixture_count=len(fixtures),hashes=fingerprints,games=results),fixed=True)

def bound(d):
    # One averaged observation per timing donor; the prospective donors have a prespecified gap.
    d=np.asarray(d);mean=float(np.mean(d));half=float(statistics.t.ppf(.975,len(d)-1)*statistics.sem(d))
    if np.ptp(d)==0:half=float(np.sqrt(2*np.log(2/.025)/len(d)))
    return dict(delta=mean,lower=mean-half,upper=mean+half)

def summarize(stage,split):
    folder=STUDY/stage/split
    cells={};blocks={}
    for foe in opponents():
        d=json.loads((folder/(foe+'.json')).read_text())['games'];cells[foe]={}
        for role,games in d.items():
            result=stats(games)
            clocks=sorted({v[0] for v in games})
            blocks[(foe,role)]=np.array([np.mean([v[5] for v in games if v[0]==c]) for c in clocks])
            result['clock']=blocks[(foe,role)].tolist();cells[foe][role]=result
    field={a:float(np.mean([cells[f][a]['score'] for f in FIELD])) for a in PRIMARY}
    contrasts={}
    for left,right in [('known-id','gru-id-loss'),('gru-id-loss','gru'),('gru-id-loss','mlp'),('gru-id-loss','blind-trained')]:
        dif={f:blocks[f,left]-blocks[f,right] for f in FIELD}
        contrasts[left+' minus '+right]={'field':bound(np.mean(list(dif.values()),axis=0)),BASE_COUNTER:bound(dif[BASE_COUNTER])}
    gap=contrasts['known-id minus gru-id-loss']
    write_json(folder/'summary.json',dict(cells=cells,field=field,paired=contrasts,
               worst={a:min(FIELD,key=lambda f:cells[f][a]['score']) for a in PRIMARY},
               attained_registered_parity=split=='spaced' and gap['field']['upper']<.02 and gap[BASE_COUNTER]['upper']<.05,
               inference='development donor recordings overlapped; use prospective donor-level inference for claims'),fixed=True)

def governed(*args):
    subprocess.run([sys.executable,HERE.parent/'cpu_limit.py','--policy','nice','-n','8',sys.executable,__file__,*map(str,args)],check=True)

def capture():
    register()
    with (STUDY/'capture.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        subprocess.run([sys.executable,HERE/'calendars_holdout.py',STUDY],check=True)

def run():
    register()
    with (STUDY/'run.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        # Donor capture runs separately. Score immutable development checkpoints while it accrues.
        for split in SPLITS:
            if split=='spaced':
                capture()
            for stage in STAGES:
                for role in ROLES:
                    if stage=='ppo':
                        p=OUT/role/stage
                        while not (p/'progress.json').exists() or json.loads((p/'progress.json').read_text())['update']<256:time.sleep(8)
                        from league_tournament import pin_model
                        pin_model(p)
                    while not models(stage,INCUMBENT)[role].is_file():time.sleep(8)
                for foe in opponents():
                    if not (STUDY/stage/split/(foe+'.json')).exists():governed('score',stage,split,foe)
                summarize(stage,split)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('op',choices=['run','score','register','capture']);p.add_argument('args',nargs='*');a=p.parse_args()
    if a.op=='run':run()
    elif a.op=='register':register()
    elif a.op=='capture':capture()
    else:score(*a.args)
