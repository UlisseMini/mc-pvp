"""A learned opponent model with Bayesian updating from the public result of each played game."""
import io
import sys
import time

import numpy as np

from assay import lower_contrast
from learner import atomic,write_json
from remote_league import read
from series_bank import BUDGETS,DEV_REPS,FOLDER,ROUNDS
from series_eval import donors,series,tally
from series_learn import bank

DEST=FOLDER/'belief'


def register():
    write_json(DEST/'protocol.json',dict(training='Estimate result and duration likelihoods for each opponent, expert and ping bin on training tapes. Four global duration bins use training quartiles. Pool six equivalent observations toward the population for each table. Uniform initial belief. Infer the opponent only from the agent’s own chosen expert, result, elapsed playing time and displayed pings.',
        policies='Compare clearing belief each game; remembering one, three or nine games; outcome-only evidence; and one-step lookahead on nine-game memory. Lookahead values the current round and estimates how much the observation could improve the best subsequent fixed response, averaged over future pings. Both belief policies have no runtime ID. Grade the preregistered lookahead version on ten-match series.',
        tests='Compare the lookahead series win rate to the strongest fixed expert, ping-only play, and an intervention substituting the older belief while keeping the last genuine game, current pings and earlier score. Grade on the same unresampled league schedules as PPO. Use prospective timing and native series separately.'),fixed=True)


def learn(stats,ctx):
    w=stats[...,0].numpy();ms=stats[...,1].numpy();p=ctx[...,1:].numpy()>=70;bins=2*p[...,0]+p[...,1]
    edges=np.quantile(ms,[.25,.5,.75])
    symbols=(2*w).astype(int)*4+np.searchsorted(edges,ms)
    counts=np.stack([np.stack([(symbols[bins==b]==s).sum(axis=0) for s in range(12)],axis=-1) for b in range(4)]).astype(float)
    prior=counts.sum(1,keepdims=True)+1
    probs=counts+6*prior/prior.sum(-1,keepdims=True)
    probs/=probs.sum(-1,keepdims=True)
    return edges,probs


def choice(posterior,bin_now,probs,left,lookahead):
    points=probs@np.repeat([0.,.5,1.],4)
    actions=np.empty(len(posterior),int)
    for k in range(4):
        ids=np.flatnonzero(bin_now==k)
        if not len(ids):continue
        belief=posterior[ids];score=belief@points[k]
        if lookahead and left:
            # For each observation, the joint mass already includes how likely this observation is.
            joint=belief[:,None,None,:]*probs[k].transpose(1,2,0)[None]
            future=joint@points.transpose(1,0,2).reshape(belief.shape[1],-1)
            future=future.reshape(*joint.shape[:3],4,score.shape[1]).max(axis=-1).mean(axis=-1).sum(axis=2)
            score+=left*future
        actions[ids]=score.argmax(1)
    return actions


def belief_from(events,foes):
    logp=events.sum(1) if events.shape[1] else np.zeros((len(events),foes))
    p=np.exp(logp-logp.max(1,keepdims=True))
    return p/p.sum(1,keepdims=True)


def choose_public(edges,probs,history,own,other,remember):
    likelihood=[]
    for a,w,ms,p,q in history if remember else []:
        k=2*int(p>=70)+int(q>=70);symbol=int(2*w)*4+np.searchsorted(edges,ms)
        likelihood.append(np.log(probs[k,:,a,symbol]))
    events=np.asarray(likelihood).reshape(1,len(likelihood),probs.shape[1])
    posterior=belief_from(events,probs.shape[1])
    return int(choice(posterior,np.array([2*int(own>=70)+int(other>=70)]),probs,9-len(history),remember)[0])


def play(edges,probs,worlds,ctx,rivals,tapes,horizon,lookahead=False,duration=True,mode='intact',catalog=None):
    b,n,arms,_=worlds.shape;f=probs.shape[1];indices=np.arange(b)
    won=np.empty((b,n));act=np.empty((b,n),int);events=np.empty((b,n,f));points=np.zeros((b,n+1))
    prob_true=[];entropy=[];coverage=[];belief=probs.reshape(4,f,arms,3,4).sum(-1)
    for g in range(n):
        p=ctx[:,g,1:];pb=(p[:,0]>=70)*2+(p[:,1]>=70)
        history=events[:,max(0,g-horizon):g]
        if mode!='intact' and g>=2:
            same,wrong,mask=donors(g,points[:,g-1],rivals,tapes,p,catalog)
            ix=same if mode=='same' else wrong
            history=history.copy()
            history[mask,:-1]=catalog['events'][ix[mask],max(0,g-horizon):g-1]
            coverage.append(int(mask.sum()))
        posterior=belief_from(history,f)
        prob_true.append(float(posterior[indices,rivals].mean()))
        entropy.append(float(-(posterior*np.log(posterior.clip(1e-100))).sum(1).mean()))
        a=choice(posterior,pb,probs,ROUNDS-1-g,lookahead);act[:,g]=a
        score,ms=worlds[indices,g,a].T;won[:,g]=score;points[:,g+1]=points[:,g]+score
        result=(2*score).astype(int)
        if duration:likelihood=probs[pb,:,a,result*4+np.searchsorted(edges,ms)]
        else:likelihood=belief[pb,:,a,result]
        events[:,g]=np.log(likelihood)
    return dict(won=won,points=points,events=events,act=act,true_probability=prob_true,entropy=entropy,coverage=coverage)


def evaluate(budget,split):
    spec=read(FOLDER/'protocol.json');names=list(spec['field']);experts=list(spec['experts'])
    stats,ctx,_=bank('train',budget);edges,probs=learn(stats,ctx)
    model=io.BytesIO();np.savez_compressed(model,edges=edges,probabilities=probs)
    model_path=DEST/f'learn-{budget:02}.npz'
    if model_path.exists():
        with np.load(model_path) as old:
            if not np.array_equal(probs,old['probabilities']) or not np.array_equal(edges,old['edges']):raise ValueError('Belief model changed')
    else:atomic(model_path,model.getvalue())
    data,c,wt=bank(split,DEV_REPS);nc,nr,_,nf,na,_=data.shape
    worlds=data.permute(0,1,3,2,4,5).reshape(-1,ROUNDS,na,2).numpy()
    ctx=c[:,:,None].expand(nc,nr,nf,ROUNDS,3).reshape(-1,ROUNDS,3).numpy()
    rivals=np.tile(np.arange(nf),nc*nr);tapes=np.repeat(np.arange(nc),nr*nf)
    modes={'no-memory':(0,False,True),'last-game':(1,False,True),'last-three':(3,False,True),
           'greedy':(9,False,True),'outcomes-only':(9,False,False),'lookahead':(9,True,True)}
    runs={k:play(edges,probs,worlds,ctx,rivals,tapes,*args) for k,args in modes.items()}
    for mode in ('same','wrong'):
        runs[mode]=play(edges,probs,worlds,ctx,rivals,tapes,9,True,True,mode,runs['lookahead'])
    won={k:v['won'] for k,v in runs.items()}
    won.update({'wtap':wt.permute(0,1,3,2).reshape(-1,ROUNDS).numpy(),**{'fixed-'+n:worlds[:,:,i,0] for i,n in enumerate(experts)}})
    comparisons=[('lookahead',k) for k in ('no-memory','wrong',*['fixed-'+n for n in experts])]+[('same','wrong')]
    effects={}
    for a,b in comparisons:
        deltas=(series(won[a])==1).astype(float)-(series(won[b])==1)
        d=deltas.reshape(nc,-1).mean(1)
        effects[a+' minus '+b]=dict(mean=float(d.mean()),bounds=lower_contrast(d,.05/(len(comparisons)*(2 if split=='fresh' else 1))))
    scores={k:dict(series=tally(series(w)),ringouts=tally(w),by_game=[tally(g) for g in w.T],
          opponents={foe:dict(series=tally(series(w[rivals==i])),ringouts=tally(w[rivals==i])) for i,foe in enumerate(names)},
          seat={s:tally(w[ctx[...,0]==i]) for i,s in enumerate('ab')},
          rtt={f'{a}/{b}':tally(w[(ctx[...,1]==a)&(ctx[...,2]==b)]) for a in (31,109) for b in (31,109)}) for k,w in won.items()}
    report=dict(fights_per_arm=int(won['wtap'].size),series_per_arm=len(rivals),scores=scores,effects=effects,
        passed=all(v['bounds'][0]>0 for v in effects.values()),duration_thresholds_ms=edges.tolist(),
        posterior={k:{n:v[n] for n in ('true_probability','entropy','coverage')} for k,v in runs.items()},
        lookahead_choices={n:np.stack([(runs['lookahead']['act'][rivals==i]==j).sum(0) for j in range(na)]).tolist() for i,n in enumerate(names)})
    write_json(DEST/split/f'evaluation-{budget:02}.json',report,fixed=True)


def run(split):
    for budget in BUDGETS if split=='dev' else BUDGETS[-1:]:
        paths=[FOLDER/split/f'bank-{DEV_REPS:02}.json',FOLDER/'train'/f'bank-{budget:02}.json']
        while not all(p.is_file() for p in paths):time.sleep(15)
        evaluate(budget,split)


if __name__=='__main__':
    if sys.argv[1:]==['register']:register()
    else:run(sys.argv[1] if len(sys.argv)>1 else 'dev')
