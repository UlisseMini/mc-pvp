"""Play the eleven-expert, ring-out-trained rematch router on the main Minecraft server."""
import hashlib
import math
import random
import sys
import time

import numpy as np
import torch

from expanded_crossplay import FOLDER
from expanded_reward import DEST as FIT, SEED, load
from expert_population import Actor
from learner import ROOT, RTTS, write_json
from remote_league import read
from series_eval import tally
from series_public import messages, observed
from series_targeted import DEST as LIB

DEST = FOLDER/'native-nine-targets'
PARENT = FOLDER.parent/'native'


def native_rows(observations):
    from series_trace_native import local_features
    rows = []
    previous = None
    for o in observations:
        x,y,z,tx,ty,tz = o[:6]
        old_x,old_y,old_z = previous if previous is not None else (tx,ty,tz)
        length = max(math.hypot(tx-x,tz-z),1e-8)
        fx,fz = (tx-x)/length,(tz-z)/length
        dx,dz = tx-old_x,tz-old_z
        target = np.clip([2.5*(dx*fx+dz*fz),2.5*(dx*fz-dz*fx),2*(ty-old_y)],-1,1).astype(np.float32)
        rows.append(np.concatenate((local_features(o),target)))
        previous = tx,ty,tz
    return np.stack(rows)


def opening(mid, seat):
    sys.path.insert(0,str(ROOT/'research/sumo-rl'))
    import captures
    file, = (ROOT/'runtime/pov'/mid).glob(seat+'-client-*-events.jsonl.gz')
    end = next(ts for ts,_,line in messages(mid,seat) if line.startswith('Finished '+mid+' [sumo]'))
    # Nonempty short bouts use the simulator's repeat-last padding rule.
    raw = []
    for event in captures.events(file,{'duel_network'}):
        if event.get('match')==mid and event['time']<=end:
            raw.append(event['observation'])
            if len(raw)==48:
                break
    features = native_rows(raw)
    groups = features[np.minimum(np.arange(48),len(features)-1)].reshape(8,6,12)
    return np.concatenate((groups.mean(1),groups.std(1)),1).reshape(1,192)


def tables():
    train,c,_,_,arms = load(9,'train')
    p = c[...,1:].numpy()>=70
    bins = 2*p[...,0]+p[...,1]
    scores = train.numpy()
    counts = np.stack([np.stack([(scores[bins==b]==s).sum(0)+1 for s in (0,.5,1)],-1) for b in range(4)])
    win = (counts/counts.sum(-1,keepdims=True))[...,2]
    write_json(DEST/'lookup.json',dict(arms=arms,field=list(read(LIB/'protocol.json')['field']),
        blind=win.mean(1).argmax(-1).tolist(),identity=win.argmax(-1).tolist()),fixed=True)


def register():
    weights = FIT/'covered-09'/f'seed-{SEED}'/'u2048.pt'
    h=hashlib.sha256(weights.read_bytes()).hexdigest()
    if h!=read(FOLDER/'future-nine-targets/assay.json')['models']['9']:
        raise ValueError('Unrecognized reward router weights')
    protocol=read(PARENT/'design.json')
    write_json(DEST/'protocol.json',dict(router=h,opponents=protocol['foes'],repeats=4,source=protocol['server'],
        schedule='Use each of the twelve precommitted game-one generalist matches in the existing native observer study. After its public finish, precommit the learned second-game choice for every advertised ping pair from this participant’s own first 48 decisions, public result and both pings. Compare the same frozen brain with memory erased, a training-fit ping lookup and a privileged ID-and-ping lookup. Share identical actual controllers in the same cell. Score plain W-tap using the original study’s already registered game-two W-tap cells on the same arena, hardware, settings, seats, RTTs and acquisition protocol. Native games are independent repeats. All outcomes and byte-level control audits are retained.'),fixed=True)


@torch.inference_mode()
def run():
    from matched_native import live
    from native_league import fair_cohort,fixture
    sys.path.insert(0,str(ROOT/'research/mmc-arena'))
    from validation import assessment_bridge
    register()
    protocol=read(DEST/'protocol.json')
    field=read(LIB/'protocol.json')
    lut=read(DEST/'lookup.json')
    arms=lut['arms']
    registry=field['experts'].copy()
    for key in arms:
        if key not in registry:
            registry[key]=read(FOLDER/'counters'/(key.removeprefix('dedicated-')+'.json'))['policy']
    if set(lut['field'])!=set(field['field']):
        raise ValueError('Changed lookup population')
    net=Actor(len(arms),False)
    net.load_state_dict(torch.load(FIT/'covered-09'/f'seed-{SEED}'/'u2048.pt',weights_only=True))
    net.eval()
    opener=arms.index('original-mixture')
    parent=PARENT/'g0/ledger.json'
    while not parent.exists():
        time.sleep(20)
    first=[v for v in read(parent) if v['role']=='adaptive' and v['game']==1]
    if len(first)!=12 or len({(v['opponent'],v['series']) for v in first})!=12:
        raise ValueError('Incomplete opener cohort')
    bridge,stamp=assessment_bridge()
    if stamp['server']!=protocol['source']:
        raise ValueError('Native server binary differs from declared comparison')
    entries=[];plan={};commit={}
    for v in first:
        f,r,mid,seat = v['opponent'],v['series'],v['mid'],v['seat']
        j = protocol['opponents'].index(f)
        raw=read(ROOT/'runtime/results'/(mid+'.json'))
        public=observed(mid,seat)
        p0,q0=public['own_ping'],public['opponent_ping']
        if (p0,q0,public['score'])!=(v['own'],v['other'],v['score']) or public['countdown_received_ms']>raw['start']:
            raise ValueError('Unexpected public result or pings: '+mid)
        if raw['policy_'+seat]!=live(registry[arms[opener]]):
            raise ValueError('Unmatched opening policy: '+mid)
        obs=np.concatenate((opening(mid,seat),np.eye(3,dtype=np.float32)[[int(2*v['score'])]],
                            np.array([[0,p0/150,q0/150]],np.float32)),axis=1)
        token=net.event(torch.from_numpy(obs),torch.tensor([opener]),len(arms))
        h=net.memory(net.encoder(token)[:,None])[1][0]
        routes={}
        for p,q in RTTS:
            pair=torch.tensor([[p,q]],dtype=torch.float32)
            k=2*int(p>=70)+int(q>=70)
            routes[f'{p}/{q}']=dict(learned=int(net.readout(h,pair)[0].argmax()),
                cleared=int(net.readout(torch.zeros_like(h),pair)[0].argmax()),
                ping=lut['blind'][k],identity=lut['identity'][k][lut['field'].index(f)])
        commit[f'{j}-{r}']=dict(first=mid,routes=routes)
        p,q=RTTS[(r+1+j)%4]
        side='ab'[(r+1+j//2)%2]
        assigned={}
        for role,arm in routes[f'{p}/{q}'].items():
            key=f'expanded-r9-s1-f{j}-r{r}-a{arm}'
            assigned[role]=key
            if key in plan:
                continue
            trial=fixture.trial(key,live(registry[arms[arm]]),live(field['field'][f]),side,p,q)
            trial.update(cps_cap=7,command=trial['command'].replace(' cps=20 ',' cps=7 '),block=r)
            plan[key]=trial
        entries.append(dict(opponent=f,series=r,seat=side,own=p,other=q,assigned=assigned))
    write_json(DEST/'decisions.json',commit,fixed=True)
    keys=list(plan)
    random.Random(772081).shuffle(keys)
    actual=fair_cohort(DEST/'rematches',{k:plan[k] for k in keys},bridge,server=stamp['server'])
    reference=PARENT/'g1/ledger.json'
    while not reference.exists():
        time.sleep(20)
    wtap={(v['opponent'],v['series']):v for v in read(reference) if v['game']==2 and v['role']=='wtap'}
    results={}
    for cell in entries:
        f,r=cell['opponent'],cell['series']
        ref=wtap[f,r]
        if any(ref[k]!=cell[k] for k in ('seat','own','other')):
            raise ValueError('Unmatched native W-tap fixture')
        cell['outcomes']={'wtap':dict(mid=ref['mid'],score=ref['score'])}
        for role,key in cell['assigned'].items():
            match=actual[key]
            public=observed(match['mid'],cell['seat'])
            if (public['own_ping'],public['opponent_ping'],public['score'])!=(cell['own'],cell['other'],match['score']):
                raise ValueError('Audited rematch disagrees with participant POV')
            cell['outcomes'][role]=dict(mid=match['mid'],score=match['score'])
        for role,outcome in cell['outcomes'].items():
            results.setdefault(role,[]).append(outcome['score'])
    def summarize(values):
        w=np.asarray(values)
        return dict(games=tally(w),foes={f:tally(w[[e['opponent']==f for e in entries]]) for f in protocol['opponents']},
            seats={s:tally(w[[e['seat']==s for e in entries]]) for s in 'ab'},
            rtts={f'{p}/{q}':tally(w[[(e['own'],e['other'])==(p,q) for e in entries]]) for p,q in RTTS})
    write_json(DEST/'results.json',dict(scores={k:summarize(v) for k,v in results.items()},ledger=entries),fixed=True)


if __name__=='__main__':
    if sys.argv[1]=='tables':
        tables()
    elif sys.argv[1]=='register':
        register()
    else:
        run()
