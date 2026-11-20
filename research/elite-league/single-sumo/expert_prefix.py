"""One bout, three trained counters, and one irreversible choice from the legal prefix."""
import io
import sys
import time

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

from expert_population import STUDY
from learner import CLOCKS, RTTS, atomic, write_json
from remote_league import read
from series_eval import tally
from series_targeted import DEST as LIB
from specialist_ablation import bootstrap
from specialist_prefix import HEADS, STUDY as PREFIX, arrays

DEST=STUDY/'single-bout'


def register():
    write_json(DEST/'protocol.json',dict(opponents=read(STUDY/'protocol.json')['small'],cuts=list(HEADS),
        question='Can a router trained only on the selected branch’s outright ring-out choose between the same three dedicated MLPs within a single duel?',
        learning='From the library experiment reuse only these three opponents. Every actor plays the same broad MLP before the cut. Offer the three dedicated responses and keeping the opener. An observation GRU consumes the prefix and two pings. Exploration uniformly samples just one action per training example per pass and its single actual payoff. Fit neural action values to this binary reward; no opponent labels or supervision on unplayed actions. Two training seeds, 64 passes over the frozen first-round training panel. Evaluate argmax decisions over untouched development fixtures for this fit. Exposed calendar and opponent names retain development status. Use no decision from after the cut.',
        controls='Compare identity-to-dedicated from spawn and after cut, train-fitted ID-to-best continuation, best fixed continuation and W-tap from spawn. A second learnable arm reads only pings. All matched forks share both actors, queues and controls through the cut. Keep every early terminal result.'),fixed=True)


class ValueRouter(nn.Module):
    def __init__(self,choices):
        super().__init__()
        self.encoder=nn.GRU(9,32,batch_first=True)
        self.head=nn.Sequential(nn.Linear(34,48),nn.Tanh(),nn.Linear(48,choices))

    def forward(self,x,p,blind):
        h=p.new_zeros(len(p),32) if blind else self.encoder(x)[1][0]
        return self.head(torch.cat((h,p/150),1))


def run():
    study=read(STUDY/'protocol.json');names=study['small'];spec=read(LIB/'protocol.json')
    rivals=list(spec['field']);experts=list(spec['experts']);nf=len(rivals)
    fi=[rivals.index(n) for n in names];ai=[experts.index(study['pairs'][n]) for n in names]+[experts.index('original-mixture')]
    while not all((PREFIX/f'collection/{s}-{i}-of-2.json').exists() for s in ('train','dev') for i in range(2)):time.sleep(5)
    def restrict(a):
        x,p,seat,ids,v,lengths,start,wt=a;m=np.isin(ids,fi);idx=np.array([fi.index(j) for j in ids[m]])
        return x[m],p[m].float(),seat[m],idx,v[:,m][:,:,ai],start[m][:,ai],None if wt is None else wt[m]
    x,p,_,ids,values,_,_=restrict(arrays('train'));v,q,seat,truth,actual,spawn,wt=restrict(arrays('dev'))
    row=np.arange(len(v));pbin=2*(p[:,0]>=70).numpy()+(p[:,1]>=70).numpy();qbin=2*(q[:,0]>=70).numpy()+(q[:,1]>=70).numpy()
    report={}
    for i,h in enumerate(HEADS):
        target=(values[i]==1).astype(np.float32);scores=actual[i]
        table=np.stack([[target[(pbin==j)&(ids==f)].mean(0) for f in range(len(names))] for j in range(4)])
        arms={'dedicated-spawn':spawn[row,truth],'dedicated-switch':scores[row,truth],'train-id':scores[row,table[qbin,truth].argmax(1)],
              'blind-table':scores[row,table.mean(1)[qbin].argmax(1)],'keep':scores[:,-1],'wtap':wt}
        for blind in (False,True):
            for seed in (9381,9382):
                torch.manual_seed(seed);net=ValueRouter(len(ai));opt=torch.optim.AdamW(net.parameters(),lr=.001)
                rng=torch.Generator().manual_seed(seed);y=torch.from_numpy(target)
                for epoch in range(64):
                    for j in torch.randperm(len(x),generator=rng).split(64):
                        a=torch.randint(len(ai),(len(j),),generator=rng)
                        predictions=net(x[j,:h],p[j],blind)[torch.arange(len(j)),a]
                        loss=F.binary_cross_entropy_with_logits(predictions,y[j,a]);opt.zero_grad();loss.backward();opt.step()
                with torch.inference_mode():chosen=net(v[:,:h],q,blind).argmax(1).numpy()
                name=('blind' if blind else 'prefix')+str(seed);arms[name]=scores[row,chosen]
                buf=io.BytesIO();torch.save(net.state_dict(),buf);atomic(DEST/f'h{h}-{name}.pt',buf.getvalue())
        totals={k:dict(games=tally(a),opponents={n:tally(a[truth==j]) for j,n in enumerate(names)},
                 seats={s:tally(a[seat==j]) for j,s in enumerate('ab')},
                 rtts={f'{x}/{y}':tally(a[(q[:,0].numpy()==x)&(q[:,1].numpy()==y)]) for x,y in RTTS}) for k,a in arms.items()}
        deltas={}
        for other in ('blind9381','blind-table','keep','dedicated-switch','dedicated-spawn','train-id'):
            d=((arms['prefix9381']==1).astype(float)-(arms[other]==1)).reshape(len(CLOCKS['dev']),4,-1).mean(2)
            deltas[other]=dict(delta=float(d.mean()),conditional_95=bootstrap(d))
        report[str(h)]=dict(scores=totals,gains=deltas,training_openings=len(x),testing_openings=len(v))
    write_json(DEST/'dev.json',report,fixed=True)


if __name__=='__main__':
    if sys.argv[1:]==['register']:register()
    else:run()
