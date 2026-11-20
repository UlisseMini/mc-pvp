"""One pre-trained counter per target, then learn the expert choice only from wins."""
import hashlib
import io
from itertools import combinations
import sys
import time

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

from learner import CLOCKS, RTTS, Worker, atomic, write_json
from remote_league import read
from series_eval import tally, series
from series_targeted import DEST as LIB, bank
from series_trace import CHANNELS
from specialist_diagnosis import DATA, DEST, fixture_inputs, signal
from specialist_router import visible_feedback
from specialist_ablation import bootstrap

STUDY=DEST/'expert-population'
REPS=8
SEED=22819


def register():
    spec=read(LIB/'protocol.json');pf=list(spec['field']);pa=list(spec['experts'])
    pairs=read(DEST/'protocol.json')['dedicated'];train,c,_=bank('train',REPS)
    q=(train[...,0].numpy()==1).mean((0,1,2))
    def margin(trio):
        selected=q[[pf.index(n) for n in trio]][:,[pa.index(pairs[n]) for n in trio]]
        return np.diag(selected).mean()-selected.mean(0).max()
    trio=max(combinations(sorted(pairs),3),key=margin)
    write_json(STUDY/'protocol.json',dict(pairs=pairs,small=list(trio),train_small_diagonal_advantage=float(margin(trio)),
        selection='Include all seven existing 65,536-fight counters and their seven distinct training opponents. Additionally choose three pairs using training clocks only, maximizing average diagonal outright-win rate minus the best single fixed counter among the three. Report both groups, every cross-match and unrestricted controls.',
        training='Act each of ten rounds in a row against a fixed rival. A 48-unit GRU updates after each bout from public motion, own last expert, displayed result, duration and pings. PPO sees only the chosen expert reward, 1 for outright win, 0 for draw/loss. No foe labels, demonstrations, counterfactual value loss or expert profitability tables. Two tanh value/actor heads, Adam at 0.0007, clipped PPO 0.2, entropy 0.015, gamma 1, GAE lambda 0.95, 3 full-sequence epochs, batch 96 series. First 128 and 512 updates plus final 1024 when training the seven. Compare a separately PPO-trained blind router on identical opponent and fixture schedules.',
        evaluation='Report literal known-ID to dedicated counter, privileged training-best response given ID/pings, each frozen MLP, strongest unrestricted fixed generalist, ping-only, plain W-tap. Grade 320 fights per foe from development schedules and identical physical seeds across arms. Compare intact, reset, same-opponent and wrong-opponent histories. Greedy test decisions; policy samples while training. The router and history encoder train solely for winning. The 65k low-level counters stay frozen in this controlled experiment.'),fixed=True)


def cache(split,shard,total):
    spec=read(LIB/'protocol.json');pairs=read(STUDY/'protocol.json')['pairs'];foes=list(pairs);experts=[pairs[n] for n in foes]
    indices=[list(spec['field']).index(n) for n in foes];arms=[list(spec['experts']).index(a) for a in experts]
    reps=REPS if split=='train' else 4
    world,c,_=bank(split,reps);engine=Worker(current_runtime=True)
    try:
        for i,clock in enumerate(CLOCKS[split]):
            for r in range(reps):
                if (i*reps+r)%total!=shard:continue
                path=DATA/'expert-population'/split/f'{i:02}-{r:02}.npz'
                if path.exists():continue
                v=[]
                if split=='dev':
                    with np.load(DATA/'dev'/path.name) as recorded:
                        v=recorded['features'][:,indices][:,:,:,360:][:,:,arms]
                else:
                    for g,(side,a,b,x,y) in enumerate(fixture_inputs(split,r,i)):
                        obs=[]
                        for j,actor in enumerate(experts):
                            rows=[[spec['experts'][actor]['reference'],spec['field'][f]['reference'],clock,'ab'[side],a,b,x,y] for f in foes]
                            played=engine.run(rows,True)
                            if not np.array_equal([[w,ms] for w,ms,_ in played],world[i,r,g,indices,arms[j]].numpy()):raise ValueError('Physics mismatch')
                            obs.append([signal(v[:,CHANNELS])[360:] for _,_,v in played])
                        v.append(np.stack(obs,1))
                buf=io.BytesIO();np.savez_compressed(buf,physical=np.asarray(v,np.float32));atomic(path,buf.getvalue())
    finally:engine.close()
    write_json(STUDY/f'collection/{split}-{shard}-of-{total}.json',dict(complete=True),fixed=True)


def load(split,which):
    spec=read(LIB/'protocol.json');study=read(STUDY/'protocol.json');pairs=study['pairs']
    names=list(pairs);select=names if which=='seven' else study['small']
    index=[names.index(n) for n in select]
    a=[list(spec['experts']).index(pairs[n]) for n in select];f=[list(spec['field']).index(n) for n in select]
    reps=REPS if split=='train' else 4
    world,c,wt=bank(split,reps);counter=world[:,:,:,f][:,:,:,:,a];nc,nr,ng,nf,na,_=counter.shape
    physical=[]
    for i in range(nc):
        for r in range(nr):
            with np.load(DATA/'expert-population'/split/f'{i:02}-{r:02}.npz') as data:
                physical.append(data['physical'][:,index][:,:,index])
    x=np.stack(physical).reshape(nc,nr,ng,nf,na,-1);scores=counter.numpy()
    public=visible_feedback(scores[...,0],scores[...,1],np.broadcast_to(c.numpy()[:,:,:,None,None,1:],scores.shape[:-1]+(2,)))
    return torch.from_numpy(np.concatenate((x,public),-1)),counter[...,0],c,wt,world[:,:,:,f,:,:,],f,a,select


class Actor(nn.Module):
    def __init__(self,arms,blind):
        super().__init__();self.blind=blind
        self.encoder=nn.Sequential(nn.Linear(198+arms,64),nn.LayerNorm(64),nn.Tanh())
        self.memory=nn.GRU(64,48,batch_first=True)
        self.trunk=nn.Sequential(nn.Linear(50,64),nn.Tanh());self.policy=nn.Linear(64,arms);self.value=nn.Linear(64,1)
        nn.init.zeros_(self.policy.weight);nn.init.zeros_(self.policy.bias)

    def readout(self,h,p):
        z=self.trunk(torch.cat((torch.zeros_like(h) if self.blind else h,p/150),-1))
        return self.policy(z),self.value(z).squeeze(-1)

    def event(self,x,a,n):
        return torch.cat((x,F.one_hot(a,n)),dim=-1)

    def sequence(self,events,p):
        b=len(p);zero=p.new_zeros((b,1,48))
        hist=torch.cat((zero,self.memory(self.encoder(events[:,:-1]))[0]),1)
        return self.readout(hist,p)


@torch.no_grad()
def play(net,obs,w,p,greedy,donor=None,erase=False):
    b,ng,na=w.shape;row=torch.arange(b);h=torch.zeros(1,b,48)
    acts=[];events=[];wins=[];logps=[];values=[]
    for g in range(ng):
        policy,value=net.readout(h[0],p[:,g]);dist=torch.distributions.Categorical(logits=policy)
        a=policy.argmax(1) if greedy else dist.sample();scores=w[row,g,a]
        selected=row if donor is None else donor
        event=net.event(obs[selected,g,a],a,na)
        h=net.memory(net.encoder(event)[:,None],h)[1]
        if erase:h.zero_()
        acts.append(a);events.append(event);wins.append(scores);values.append(value);logps.append(dist.log_prob(a))
    return tuple(torch.stack(x,1) for x in (acts,events,wins,values,logps))


def fit(data,folder,total,blind,seed):
    x,w,c,*_=data;nc,nr,ng,nf,na=w.shape;b=96
    net=Actor(na,blind);opt=torch.optim.Adam(net.parameters(),lr=.0007);rng=torch.Generator().manual_seed(seed+41)
    ckpt=folder/'resume.pt';step=0;history=[]
    if ckpt.exists():
        d=torch.load(ckpt,weights_only=True);net.load_state_dict(d['net']);opt.load_state_dict(d['opt'])
        rng.set_state(d['gen']);torch.set_rng_state(d['torch']);step=d['step'];history=d['curve']
    for k in range(step,total):
        fi=torch.randint(nf,(b,1),generator=rng);ii=[torch.randint(s,(b,ng),generator=rng) for s in (nc,nr,ng)]
        current=x[*ii,fi];scores=w[*ii,fi];p=c[*ii][...,1:].float()
        a,events,won,values,old=play(net,current,scores,p,False)
        returns=(won==1).float();delta=returns+F.pad(values[:,1:],(0,1))-values
        adv=delta.clone()
        for j in reversed(range(ng-1)):adv[:,j]+=.95*adv[:,j+1]
        target=adv+values;adv=(adv-adv.mean())/(adv.std()+1e-6)
        for _ in range(3):
            logits,v=net.sequence(events,p);dist=torch.distributions.Categorical(logits=logits)
            ratio=(dist.log_prob(a)-old).exp()
            loss=-torch.minimum(ratio*adv,ratio.clamp(.8,1.2)*adv).mean()+.5*F.mse_loss(v,target)-.015*dist.entropy().mean()
            opt.zero_grad();loss.backward();nn.utils.clip_grad_norm_(net.parameters(),.8);opt.step()
        if (k+1)%32==0:
            history.append(float(returns.mean()));buf=io.BytesIO()
            torch.save(dict(net=net.state_dict(),opt=opt.state_dict(),gen=rng.get_state(),torch=torch.get_rng_state(),step=k+1,curve=history),buf)
            atomic(ckpt,buf.getvalue())
            if k+1 in (128,512,1024):
                buf=io.BytesIO();torch.save(net.state_dict(),buf);atomic(folder/f'u{k+1}.pt',buf.getvalue())
                write_json(folder/f'u{k+1}.json',dict(updates=k+1,played_training_decisions=(k+1)*b*ng,
                    training_curve=history.copy(),sha256=hashlib.sha256(buf.getvalue()).hexdigest()),fixed=True)
    return net.eval()


def score(which,step,train,dev,seed):
    from series_hidden import donor_rows
    study=read(STUDY/'protocol.json');spec=read(LIB/'protocol.json');x,w,c,wt,world,fs,aa,names=dev
    n,r,g,nf,na=w.shape;ids=np.tile(np.arange(nf),n*r);rows=np.arange(len(ids))[:,None];gg=np.arange(10)[None,:]
    obs=x.permute(0,1,3,2,4,5).reshape(-1,g,na,x.shape[-1]);scores=w.permute(0,1,3,2,4).reshape(-1,g,na)
    p=c[:,:,None,:,1:].expand(n,r,nf,g,2).reshape(-1,g,2).float();same,wrong=donor_rows(c,nf)
    ww=scores.numpy();pt=train[2][...,1:].numpy()>=70;bt=2*pt[...,0]+pt[...,1]
    bins=(2*(p[...,0]>=70)+(p[...,1]>=70)).numpy()
    value=train[1].numpy()==1;table=np.stack([value[bt==b].mean(0) for b in range(4)])
    scores_out={'wtap':wt[:,:,:,fs].permute(0,1,3,2).reshape(-1,10).numpy(),
        'literal-dedicated':ww[rows,gg,ids[:,None]],'known-id-lookup':ww[rows,gg,table[bins,ids[:,None]].argmax(2)],
        'ping-only':ww[rows,gg,table.mean(1)[bins].argmax(2)]}
    for j,f in enumerate(names):scores_out['fixed-'+study['pairs'][f]]=ww[:,:,j]
    for arm in ('original-mixture','weakest-mixture'):
        scores_out[arm]=world[...,list(spec['experts']).index(arm),0].permute(0,1,3,2).reshape(-1,10).numpy()
    root=STUDY/which/f'seed-{seed}'
    for mode in ('memory','blind'):
        model=Actor(na,mode=='blind');model.load_state_dict(torch.load(root/mode/f'u{step}.pt',weights_only=True));model.eval()
        for label,donor,erase in ([(mode,None,False)] if mode=='blind' else [('memory',None,False),('reset',None,True),
            ('same',torch.from_numpy(same),False),('wrong',torch.from_numpy(wrong),False)]):
            scores_out[label]=play(model,obs,scores,p,True,donor,erase)[2].numpy()
    fixed=[k for k in scores_out if k.startswith('fixed-')][int(table.mean((0,1)).argmax())]
    contrasts={}
    for a,b in [('memory','blind'),('memory','ping-only'),('memory',fixed),('memory','literal-dedicated'),
                ('memory','known-id-lookup'),('memory','reset'),('same','wrong')]:
        delta=((scores_out[a]==1).astype(float)-(scores_out[b]==1)).reshape(n,r,-1).mean(2)
        contrasts[f'{a} minus {b}']=dict(gain=float(delta.mean()),conditional_95=bootstrap(delta))
    seat=c[:,:,None,:,0].expand(n,r,nf,g).reshape(-1,g).numpy()
    report={k:dict(games=tally(v),series=tally(series(v)),opponents={f:tally(v[ids==j]) for j,f in enumerate(names)},
        by_game=[tally(z) for z in v.T],seat={s:tally(v[seat==j]) for j,s in enumerate('ab')},
        rtt={f'{a}/{b}':tally(v[(p[...,0].numpy()==a)&(p[...,1].numpy()==b)]) for a,b in RTTS}) for k,v in scores_out.items()}
    write_json(root/f'dev-u{step}.json',dict(scores=report,paired=contrasts,opponents=names,training_best_fixed=fixed),fixed=True)


def run(which,seed):
    while not all((STUDY/f'collection/{s}-{j}-of-2.json').exists() for s in ('train','dev') for j in range(2)):time.sleep(5)
    train=load('train',which);dev=load('dev',which)
    for step in ((128,512,1024) if which=='seven' else (128,512)):
        for mode in ('memory','blind'):
            torch.manual_seed(seed);folder=STUDY/which/f'seed-{seed}'/mode
            if not (folder/f'u{step}.json').exists():fit(train,folder,step,mode=='blind',seed)
        score(which,step,train,dev,seed)


if __name__=='__main__':
    if sys.argv[1]=='register':register()
    elif sys.argv[1] in ('small','seven'):run(sys.argv[1],int(sys.argv[2]))
    else:cache(sys.argv[1],int(sys.argv[2]),int(sys.argv[3]))
