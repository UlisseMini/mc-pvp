"""Does uncertainty in sampled sprint, click, or jump decisions cause the residual specialist losses?"""
import base64
import hashlib
import json
import random
import sys
import time

import numpy as np
from id_evaluate import make_panel
from learner import BASE_COUNTER,CLOCKS,EVIDENCE,HERE,ROOT,Worker,freeze,write_json
from id_native import native_name
from forge_window import cohort_window
from native_league import WT,alias,cohort,fixtures
from long_specialist import RUN,BASE,sources
from league_tournament import stats

DIR=RUN/'sampling-ablation'
# These three logits are centered at zero. The ordinal forward and strafe thresholds stay fixed.
HEADS={'original':(), 'sprint':(1,), 'click':(2,), 'jump':(3,), 'all-three':(1,2,3)}

def register():
    src,_,net,foe=sources('incumbent')
    original=(src/net).read_bytes();tag,payload=original.strip().split(b'.',1)
    assert tag==b'LEAGUE_MLP180'
    for name,which in HEADS.items():
        vals=np.frombuffer(base64.b64decode(payload),dtype='>f4').copy()
        for k in which:
            start=180*26+k*180
            vals[start:start+180]*=4;vals[180*26+6*180+k]*=4
        freeze(DIR/(name+'.net'),tag+b'.'+base64.b64encode(vals.tobytes())+b'\n')
    write_json(DIR/'protocol.json',dict(snapshot=hashlib.sha256(original).hexdigest(),target=foe,arms=HEADS,logit_multiplier=4,
         physical_effect='logistic temperature .25 to .0625 only in the selected physical-button heads; forward/back and strafe unchanged',
         source='four residual development losses; 125/128 counterfactual wins when resampling only our policy, 0/128 when resampling only the deterministic foe',
         selection='highest ring-out count on existing 512 default-map fixtures; tie favors original then fewer modified heads',
         evaluation='compare selection to original and plain W-tap on 32 temporally spaced clocks and independent action seeds, plus original matched native blocks; no validation or sealed map results used'),fixed=True)
    return foe

def score(foe,split,clocks,names):
    w=Worker()
    try:
        cells=make_panel(clocks,8 if split=='development' else 4)
        for arm in names:
            actor='wtap' if arm=='wtap' else DIR/(arm+'.net')
            for opponent in (foe,'wtap'):
                dst=DIR/split/arm/(opponent+'.json')
                if dst.exists():continue
                plan=[]
                for index,(i,rep,s,a,b,c,x,y) in enumerate(cells):
                    if split!='development':
                        rng=random.Random(83392291+index*9019);x,y=rng.getrandbits(60),rng.getrandbits(60)
                    plan.append([actor,opponent,c,s,a,b,x,y])
                raw=[r for j in range(0,len(plan),32) for r in w.run(plan[j:j+32],False)]
                vals=[[i,rep,s,a,b,r[0],r[1]] for (i,rep,s,a,b,_,_,_),r in zip(cells,raw)]
                result=stats(vals);result['clock']=[float(np.mean([r[5] for r in vals if r[0]==i])) for i in range(len(clocks))]
                write_json(dst,dict(stats=result,games=vals),fixed=True)
    finally:w.close()

def native(foe,chosen):
    arms=list(dict.fromkeys([native_name(DIR/(n+'.net')) for n in [chosen,'original']]+[WT]))
    plan=fixtures('sg-sharp16',arms,[alias(foe),WT],16,7)
    with cohort_window(DIR/'native') as bridge:r=cohort(DIR/'native',plan,bridge,block_barrier=True)
    write_json(DIR/'native/outcomes.json',[dict(arm=t['arm'],opponent=t['opponent'],seat=t['side'],own=t['rtt'],other=t['opponent_rtt'],score=r[q]['score'],mid=r[q]['mid']) for q,t in plan.items()],fixed=True)

def run():
    import subprocess
    from history_replication import FOLDER
    foe=register();protocol=FOLDER/'replication/spaced/manifest.json'
    def limited(part):
        subprocess.run([sys.executable,HERE.parent/'cpu_limit.py','--terrain','nice','-n','8',sys.executable,'-u',__file__,part],check=True)
    limited('development')
    chosen=max(HEADS,key=lambda k:(json.loads((DIR/'development'/k/(foe+'.json')).read_text())['stats']['wins'],-len(HEADS[k])))
    write_json(DIR/'nomination.json',dict(arm=chosen),fixed=True)
    # Confirm against pre-registered clocks that were absent from all specialist fitting.
    while not protocol.exists():time.sleep(10)
    limited('confirm');native(foe,chosen)

if __name__=='__main__':
    if len(sys.argv)==1:run()
    elif sys.argv[1]=='development':score(register(),'development',CLOCKS['dev'],[*HEADS,'wtap'])
    else:
        from history_replication import FOLDER
        clocks=json.loads((FOLDER/'replication/spaced/manifest.json').read_text())['selected']
        selected=json.loads((DIR/'nomination.json').read_text())['arm']
        score(register(),'confirmation',clocks,list(dict.fromkeys([selected,'original','wtap'])))
