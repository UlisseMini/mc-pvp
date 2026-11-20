"""Run the frozen ring-out-only three-expert router on a live anonymous rematch."""
import hashlib
import random
import sys

import numpy as np
import torch

from expert_population import Actor, STUDY, SEED
from learner import ROOT,RTTS,write_json
from matched_native import live
from native_league import fair_cohort,fixture
from remote_import import wait_for
from remote_league import OUT,read
from series_eval import tally
from series_public import observed
from series_targeted import DEST as LIB
from specialist_native import motion_features
from specialist_router import visible_feedback


def run():
    parent=STUDY/'small'/f'seed-{SEED}'/'memory';rel=str(parent.relative_to(OUT))+'/'
    wait_for([rel+'u512.pt',rel+'u512.json'])
    digest=hashlib.sha256((parent/'u512.pt').read_bytes()).hexdigest()
    if digest!=read(parent/'u512.json')['sha256']:raise ValueError('Native router changed')
    spec=read(STUDY/'protocol.json');library=read(LIB/'protocol.json')
    names=spec['small'];arms=[spec['pairs'][f] for f in names];roles=('memory','reset','dedicated','generalist','wtap')
    foes=names+['wtap'];na=len(arms);brain=Actor(na,False);brain.load_state_dict(torch.load(parent/'u512.pt',weights_only=True));brain.eval()
    actors={a:live(v) for a,v in library['experts'].items()};actors['wtap']=live(library['field']['wtap'])
    sys.path.insert(0,str(ROOT/'research/mmc-arena'));from validation import assessment_bridge
    bridge,stamp=assessment_bridge();dest=STUDY/'native-rematch'
    write_json(dest/'protocol.json',dict(router=digest,expert_order=arms,opponents=foes,replicates=4,
        rule='Play the actual greedy router opening. Before the second fight, read only the participant’s observed motion, previous physical controls and public finish message. Compare intact memory, cleared memory, the target-specific known-ID expert, the unrestricted generalist and matched plain W-tap. For W-tap the generalist is the known-ID reference. When two arms commit to identical physical players in the same cell, share that repeat. Precommit the legal arms for all public RTT pairs. Native repeats in distinct arms are not full-state forks.'),fixed=True)
    memory={};scores={};ledger=[]
    with torch.inference_mode():
        for g in (0,1):
            plan={};commits={};assign={};ctx={}
            for r in range(4):
                for j,foe in enumerate(foes):
                    own,other=RTTS[(r+g+j)%4];side='ab'[(r+j+g)%2]
                    for role in (roles if g else ['memory']):
                        if role in ('memory','reset'):
                            h=memory[r,foe] if g and role=='memory' else torch.zeros(1,48)
                            table={f'{p}/{q}':int(brain.readout(h,torch.tensor([[p,q]],dtype=torch.float32))[0].argmax()) for p,q in RTTS}
                            a=table[f'{own}/{other}'];arm=arms[a];commits[f'{r}-{j}-{role}']=table
                        else:arm=spec['pairs'].get(foe,'original-mixture') if role=='dedicated' else 'original-mixture' if role=='generalist' else 'wtap'
                        key=f'exp-router-22819-g{g}-r{r}-f{j}-a{list(actors).index(arm)}'
                        assign[r,foe,role]=key;ctx[key]=r,j,side,own,other,arm
                        if key in plan:continue
                        v=fixture.trial(key,actors[arm],live(library['field'][foe]),side,own,other)
                        v.update(cps_cap=7,command=v['command'].replace(' cps=20 ',' cps=7 '),block=r);plan[key]=v
            write_json(dest/f'g{g}/decisions.json',commits,fixed=True)
            order=list(plan);random.Random(85993+g).shuffle(order)
            games=fair_cohort(dest/f'g{g}',{k:plan[k] for k in order},bridge,server=stamp['server'])
            for k,game in games.items():
                r,j,side,own,other,arm=ctx[k];mid=game['mid'];view=observed(mid,side);foe=foes[j]
                if view['score']!=game['score'] or (view['own_ping'],view['opponent_ping'])!=(own,other) or view['countdown_received_ms']>read(ROOT/'runtime/results'/(mid+'.json'))['start']:raise ValueError('Own-view validation failed for '+mid)
                if not g:
                    visible=motion_features(mid,side)[360:]
                    meta=visible_feedback(np.asarray(view['score']),np.asarray(view['displayed_duration_ms']),np.asarray([own,other]))
                    event=brain.event(torch.from_numpy(np.concatenate((visible,meta))[None]),torch.tensor([arms.index(arm)]),na)
                    memory[r,foe]=brain.memory(brain.encoder(event)[:,None],torch.zeros(1,1,48))[1][0]
                scores[k]=view['score'];ledger.append(dict(mid=mid,foe=foe,r=r,game=g+1,actor=arm,side=side,own=own,other=other,score=view['score']))
            write_json(dest/f'g{g}/observations.json',ledger,fixed=True)
        result={}
        for role in roles:
            result[role]={f:tally(np.array([scores[assign[r,f,role]] for r in range(4)])) for f in foes}
    write_json(dest/'result.json',dict(rematch_scores=result,unique_native_duels=len(ledger),ledger=ledger,shared_arms={str(k):v for k,v in assign.items()}),fixed=True)


if __name__=='__main__':run()
