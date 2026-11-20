"""Replay rare ring-outs on the training map and vary only the two actors' random streams."""
import hashlib
import random
from long_specialist import RUN,BASE,sources
from id_evaluate import make_panel
from learner import CLOCKS,Worker,write_json
from native_league import read

def study(key):
    folder=RUN/key
    data=read(folder/'development'/str(BASE)/'target.json')['games']
    cells=make_panel(CLOCKS['dev'],8)
    failures=[i for i,r in enumerate(data) if r[5]==0]
    rng=random.Random(329017);rng.shuffle(failures)
    chosen=[]
    for ix in failures[:4]:
        case=data[ix]
        controls=[i for i,row in enumerate(data) if (row[0],row[2:5],row[5])==(case[0],case[2:5],1) and i not in chosen]
        if controls:chosen.extend([ix,rng.choice(controls)])
    src,state,net,foe=sources(key)
    protocol=dict(target=foe,checkpoint=BASE,fixture_indices=chosen,draws=32,
        selection='four uniformly sampled losses with winning controls in the same timing donor, seat and RTT pair where available; spans no independent test set',
        metric='ring-out W/D/L on the full original fixture, then after changing only actor seeds, only foe seeds and both; W-tap on every continuation')
    write_json(folder/'variance/protocol.json',protocol,fixed=True)
    w=Worker()
    try:
        results=[]
        for ix in chosen:
            i,rep,s,a,b,c,x,y=cells[ix]
            def seed(k,who):return int(hashlib.sha256(f'loss-v1/{key}/{ix}/{k}/{who}'.encode()).hexdigest()[:15],16)
            for label,actor in [('learner',src/net),('wtap','wtap')]:
                for condition in ('original','self','opponent','both'):
                    plan=[[actor,foe,c,s,a,b,seed(k,'a') if condition in ('self','both') else x,
                           seed(k,'b') if condition in ('opponent','both') else y] for k in range(1 if condition=='original' else 32)]
                    values=[v[0] for v in w.run(plan,False)]
                    if condition=='original' and label=='learner' and values!=[data[ix][5]]:raise ValueError('Original rollout did not reproduce')
                    results.append(dict(fixture=ix,original=data[ix][5],donor=i,seat=s,own_rtt=a,opponent_rtt=b,arm=label,change=condition,
                                        wins=values.count(1),draws=values.count(.5),losses=values.count(0)))
    finally:w.close()
    write_json(folder/'variance/result.json',results,fixed=True)
if __name__=='__main__':
    for key in ('incumbent','g1','seven'):study(key)
