"""Use only the preceding native bouts and public ping to select the next legal MLP."""
import hashlib
import random
import sys

import numpy as np
import torch
from scipy.special import softmax

from learner import ROOT, RTTS, write_json
from matched_native import live
from native_league import fair_cohort, fixture
from remote_import import wait_for
from remote_league import OUT, read
from series_public import messages, observed
from series_targeted import DEST as LIB, bank
from series_trace_native import local_features
from specialist_diagnosis import DEST, TRAIN_REPS, signal
from specialist_router import Decoder, payoffs, visible_feedback


def motion_features(mid, side):
    sys.path.insert(0,str(ROOT/'research/sumo-rl'))
    import captures
    file,=(ROOT/'runtime/pov'/mid).glob(side+'-client-*-events.jsonl.gz')
    end=next(ts for ts,_,line in messages(mid,side) if line.startswith('Finished '+mid+' [sumo]'))
    rows=[local_features(e['observation']) for e in captures.events(file,{'duel_network'}) if e.get('match')==mid and e['time']<=end]
    return signal(np.stack(rows))


def run():
    sys.path.insert(0,str(ROOT/'research/mmc-arena'))
    from validation import assessment_bridge
    bridge,version=assessment_bridge()
    spec=read(LIB/'protocol.json');experts=list(spec['experts']);field=list(spec['field'])
    targets=['wtap','reply-to-g1','sumo-seven-first'];arms=['adaptive','ping-only','dedicated','wtap']
    opener=experts.index('original-mixture');na=len(experts);nf=len(field)
    src=DEST/'models/motion-5213-n8';dest=DEST/'native';pairs=read(DEST/'protocol.json')['dedicated']
    train,c,_=bank('train',TRAIN_REPS);table=payoffs(train.numpy(),c.numpy(),True)
    write_json(dest/'design.json',dict(actors=arms,foes=targets,training_seed=5213,budget=8,
        rules='Four regulation bouts per opponent, four independent series rotating both seats and all four RTT pairs. Compare a recurrent motion observer, a past-blind ping lookup, the dedicated counter from spawn and plain W-tap. Use the broad opener for both routers. Dedicated uses the broad bot when the target is W-tap. Report true native results independently from the simulator. Only adaptive receives its own game history. An independent native repeat shares the fixture specification, not all state.',
        server=version['server'],combat_experts=spec['experts']),fixed=True)
    posterior={(r,f):np.zeros(nf) for r in range(4) for f in targets}
    ledger=[]
    for g in range(4):
        plan={}; conditions={};pre={}
        if g==1:
            prefix=str(src.relative_to(OUT))+'/'
            wait_for([prefix+'actor.pt',prefix+'fit.json'])
            if hashlib.sha256((src/'actor.pt').read_bytes()).hexdigest()!=read(src/'fit.json')['sha256']:
                raise ValueError('Native observer changed')
            m=Decoder(na,nf,True);m.load_state_dict(torch.load(src/'actor.pt',weights_only=True));m.eval()
        for j,f in enumerate(targets):
            for r in range(4):
                own,other=RTTS[(r+g+j)%4];side='ab'[(r+g+j//2)%2]
                for arm in (['shared','dedicated','wtap'] if not g else arms):
                    key=f'specobs-s1-f{j}-r{r}-g{g}-{arm}'
                    if arm=='wtap':index=-1; actor=live(spec['field']['wtap'])
                    else:
                        index=opener
                        if arm=='dedicated':index=experts.index(pairs.get(f,'original-mixture'))
                        if arm in ('adaptive','ping-only'):
                            belief=softmax(posterior[r,f]) if arm=='adaptive' else np.full(nf,1/nf)
                            choices={f'{a}/{b}':int((belief@table[2*int(a>=70)+int(b>=70)]).argmax()) for a,b in RTTS}
                            pre[key]=choices;index=choices[f'{own}/{other}']
                        actor=live(spec['experts'][experts[index]])
                    v=fixture.trial(key,actor,live(spec['field'][f]),side,own,other)
                    v.update(cps_cap=7,command=v['command'].replace(' cps=20 ',' cps=7 '),block=r)
                    plan[key]=v;conditions[key]=r,f,arm,side,index,own,other
        write_json(dest/f'g{g}/pre.json',pre,fixed=True)
        keys=list(plan);random.Random(49217+g).shuffle(keys)
        results=fair_cohort(dest/f'g{g}',{k:plan[k] for k in keys},bridge,server=version['server'])
        if g==0:
            prefix=str(src.relative_to(OUT))+'/'
            wait_for([prefix+'actor.pt',prefix+'fit.json'])
            m=Decoder(na,nf,True);m.load_state_dict(torch.load(src/'actor.pt',weights_only=True));m.eval()
        for k,record in results.items():
            r,f,arm,side,a,own,other=conditions[k];mid=record['mid'];public=observed(mid,side)
            result=read(ROOT/'runtime/results'/(mid+'.json'))
            if (public['own_ping'],public['opponent_ping'])!=(own,other) or public['countdown_received_ms']>result['start'] or public['score']!=record['score']:
                raise ValueError('Pre-duel ping or result mismatch '+mid)
            if arm in ('shared','adaptive'):
                fb=visible_feedback(np.asarray(public['score']),np.asarray(public['displayed_duration_ms']),np.asarray([own,other]))
                x=np.concatenate((motion_features(mid,side),fb,np.eye(na,dtype=np.float32)[a]))
                with torch.inference_mode(): posterior[r,f]+=m(torch.from_numpy(x[None])).log_softmax(1)[0].numpy()
            for mode in (['adaptive','ping-only'] if arm=='shared' else [arm]):
                ledger.append(dict(mid=mid,series=r,game=g+1,opponent=f,role=mode,seat=side,own=own,other=other,score=public['score']))
        write_json(dest/f'g{g}/ledger.json',ledger,fixed=True)
    from series_eval import tally
    scores={a:{f:tally(np.asarray([v['score'] for v in ledger if v['role']==a and v['opponent']==f])) for f in targets} for a in arms}
    write_json(dest/'results.json',dict(scores=scores,ledger=ledger),fixed=True)


if __name__=='__main__':run()
