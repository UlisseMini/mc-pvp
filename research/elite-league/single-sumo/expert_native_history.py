"""Audit the finished, matched native cells for the already-played dedicated combat policies."""
import sys

from expert_population import STUDY
from learner import EVIDENCE, ROOT, write_json
from native_league import WT, certify
from remote_league import read

DEST=STUDY/'archived-native-controls'
SOURCES={'seven':('seven/native-65536','idlab-cd5443f19665','sumo-seven-first',4),
         'incumbent':('incumbent/recorded-input-times/native','idlab-c1bc00a1e5f6','v4-ppo-g0496',3)}


def register():
    cells=[]
    for group,(location,expert,target,count) in SOURCES.items():
        folder=EVIDENCE/'long-specialists'/location
        attempt=read(folder/'attempt-0.json')
        bridge=read(folder/'protocol.json')['bridge']
        relevant={q:t for q,t in attempt['plan'].items() if t['block']<count and t['arm'] in (WT,expert) and t['opponent'] in (WT,target)}
        if len(relevant)!=4*count:
            raise ValueError('Incomplete matched design')
        for q,t in relevant.items():
            mid=attempt['matches'][q]
            if int(mid[1:])>10032:raise ValueError('Later replay outside the registered historical cutoff')
            cells.append(dict(group=group,mid=mid,trial=t,bridge=bridge,request=q))
    write_json(DEST/'fixtures.json',dict(cells=cells,design='Retrospective first fully acquired matched blocks up to m10032 of the two already running native campaigns for the exact same frozen button policies. Excludes other maps, other checkpoints and incomplete blocks. Audits do not launch duels or change original campaigns. These matches test the fixed experts, not the rematch router.'),fixed=True)


def run():
    cells=read(DEST/'fixtures.json')['cells']
    records=[]
    for v in cells:
        f=DEST/'audits'/(v['mid']+'.json')
        if not f.exists():
            accepted=certify(v['mid'],v['trial'],v['bridge'])
            write_json(f,accepted,fixed=True)
        audit=read(f)
        t=v['trial']
        records.append(dict(group=v['group'],mid=v['mid'],block=t['block'],side=t['side'],own=t['rtt'],
            other=t['opponent_rtt'],arm=t['arm'],foe=t['opponent'],valid=audit['valid'],score=audit.get('score'),reason=audit.get('reason')))
    scores={}
    def tally(cases):
        return [sum(r['valid'] and r['score']==z for r in cases) for z in (1,.5,0)]
    for group,(_,expert,target,count) in SOURCES.items():
        for foe in (target,WT):
            for arm in (expert,WT):
                matches=[r for r in records if (r['group'],r['arm'],r['foe'])==(group,arm,foe)]
                scores[f'{group}/{arm}/{foe}']=dict(wdl=tally(matches),invalid=sum(not r['valid'] for r in matches),
                    seats={s:tally([r for r in matches if r['side']==s]) for s in 'ab'},
                    ping={f"{r['own']}/{r['other']}":tally([v for v in matches if (v['own'],v['other'])==(r['own'],r['other'])]) for r in matches})
    write_json(DEST/'results.json',dict(scores=scores,ledger=records),fixed=True)


def recognize():
    import hashlib
    import numpy as np
    import torch
    from series_public import observed
    from specialist_native import motion_features
    from specialist_router import Decoder, visible_feedback
    from series_targeted import DEST as LIB
    from specialist_diagnosis import DEST as ORIGINAL

    spec=read(LIB/'protocol.json')
    foes=list(spec['field'])
    experts=list(spec['experts'])
    dedicated=read(ORIGINAL/'protocol.json')['dedicated']
    records=read(DEST/'results.json')['ledger']
    models={}
    for mode in ('motion','feedback'):
        folder=ORIGINAL/'models'/f'{mode}-5213-n8'
        pt=folder/'actor.pt'
        if hashlib.sha256(pt.read_bytes()).hexdigest()!=read(folder/'fit.json')['sha256']:
            raise ValueError('Opponent observer differs from locked weights')
        net=Decoder(len(experts),len(foes),mode=='motion')
        net.load_state_dict(torch.load(pt,weights_only=True))
        models[mode]=net.eval()
    scored=[];unavailable=[]
    for v in records:
        if not v['valid'] or v['arm']==WT:continue
        rival='sumo-seven-first' if v['group']=='seven' else 'v4-ppo-g0496'
        true='wtap' if v['foe']==WT else rival
        act=experts.index(dedicated[rival])
        try:
            info=observed(v['mid'],v['side'])
        except ValueError as e:
            unavailable.append(dict(mid=v['mid'],reason=str(e)))
            continue
        if (info['own_ping'],info['opponent_ping'],info['score'])!=(v['own'],v['other'],v['score']):
            raise ValueError('The observer input disagrees with the official native fixture')
        signals=motion_features(v['mid'],v['side'])
        public=visible_feedback(np.asarray(info['score']),np.asarray(info['displayed_duration_ms']),np.array([v['own'],v['other']]))
        token=torch.from_numpy(np.concatenate((signals,public,np.eye(len(experts),dtype=np.float32)[act]))[None])
        outcome=dict(mid=v['mid'],expert=experts[act],true=true,available_pair=[rival,'wtap'])
        with torch.inference_mode():
            for mode,model in models.items():
                logit=model(token)[0]
                if not torch.isfinite(logit).all():
                    raise ValueError('Non-finite native opponent probabilities')
                outcome[mode]=dict(prediction=foes[int(logit.argmax())],
                    among_two=max((rival,'wtap'),key=lambda f:float(logit[foes.index(f)])))
        scored.append(outcome)
    write_json(DEST/'perception.json',dict(ledger=scored,unavailable=unavailable,recognized={
        mode:{choice:sum(v[mode][choice]==v['true'] for v in scored) for choice in ('prediction','among_two')} for mode in models},
        scope='Retrospective cross-domain diagnostic from a single completed native bout. Every official seven-CPS matched block plays each evaluated expert against its own target and against W-tap. Open 27-way recognition uses only its selected expert, own observed motion, displayed pings, duration and result. Restricted two-way answers are diagnostic only. These counts measure identification and supply no native adaptive win rate.'),fixed=True)


if __name__=='__main__':
    if sys.argv[1:] == ['register']:register()
    elif sys.argv[1:] == ['perception']:recognize()
    else:run()
