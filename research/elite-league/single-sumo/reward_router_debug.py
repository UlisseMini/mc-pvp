"""Keep exploration and shorten credit assignment separately in the reward-only specialist router."""
import io
import sys
import time

import numpy as np
import torch
from torch.nn import functional as F

from expert_population import Actor, SEED, STUDY, load, play
from learner import atomic, write_json
from remote_league import read
from series_eval import tally, series
from series_hidden import donor_rows
from specialist_ablation import bootstrap

DEST=STUDY/'reward-debug'


def register():
    write_json(DEST/'protocol.json',dict(observation='At update 128 all three original seven-expert PPO seeds produced the identical aggregate score with real, erased and wrong memory, exactly the same score as the blind fixed response. Preserve that run.',
        design='Use the same GRU, opponents, public bout summaries and PPO schedules as the original reward-only experiment. At 512 updates cross an explicit 30-percent uniform action probability floor with using current-game-only versus ten-game PPO credit. Add recurrent contextual action values fitted only from the chosen action’s observed ring-out, on exploratory play, and a history-blind action-value control. No counterfactual rewards or identity enter optimization in any arm. Record exploration, selected action concentration, and payoff after swapping opponent history. Primary comparisons use a predeclared seed, include every result and plain W-tap; seed replications run for the reward-regression arm.',
        interpretation='Myopic learning can recover a reactive exploitation strategy. Its objective gives no credit for a deliberately sacrificial information-gathering move. Full-return PPO is retained for that purpose. The combat MLPs remain fixed during this diagnosis.'),fixed=True)


def distribution(logits,floor):
    return torch.distributions.Categorical(probs=(1-floor)*logits.softmax(-1)+floor/logits.shape[-1])


@torch.no_grad()
def sample(net,x,w,p,kind,floor):
    b,ng,na=w.shape;h=torch.zeros(1,b,48);row=torch.arange(b);a=[];e=[];wo=[];v=[];lp=[];entropy=[]
    for j in range(ng):
        raw,value=net.readout(h[0],p[:,j])
        d=distribution(raw if kind!='q' else raw.sigmoid()*12,floor)
        move=d.sample();feedback=net.event(x[row,j,move],move,na);h=net.memory(net.encoder(feedback)[:,None],h)[1]
        a.append(move);e.append(feedback);wo.append(w[row,j,move]);v.append(value);lp.append(d.log_prob(move));entropy.append(d.entropy())
    return tuple(torch.stack(z,1) for z in (a,e,wo,v,lp,entropy))


def run(which,kind,seed,total=512):
    while not (STUDY/which/f'seed-{SEED}'/'dev-u128.json').exists():time.sleep(5)
    train=load('train',which);dev=load('dev',which)
    x,w,c,*_=train;nc,nr,ng,nf,na=w.shape;b=96
    blind=kind=='q-blind';objective='q' if kind.startswith('q') else 'short' if kind.startswith('short') else 'full'
    floor=.3 if 'floor' in kind or objective=='q' else 0.
    folder=DEST/which/f'{kind}-{seed}';torch.manual_seed(seed);rng=torch.Generator().manual_seed(seed+41)
    model=Actor(na,blind);opt=torch.optim.Adam(model.parameters(),lr=.0007);curve=[];step=0
    ckpt=folder/'resume.pt'
    if ckpt.exists():
        s=torch.load(ckpt,weights_only=True);model.load_state_dict(s['actor']);opt.load_state_dict(s['opt'])
        rng.set_state(s['gen']);torch.set_rng_state(s['rng']);step=s['step'];curve=s['curve']
    for k in range(step,total):
        ids=torch.randint(nf,(b,1),generator=rng);ii=[torch.randint(d,(b,ng),generator=rng) for d in (nc,nr,ng)]
        cues=c[*ii][...,1:].float()
        a,event,won,val,old,explore=sample(model,x[*ii,ids],w[*ii,ids],cues,objective,floor)
        win=(won==1).float();adv=win-val
        if objective=='full':
            adv+=F.pad(val[:,1:],(0,1))
            for j in reversed(range(ng-1)):adv[:,j]+=.95*adv[:,j+1]
        target=adv+val;adv=(adv-adv.mean())/(adv.std()+1e-6)
        for _ in range(3):
            raw,v=model.sequence(event,cues)
            if objective=='q':
                loss=F.binary_cross_entropy_with_logits(raw.gather(-1,a.unsqueeze(-1)).squeeze(-1),win)
            else:
                d=distribution(raw,floor);ratio=(d.log_prob(a)-old).exp()
                loss=-torch.minimum(ratio*adv,ratio.clamp(.8,1.2)*adv).mean()+.5*F.mse_loss(v,target)-.015*d.entropy().mean()
            opt.zero_grad();loss.backward();torch.nn.utils.clip_grad_norm_(model.parameters(),.8);opt.step()
        if (k+1)%32==0:
            curve.append(dict(step=k+1,train_win=float(win.mean()),action_counts=torch.bincount(a.flatten(),minlength=na).tolist(),entropy=float(explore.mean())))
            buf=io.BytesIO();torch.save(dict(actor=model.state_dict(),opt=opt.state_dict(),gen=rng.get_state(),rng=torch.get_rng_state(),step=k+1,curve=curve),buf);atomic(ckpt,buf.getvalue())
        if k+1 in (128,512,1024,2048):
            xx,ss,cc,wt,_,fi,_,names=dev
            n,r,g,nf,na=ss.shape;sc=ss.permute(0,1,3,2,4).reshape(-1,10,na);obs=xx.permute(0,1,3,2,4,5).reshape(-1,10,na,198)
            ping=cc[:,:,None,:,1:].expand(n,r,nf,g,2).reshape(-1,g,2).float();same,wrong=donor_rows(cc,nf)
            pos=np.tile(np.arange(nf),n*r);arms={};counts={}
            for label,donor,clear in [('real',None,False),('reset',None,True),('same',torch.from_numpy(same),False),('wrong',torch.from_numpy(wrong),False)]:
                ac,_,res,*_=play(model,obs,sc,ping,True,donor,clear)
                arms[label]=res.numpy();counts[label]=torch.bincount(ac.flatten(),minlength=na).tolist()
            arms['wtap']=wt[:,:,:,fi].permute(0,1,3,2).reshape(-1,10).numpy()
            reference=read(STUDY/which/f'seed-{SEED}'/'dev-u128.json')
            fixed=reference['training_best_fixed'].removeprefix('fixed-');pairs=read(STUDY/'protocol.json')['pairs']
            arms['best-fixed']=sc[:,:,[pairs[f] for f in names].index(fixed)].numpy()
            contrasts={}
            for l,o in [('real','reset'),('real','best-fixed'),('same','wrong')]:
                d=((arms[l]==1).astype(float)-(arms[o]==1)).reshape(n,r,-1).mean(2);contrasts[l+' minus '+o]=dict(mean=float(d.mean()),conditional_95=bootstrap(d))
            buf=io.BytesIO();torch.save(model.state_dict(),buf);atomic(folder/f'u{k+1}.pt',buf.getvalue())
            write_json(folder/f'u{k+1}.json',dict(scores={n:dict(games=tally(v),series=tally(series(v)),opponents={f:tally(v[pos==j]) for j,f in enumerate(names)}) for n,v in arms.items()},
                action_counts=counts,training=curve.copy(),effects=contrasts),fixed=True)


if __name__=='__main__':
    if sys.argv[1]=='register':register()
    else:run(sys.argv[1],sys.argv[2],int(sys.argv[3]),int(sys.argv[4]) if len(sys.argv)>4 else 512)
