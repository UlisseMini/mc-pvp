"""Teach history-dependent play when previous opponent behavior can become stale."""
import sys

from expert_population import SEED, fit, load
from expert_switch import DEST as SWITCH, run as assess
from field_reward import value_fit
from learner import write_json

DEST = SWITCH / 'reward-training'
HAZARD = .12


def register():
    write_json(DEST/'protocol.json', dict(
        observation='After the unexpected switch, retaining all history won 560/768 versus 654 after the privileged reset on three opponents and 3971/5376 versus 4092 on seven. Freezing the pre-switch state won 455 and 3538. A stationary training distribution rewarded continuity after the first identification.',
        intervention='Change only the training opponent schedule. Before each subsequent bout change to a random different opponent with probability 0.12. Never provide a notification, opponent ID or change label to the learner. Use a separate random stream, keeping opening opponent, seats, pings, network initializations, training budget and optimizer schedule paired with the original. Three-opponent full-series PPO for 512 updates; seven-opponent chosen-action win regression for 2,048. Replicate both with a second initialization.',
        evaluation='Freeze the last checkpoint. Repeat the zero-change and all ordered unannounced-switch-after-five fixtures and controls in the parent protocol. Primary comparison is games seven through ten of the changed panel, with unchanged games separately reported. Preserve the initially measured stationary-only result and use final checkpoints selected solely by update count. This is a development diagnosis on exposed policies and timings, not mutually adaptive self-play.',
        rate=HAZARD, seeds=[SEED,SEED+1]), fixed=True)


def run(group, seed):
    import torch
    folder=DEST/group/f'seed-{seed}'
    train=load('train',group)
    torch.manual_seed(seed)
    if group=='small':
        step=512
        if not (folder/f'u{step}.pt').exists():
            fit(train,folder,step,False,seed,change_rate=HAZARD)
    else:
        step=2048
        if not (folder/f'u{step}.pt').exists():
            value_fit(train[:4],folder,seed,step,change_rate=HAZARD)
    assess(group,folder/f'u{step}.pt',folder/'dev.json')


def compare(group, seed):
    import numpy as np
    import torch
    from expert_population import Actor, STUDY
    from expert_switch import rollout
    from remote_league import read
    from series_eval import tally
    from specialist_ablation import bootstrap

    folder = DEST/group/f'seed-{seed}'
    step = 512 if group=='small' else 2048
    old = (STUDY/group/f'seed-{seed}'/'memory/u512.pt' if group=='small'
           else STUDY/'reward-debug/seven'/f'q-{seed}/u2048.pt')
    x,w,c,wt,_,fi,_,names=load('dev',group)
    n,r,g,nf,na=w.shape
    models=[]
    for path in (old, folder/f'u{step}.pt'):
        model=Actor(na,False)
        model.load_state_dict(torch.load(path,weights_only=True))
        models.append(model.eval())
    report={}
    official=read(folder/'dev.json')
    for changed in (False,True):
        pairs=[(i,j) for i in range(nf) for j in range(nf) if (i!=j)==changed]
        ids=np.array([[a]*5+[b]*5 for a,b in pairs])
        ci,ri,seq,k=np.indices((n,r,len(pairs),g))
        ix=(ci,ri,k,ids[seq,k])
        events=x[ix].reshape(-1,g,na,198)
        scores=w[ix].reshape(-1,g,na)
        ctx=c[ci,ri,k].reshape(-1,g,3)
        outcomes=[rollout(model,events,scores,ctx[...,1:].float(),'intact')[0] for model in models]
        baseline=wt[:,:,:,fi][ix].reshape(-1,g).numpy()
        name='changed' if changed else 'stable'
        if tally(outcomes[1])!=official[name]['scores']['intact']['games']:
            raise ValueError('Repeat changed the trained physical decisions')
        if seed==SEED and tally(outcomes[0])!=read(SWITCH/f'{group}.json')[name]['scores']['intact']['games']:
            raise ValueError('Repeat changed the original physical decisions')
        a,b=outcomes
        row={}
        for metric,part in [('all',slice(None)),('first-unseen',slice(5,6)),('after-one',slice(6,None))]:
            delta=((b[:,part]==1).astype(float)-(a[:,part]==1)).reshape(n,r,-1).mean(2)
            row[metric]=dict(stationary_training=tally(a[:,part]),changing_training=tally(b[:,part]),wtap=tally(baseline[:,part]),delta=float(delta.mean()),conditional_95=bootstrap(delta))
        row['by_game']={'stationary':[tally(z) for z in a.T],'changing':[tally(z) for z in b.T],'wtap':[tally(z) for z in baseline.T]}
        report[name]=row
    write_json(folder/'comparison.json',report,fixed=True)


if __name__=='__main__':
    if sys.argv[1]=='register':register()
    elif sys.argv[1]=='compare':compare(sys.argv[2],int(sys.argv[3]))
    else:run(sys.argv[1],int(sys.argv[2]))
