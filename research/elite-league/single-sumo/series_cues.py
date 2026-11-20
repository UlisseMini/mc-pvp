"""Explain the frozen cross-match learner's second move by separating a public result from a public duration."""
import sys
import time

import numpy as np
import torch

from assay import lower_contrast
from learner import write_json
from remote_league import read
from series_bank import DEV_REPS,FOLDER,ROUNDS
from series_eval import load_models,matches,tally
from series_hidden import donor_rows,field_confirmation
from series_learn import bank,features


@torch.no_grad()
def study(task,split):
    src=FOLDER if task=='field' else FOLDER/'archive-reversal';root=src/'hidden-score'
    while not (root/split/'public-controls.json').exists():time.sleep(10)
    if task=='field':loader=field_confirmation() if split=='fresh' else bank
    else:
        from series_archive import load as loader
    data,c,wt=loader(split,DEV_REPS);nc,nr,_,nf,na,_=data.shape
    w=data.permute(0,1,3,2,4,5).reshape(-1,ROUNDS,na,2).clone()
    w[...,1]=100*torch.floor((w[...,1]+50)/100)
    ctx=c[:,:,None].expand(nc,nr,nf,ROUNDS,3).reshape(-1,ROUNDS,3)
    foes=np.tile(np.arange(nf),nc*nr);tapes=np.repeat(np.arange(nc),nr*nf);idx=np.arange(len(foes))
    same,wrong=donor_rows(c,nf);nets=load_models(2,9,na,nf,root)
    original=matches(nets,w,ctx,foes,tapes);history=original['history'][:,:1]
    zero=torch.zeros(len(foes));controls=torch.stack([features(zero,g,ctx[:,g,1:]) for g in (0,1)],1)
    if not np.array_equal(original['choices'][:,0],original['choices'][wrong,0]):raise ValueError('First expert differed')
    options={'both-real':(idx,idx),'other-result':(wrong,idx),'other-duration':(idx,wrong),'both-other':(wrong,wrong),'both-same-opponent':(same,same)}
    actions={};won={}
    for label,(score_from,time_from) in options.items():
        h=history.clone();h[:,:,na:na+3]=history[score_from,:,na:na+3];h[:,:,na+3]=history[time_from,:,na+3]
        a=torch.stack([m(h,controls)[0][:,-1] for m in nets]).mean(0).argmax(1).numpy()
        actions[label]=a;won[label]=w[idx,1,a,0].numpy()
    if not np.array_equal(actions['both-real'],original['choices'][:,1]) or not np.array_equal(actions['both-other'],original['choices'][wrong,1]):
        raise ValueError('Cue reconstruction changed the unmodified action')
    won['wtap']=wt.permute(0,1,3,2).reshape(-1,ROUNDS)[:,1].numpy()
    scores=original['won'][:,0];dur=w[idx,0,original['choices'][:,0],1].numpy()
    subsets={'all':np.ones(len(foes),bool),'same-result-different-duration':(scores==scores[wrong])&(dur!=dur[wrong]),
             'different-result-same-duration':(scores!=scores[wrong])&(dur==dur[wrong]),'both-cues-different':(scores!=scores[wrong])&(dur!=dur[wrong])}
    results={}
    experts=list(read(src/'protocol.json')['experts']);names=list(read(src/'protocol.json')['field'])
    for name,mask in subsets.items():
        results[name]=dict(opportunities=int(mask.sum()),wins_draws_losses={n:tally(v[mask]) for n,v in won.items()},
            changed_expert={n:int((v[mask]!=actions['both-real'][mask]).sum()) for n,v in actions.items()})
    effects={}
    for label in ('other-result','other-duration','both-other','both-same-opponent'):
        d=((won['both-real']==1).astype(float)-(won[label]==1)).reshape(nc,-1).mean(1)
        effects[label]=dict(real_minus_treatment=float(d.mean()),adjusted_bounds=lower_contrast(d,.05/8))
    observed=[]
    for j,f in enumerate(names):
        for outcome in (0,.5,1.):
            mask=(foes==j)&(scores==outcome)
            if not mask.any():continue
            observed.append(dict(opponent=f,first_score=outcome,n=int(mask.sum()),median_duration_ms=float(np.median(dur[mask])),
                second_experts={arm:int((actions['both-real'][mask]==i).sum()) for i,arm in enumerate(experts)}))
    write_json(root/split/'second-game-cues.json',dict(second_games=len(foes),results=results,contrasts=effects,observed=observed,
        interpretation='Single-cue hybrids measure the frozen network’s sensitivity. Hybrids can be impossible in play, such as a short draw. The no-edit, paired donor and equal-cue subsets use real first-game feedback.'),fixed=True)


if __name__=='__main__':study(sys.argv[1],sys.argv[2])
