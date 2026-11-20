"""Teach the GRU what each response is worth at the actual score of a ten-game series."""
import hashlib
import io
import sys
import time

import numpy as np
import torch
from torch.nn import functional as F

from assay import lower_contrast
from learner import atomic,write_json
from remote_league import read
from series_bank import DEV_REPS,FOLDER,ROUNDS
from series_bayes import belief_from,learn
from series_eval import load_models,matches,series,tally
from series_learn import SEEDS,SeriesPolicy,bank,draw,play

DEST=FOLDER/'score-aware'


def register():
    base=read(FOLDER/'protocol.json')
    write_json(DEST/'protocol.json',dict(field=base['field'],experts=base['experts'],
        rationale='The historical blind learner won every series through draws. At 276480 full-field fights a scoreboard-only network won more series than the recurrent policy or the true-ID expected-game-points lookup. An expert that maximizes average game points can be the wrong expert at a particular series score.',
        teacher='Fit probabilities of wins, draws and losses by opponent, expert and both pings from training duels. Solve the ten-game series backwards for each known opponent, maximizing probability of winning plus half the probability of tying, with an extra 0.15 times mean game points. This is a positive affine transform of the original series reward. Marginalize future pings uniformly. No future evaluation pings or bouts enter the calculation.',
        students='Train equal-sized 64-unit GRUs and memoryless networks with equal optimizer steps to predict the training-only teacher’s action advantages using their own public observations. Draw closed-loop series under the current stochastic learner; the true opponent is only used to label the response values offline. Report the privileged teacher, population-only score-aware planning, Bayesian opponent inference and ordinary PPO alongside the learned students. Use the same 27-opponent panel and plain seven-CPS W-tap.',
        grade='At 8 training schedules fit the public models once and evaluate all 864 actual ten-game series. Perturb the recurrent history while preserving observed score and current pings. Treat development as exposed. Record the number of eligible decisions and use independent native games for transfer.'),fixed=True)


def action_values(symbols):
    """Table indexed by round, doubled score so far, public ping bin, opponent and action."""
    p=symbols.reshape(*symbols.shape[:3],3,4).sum(-1)
    _,foes,arms,_=p.shape
    q=np.zeros((ROUNDS,2*ROUNDS+1,4,foes,arms))
    scores=np.arange(2*ROUNDS+1)
    v=np.repeat(((scores>10)+.5*(scores==10))[:,None],foes,axis=1)
    earned=.15*np.arange(3)/(2*ROUNDS)
    for g in reversed(range(ROUNDS)):
        for s in range(2*g+1):
            future=v[s:s+3].T+earned
            q[g,s]=(p*future[None,:,None,:]).sum(-1)
        v=q[g,:2*g+1].max(-1).mean(1)
    return q


def fit_student(train,cues,teacher,horizon):
    seed=SEEDS[0];folder=DEST/'learn-08'/f'h{horizon}-s{seed}'
    if (folder/'trained.json').is_file():return
    torch.manual_seed(seed);rng=torch.Generator().manual_seed(seed+901)
    model=SeriesPolicy(train.shape[-2],train.shape[3],horizon)
    opt=torch.optim.Adam(model.parameters(),lr=.0007)
    values=torch.tensor(teacher,dtype=torch.float32);curve=[]
    for step in range(384):
        world,ctx,foes=draw(train,cues,rng,192)
        hist,x,_,_,_,outcome=play(model,world,ctx)
        score=F.pad((2*outcome).long().cumsum(1)[:,:-1],(1,0))
        bins=(ctx[...,1]>=70).long()*2+(ctx[...,2]>=70).long()
        allq=values[torch.arange(ROUNDS)[None],score,bins,foes[:,None]]
        targets=24*(allq-allq.mean(-1,keepdim=True))
        for _ in range(3):
            logits,_,_,ids=model(hist,x)
            loss=F.mse_loss(logits-logits.mean(-1,keepdim=True),targets)
            if horizon>0:loss+=.12*F.cross_entropy(ids[:,1:].reshape(-1,ids.shape[-1]),foes[:,None].expand(-1,9).flatten())
            opt.zero_grad();loss.backward();torch.nn.utils.clip_grad_norm_(model.parameters(),.8);opt.step()
        if (step+1)%32==0:curve.append(dict(step=step+1,regression=float(loss.detach()),ringouts=float((outcome==1).float().mean())))
    out=io.BytesIO();torch.save(model.state_dict(),out);atomic(folder/'actor.pt',out.getvalue())
    write_json(folder/'trained.json',dict(sha256=hashlib.sha256(out.getvalue()).hexdigest(),scoreboard=True,
        horizon=horizon,seed=seed,updates=384,sampled_rounds=384*192*ROUNDS,learning_curve=curve),fixed=True)


def bellman_games(world,ctx,rivals,edges,probs,q,kind):
    b=len(rivals);idx=np.arange(b);f=probs.shape[1]
    prior=np.zeros((b,1 if kind=='population' else f));points=np.zeros(b,int)
    out=[];choices=[]
    for g in range(ROUNDS):
        bins=(ctx[:,g,1]>=70)*2+(ctx[:,g,2]>=70);value=q[g,points,bins]
        if kind=='oracle':a=value[idx,rivals].argmax(-1)
        elif kind=='population':a=value[:,0].argmax(-1)
        else:
            posterior=belief_from(prior[:,None,:],f)
            a=(posterior[:,:,None]*value).sum(1).argmax(-1)
        w,ms=world[idx,g,a].T;delta=(2*w).astype(int);points+=delta
        if kind=='posterior':
            symbol=delta*4+np.searchsorted(edges,ms)
            prior+=np.log(probs[bins,:,a,symbol])
        out.append(w);choices.append(a)
    return np.stack(out,1),np.stack(choices,1)


def run():
    while not (FOLDER/'train/bank-08.json').exists():time.sleep(10)
    train,cues,_=bank('train',8);edges,probs=learn(train,cues)
    teacher=action_values(probs);population=action_values(probs.mean(1,keepdims=True))
    for h in (0,1,9):fit_student(train,cues,teacher,h)
    data,c,wt=bank('dev',DEV_REPS);nc,nr,_,nf,na,_=data.shape
    world=data.permute(0,1,3,2,4,5).reshape(-1,ROUNDS,na,2).clone()
    world[...,1]=100*torch.floor((world[...,1]+50)/100)
    context=c[:,:,None].expand(nc,nr,nf,ROUNDS,3).reshape(-1,ROUNDS,3)
    rivals=np.tile(np.arange(nf),nc*nr);clocks=np.repeat(np.arange(nc),nr*nf)
    scores={'wtap':wt.permute(0,1,3,2).reshape(-1,ROUNDS).numpy()};coverage={}
    for name,table in [('oracle',teacher),('population',population),('posterior',teacher)]:
        scores[name],choices=bellman_games(world.numpy(),context.numpy(),rivals,edges,probs,table,name)
        first=choices[:,0].reshape(nc,nr,nf)
        if name!='oracle' and not (first==first[:,:,:1]).all():raise ValueError('Unplayed opponent affected first action')
    for role,folder,h in [('memory-9',DEST,9),('memory-1',DEST,1),('memory-0',DEST,0),('ppo-9',FOLDER,9),('ppo-0',FOLDER,0)]:
        nets=load_models(8,h,na,nf,folder);played=matches(nets,world,context,rivals,clocks);scores[role]=played['won']
        if role=='memory-9':
            for mode in ('all-same','all-wrong','same','wrong'):
                perturbed=matches(nets,world,context,rivals,clocks,mode,played);scores[mode]=perturbed['won'];coverage[mode]=perturbed['covered']
    effects={}
    for a,b in [('memory-9','memory-0'),('memory-9','ppo-0'),('memory-9','ppo-9'),('memory-9','all-wrong'),
                ('all-same','all-wrong'),('same','wrong'),('posterior','population'),('oracle','population')]:
        for unit,vals in [('games',scores),('series',{n:series(v) for n,v in scores.items()})]:
            d=((vals[a]==1).astype(float)-(vals[b]==1)).reshape(nc,-1).mean(1)
            effects[f'{a} minus {b} {unit}']=dict(mean=float(d.mean()),adjusted_bounds=lower_contrast(d,.05/32))
    foes=list(read(DEST/'protocol.json')['field'])
    results={n:dict(series=tally(series(w)),ringouts=tally(w),opponents={f:dict(series=tally(series(w[rivals==j])),ringouts=tally(w[rivals==j])) for j,f in enumerate(foes)},
        seat={s:tally(w[context[...,0].numpy()==j]) for j,s in enumerate('ab')},rtt={f'{a}/{b}':tally(w[(context[...,1].numpy()==a)&(context[...,2].numpy()==b)]) for a in (31,109) for b in (31,109)})
        for n,w in scores.items()}
    write_json(DEST/'dev/evaluation-08.json',dict(series_per_arm=len(rivals),scores=results,effects=effects,history_coverage=coverage),fixed=True)


if __name__=='__main__':
    if sys.argv[1:]==['register']:register()
    else:run()
