"""Recurrent PPO over ten regulation matches. Each outer action selects one frozen legal combat policy."""
import argparse
import hashlib
import io
import time

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

from learner import atomic,write_json
from remote_league import read
from series_bank import BUDGETS,CACHE,FOLDER,ROUNDS

HORIZONS=(-1,0,1,3,9)
SEEDS=(4101,4102,4103)
UPDATES={2:192,8:384,16:768}
BATCH=192


def register():
    write_json(FOLDER/'training.json',dict(horizons=list(HORIZONS),seeds=list(SEEDS),updates=UPDATES,batch=BATCH,
        architecture='A 64-unit GRU encodes completed games. Two 64-unit tanh layers combine it with current pings, series score and game number. Eight action logits select a frozen legal expert for the whole next bout. The blind control drops the score as well as the GRU; horizon zero sees the scoreboard only.',
        train='Clipped PPO with gamma 1, three epochs per batch, Adam 0.0007, clipping 0.2, entropy 0.015, gradient norm 0.8. Terminal +1/0/-1 for a won/tied/lost series, plus 0.15 times mean per-game result. Auxiliary regression predicts all eight simulated game scores. A training-only opponent classification loss encourages useful memory; the actor never takes an ID as an input.',
        grade='Use greedy selection, with the equal-logit mean across the three final-budget seeds as the preregistered final policy and separate per-seed results. Fit the known-ID and the ping-only lookup on training games. Compare with every fixed expert, with a scoreboard-only chooser and with a chooser blind to previous results. Swap older history from an independent clock matched on earlier points and present pings, and preserve the last real game. Report actual series wins, ties, ring-outs and family-adjusted intervals across timing recordings.',
        scale='2, 8, then 16 unique training schedules per clock. Every horizon has the same PPO steps and simulation exposure at each stage. Reused game transitions are reported separately from generated Minecraft simulator fights.'),fixed=True)


def bank(split,reps,root=None,cache=None):
    root=FOLDER if root is None else root;cache=CACHE if cache is None else cache
    record=read(root/split/f'bank-{reps:02}.json')
    files=[[cache/split/f'{r:02}-{i:02}.npz' for r in range(reps)] for i in range(record['recordings'])]
    def group(paths):
        data=[]
        for path in paths:
            with np.load(path) as saved:data.append(tuple(saved[n] for n in ('stats','context','wt')))
        return tuple(np.stack(a) for a in zip(*data))
    return tuple(torch.from_numpy(np.stack(a)) for a in zip(*(group(f) for f in files)))


class SeriesPolicy(nn.Module):
    def __init__(self,arms,opponents,horizon,scoreboard=True):
        super().__init__()
        self.arms=arms;self.horizon=horizon;self.scoreboard=scoreboard
        self.encoder=nn.GRU(arms+6,64,batch_first=True)
        self.trunk=nn.Sequential(nn.Linear(68,64),nn.Tanh(),nn.Linear(64,64),nn.Tanh())
        self.payoffs=nn.Linear(64,arms)
        self.actor=nn.Linear(64,arms)
        self.value=nn.Linear(64,1)
        self.identity=nn.Linear(64,opponents)
        nn.init.zeros_(self.actor.weight);nn.init.zeros_(self.actor.bias)

    def forward(self,history,context):
        b,n,_=history.shape
        zero=history.new_zeros(b,1,64)
        if self.horizon<0 or not self.scoreboard:
            context=context.clone();context[:,:,1]=0
        if self.horizon<=0 or n==0:
            memory=zero.expand(b,n+1,64)
        elif self.horizon>=n:
            memory=torch.cat((zero,self.encoder(history)[0]),1)
        else:
            enc=[]
            for end in range(1,n+1):
                enc.append(self.encoder(history[:,max(0,end-self.horizon):end])[1][0])
            memory=torch.cat((zero,torch.stack(enc,1)),1)
        h=self.trunk(torch.cat((context,memory),-1));q=self.payoffs(h)
        return self.actor(h)+2*q,q,self.value(h).squeeze(-1),self.identity(memory)


def features(points,round_index,pings):
    return torch.cat((torch.full_like(points[:,None],round_index/9),((points-.5*round_index)/5)[:,None],
                      pings.to(torch.float32)/150),-1)


def feedback(action,outcome,duration,pings,arms):
    # These are public results and the bot's two ping readings for the completed game.
    return torch.cat((F.one_hot(action,arms),F.one_hot((2*outcome).long(),3),
                      torch.log1p(duration.clamp(0,120000)/1000)[:,None]/5,pings.to(torch.float32)/150),-1).float()


@torch.no_grad()
def choose(models,events,own,other,arms):
    hist=torch.empty(1,0,arms+6);observations=[];score=0.
    for g,(action,outcome,ms,a,b) in enumerate(events):
        ping=torch.tensor([[a,b]])
        observations.append(features(torch.tensor([score]),g,ping))
        obs=feedback(torch.tensor([action]),torch.tensor([outcome]),torch.tensor([ms]),ping,arms)
        hist=torch.cat((hist,obs[:,None]),1);score+=outcome
    observations.append(features(torch.tensor([score]),len(events),torch.tensor([[own,other]])))
    x=torch.stack(observations,1)
    return int(torch.stack([m(hist,x)[0][0,-1] for m in models]).mean(0).argmax())


def draw(stats,context,generator,batch,opponent_weights=None):
    nclock,nrep,_,opponents=stats.shape[:4]
    if opponent_weights is None:rival=torch.randint(opponents,(batch,),generator=generator)
    else:rival=torch.multinomial(opponent_weights,batch,replacement=True,generator=generator)
    # The opponent stays fixed. No association between opponent identity and fixtures is introduced.
    picks=[torch.randint(m,(batch,ROUNDS),generator=generator) for m in (nclock,nrep,ROUNDS)]
    return stats[*picks,rival[:,None]],context[*picks],rival


@torch.no_grad()
def play(model,worlds,cues):
    b,_,arms,_=worlds.shape
    rows=torch.arange(b);points=torch.zeros(b);history=worlds.new_empty(b,0,arms+6)
    controls=[];actions=[];logps=[];values=[];outcomes=[]
    for i in range(ROUNDS):
        ping=cues[:,i,1:];controls.append(features(points,i,ping))
        policy,_,v,_=model(history,torch.stack(controls,1))
        dist=torch.distributions.Categorical(logits=policy[:,-1]);a=dist.sample()
        w,ms=worlds[rows,i,a].unbind(-1)
        actions.append(a);outcomes.append(w);values.append(v[:,-1]);logps.append(dist.log_prob(a));points+=w
        history=torch.cat((history,feedback(a,w,ms,ping,arms)[:,None]),1)
    return history[:,:-1],torch.stack(controls,1),torch.stack(actions,1),torch.stack(logps,1),torch.stack(values,1),torch.stack(outcomes,1)


def fit(stats,cues,budget,horizon,seed,updates=None,batch=BATCH,folder=None,objective='series',scoreboard=True,opponent_weights=None):
    folder=FOLDER/f'learn-{budget:02}'/f'h{horizon}-s{seed}' if folder is None else folder
    if (folder/'trained.json').is_file():return
    torch.manual_seed(seed)
    net=SeriesPolicy(stats.shape[-2],stats.shape[3],horizon,scoreboard)
    opt=torch.optim.Adam(net.parameters(),lr=.0007)
    gen=torch.Generator().manual_seed(seed+927)
    total=updates or UPDATES[budget];start=0;curve=[]
    checkpoint=folder/'checkpoint.pt'
    if checkpoint.is_file():
        state=torch.load(checkpoint,weights_only=True);start=state['step'];curve=state['curve']
        net.load_state_dict(state['net']);opt.load_state_dict(state['optimizer'])
        gen.set_state(state['generator']);torch.set_rng_state(state['torch_rng'])
    for step in range(start,total):
        worlds,ctx,rival=draw(stats,cues,gen,batch,opponent_weights)
        hist,x,a,old,v,won=play(net,worlds,ctx)
        delta=2*won-1
        # The terminal majority dominates. A small ring-out reward also grades play after clinching.
        if objective=='series':
            target=delta.sum(1).sign()[:,None]+.15*delta.flip(1).cumsum(1).flip(1)/ROUNDS
        elif objective=='ringouts':
            target=(2*(won==1).float()-1).flip(1).cumsum(1).flip(1)/ROUNDS
        else:raise ValueError('Unknown ten-game training reward')
        advantage=target-v;advantage=(advantage-advantage.mean())/(advantage.std()+1e-6)
        for _ in range(3):
            logits,q,values,ids=net(hist,x)
            dist=torch.distributions.Categorical(logits=logits);ratio=(dist.log_prob(a)-old).exp()
            policy=-torch.minimum(ratio*advantage,ratio.clamp(.8,1.2)*advantage).mean()
            # Offline counterfactuals teach a per-expert expected game value, never an inference input.
            loss=policy+.4*F.mse_loss(values,target)+.6*F.mse_loss(q,worlds[...,0])-.015*dist.entropy().mean()
            if horizon>0:loss+=.12*F.cross_entropy(ids[:,1:].reshape(-1,ids.shape[-1]),rival[:,None].expand(-1,ROUNDS-1).reshape(-1))
            opt.zero_grad();loss.backward();nn.utils.clip_grad_norm_(net.parameters(),.8);opt.step()
        if (step+1)%32==0 or step+1==total:
            curve.append(dict(update=step+1,ringouts=float((won==1).float().mean()),series_wins=float((delta.sum(1)>0).float().mean())))
            out=io.BytesIO();torch.save(dict(net=net.state_dict(),optimizer=opt.state_dict(),step=step+1,curve=curve,
                         generator=gen.get_state(),torch_rng=torch.get_rng_state()),out);atomic(checkpoint,out.getvalue())
            write_json(folder/'progress.json',curve[-1])
    out=io.BytesIO();torch.save(net.state_dict(),out);atomic(folder/'actor.pt',out.getvalue())
    write_json(folder/'trained.json',dict(updates=total,sampled_series=total*batch,sampled_rounds=ROUNDS*total*batch,sha256=hashlib.sha256(out.getvalue()).hexdigest(),
               horizon=horizon,seed=seed,objective=objective,scoreboard=scoreboard,learning_curve=curve),fixed=True)


def run(horizon):
    for budget in BUDGETS:
        while not (FOLDER/'train'/f'bank-{budget:02}.json').is_file():time.sleep(15)
        stats,cues,_=bank('train',budget)
        for seed in SEEDS if budget==BUDGETS[-1] else SEEDS[:1]:fit(stats,cues,budget,horizon,seed)


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('task',choices=['register',*map(str,HORIZONS)])
    task=parser.parse_args().task
    if task=='register':register()
    else:run(int(task))
