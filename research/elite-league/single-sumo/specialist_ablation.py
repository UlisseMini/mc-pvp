"""Matched observation ablations and timing-conditional uncertainty for the rematch observer."""
import io

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

from learner import atomic, write_json
from remote_league import read
from series_eval import load_models, matches, series, tally
from series_hidden import donor_rows
from series_targeted import DEST as LIB, bank
from specialist_diagnosis import DEST, DEV_REPS, TRAIN_REPS
from specialist_router import Decoder, features, layouts, payoffs, rollout

FOLDER=DEST/'ablation'


class FlatReader(Decoder):
    def __init__(self,na,nf):
        super().__init__(na,nf,True)
        del self.gru
        # 4,355 parameters versus 4,128 in the within-bout GRU.
        self.order=nn.Sequential(nn.Linear(360,11),nn.SiLU(),nn.Linear(11,32))

    def forward(self,x):
        return self.trunk(torch.cat((self.order(x[:,:360]),self.movement(x[:,360:552]),x[:,552:]),1))


def bootstrap(d,reps=60000):
    # Each column is a separately seeded entire schedule. Preserve shared seeds across all rivals and arms.
    rng=np.random.default_rng(927442)
    n,r=d.shape
    idx=rng.integers(r,size=(reps,n,r))
    draws=d[np.arange(n)[None,:,None],idx].mean((1,2))
    return [float(v) for v in np.quantile(draws,[.025,.975])]


def run():
    spec=read(LIB/'protocol.json');na=len(spec['experts']);nf=len(spec['field'])
    opener=list(spec['experts']).index('original-mixture');seed=5213
    train,c,_=bank('train',TRAIN_REPS);dev,ctx,wt=bank('dev',DEV_REPS)
    x,y,_=features('train',TRAIN_REPS,train,c,nf,na);v,_,shape=features('dev',DEV_REPS,dev,ctx,nf,na)
    games,cues,ids,_,_=layouts('dev',DEV_REPS);table=payoffs(train.numpy(),c.numpy(),True)
    folder=FOLDER/'flat';torch.manual_seed(seed);m=FlatReader(na,nf)
    if (folder/'actor.pt').exists():m.load_state_dict(torch.load(folder/'actor.pt',weights_only=True))
    else:
        gen=torch.Generator().manual_seed(seed);opt=torch.optim.AdamW(m.parameters(),lr=.001)
        for _ in range(18):
            for j in torch.randperm(len(y),generator=gen).split(256):
                ce=F.cross_entropy(m(x[j]),y[j],label_smoothing=.03)
                opt.zero_grad();ce.backward();nn.utils.clip_grad_norm_(m.parameters(),1.5);opt.step()
        buf=io.BytesIO();torch.save(m.state_dict(),buf);atomic(folder/'actor.pt',buf.getvalue())
    models={'flat':m}
    for name in ('motion','feedback'):
        model=Decoder(na,nf,name=='motion')
        model.load_state_dict(torch.load(DEST/f'models/{name}-5213-n8/actor.pt',weights_only=True));models[name]=model
    wins={};acc={};same,wrong=donor_rows(ctx,nf)
    for name,model in models.items():
        with torch.inference_mode():ll=torch.cat([model(b).log_softmax(1) for b in v.split(512)]).numpy().reshape(*shape,nf)
        ll=ll.transpose(0,1,3,2,4,5).reshape(-1,10,na,nf)
        modes=[(name,'all',None)] if name!='motion' else [('motion','all',None),('first','first',None),('latest','latest',None),
            ('known-id','oracle',None),('blind','none',None),('same','all',same),('wrong','all',wrong)]
        for label,h,donor in modes:
            wins[label],acc[label],_,_=rollout(ll,table,games,cues,ids,opener,h,donor)
    n,r=ctx.shape[:2]
    wins['prior-ppo']=matches(load_models(8,9,na,nf,LIB),torch.from_numpy(games),torch.from_numpy(cues),
                            ids,np.repeat(np.arange(n),r*nf))['won']
    wins['fixed']=games[:,:,int(table.mean((0,1)).argmax()),0];wins['wtap']=wt.permute(0,1,3,2).reshape(-1,10).numpy()
    scored=read(DEST/'dev/decoder-5213-n8.json')['scores']
    for k in ('motion','known-id','feedback','same','wrong','prior-ppo','wtap'):
        if tally(wins[k])!=scored[k]['games']:raise ValueError('Frozen outcome did not reproduce for '+k)
    effects={}
    for a,b in [('motion',k) for k in ('flat','feedback','blind','fixed','prior-ppo','known-id','first','latest')]+[('same','wrong')]:
        delta=(wins[a]==1).astype(float)-(wins[b]==1)
        for label,subset in [('all',delta),('second',delta[:,1:2]),('rematches',delta[:,1:])]:
            d=subset.reshape(n,r,-1).mean(2)
            effects[f'{a} minus {b} {label}']=dict(delta=float(d.mean()),conditional_95_interval=bootstrap(d),per_clock=d.mean(1).tolist())
    write_json(FOLDER/'dev.json',dict(scores={k:dict(games=tally(w),series=tally(series(w)),rounds=[tally(r) for r in w.T]) for k,w in wins.items()},
        accuracy=acc,effects=effects,parameters={n:sum(p.numel() for p in m.parameters()) for n,m in models.items()},
        interpretation='Approximate unadjusted fixed-calendar intervals. Resample entire independent seed schedules within each of the eight fixed recordings, four schedules apiece. Keep all opponents, games and arms paired. Few seeds per recording, changing seats and RTT schedules and overlapping donor recording times limit inference. Prospective independent-calendar scores are separate.'),fixed=True)


if __name__=='__main__':run()
