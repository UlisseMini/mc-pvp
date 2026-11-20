"""Distill the frozen known-opponent MLP on a legal student's own trajectories, then play ordinary PPO."""
import argparse
import base64
import fcntl
import hashlib
import io
import json
import random
import subprocess
import sys

import numpy as np
import torch
from torch.nn import functional as F
from torch.nn.utils.rnn import pack_sequence, PackedSequence

from learner import Actor, BUILD, CLOCKS, EVIDENCE, FIELD, HERE, ROOT, Worker, atomic, freeze, ordinal_logit, write_json
from opponent_id import DIR
from run_training import train
from train import configure

OUT = EVIDENCE / 'oracle-students'
ROLES = ('gru-id-loss', 'gru', 'mlp')
STEPS = 128
BOUTS = 2 * len(FIELD)
SEED = 920147
TEACHERS = DIR / 'informed/u256'

def initializer(role):
    return (DIR / 'shared-mlp' if role == 'mlp' else EVIDENCE / 'bootstrap-sumo-seven-first') / 'trainer.pt'

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def register():
    write_json(OUT / 'protocol.json', dict(
        teacher='frozen 180-unit correct-ID MLP, 16,384 original mixed PPO bouts',
        teacher_hashes={foe:sha(TEACHERS/(foe+'.net')) for foe in FIELD},
        roles=list(ROLES), initializers={role:sha(initializer(role)) for role in ROLES},
        controls='both GRU roles start from identical weights, as do their physical random draws and opponent schedule',
        observation='25 own-client features; auxiliary ID loss is available only in training; no ID enters actor or value inputs',
        teacher_action_loss='conditional physical button distribution KL; equal total weight per bout',
        auxiliary='0.10 times opponent classification cross entropy at heads 6,12,24,48,96 and terminal; average per bout',
        rollout='two complete matched worlds per opponent each update; first 16 updates under a frozen blind actor, then under each student alone',
        seed=SEED,updates=STEPS,bouts_per_update=BOUTS,optimizer='Adam 0.001, two passes, eight full trajectories per minibatch, gradient clip 0.6',
        fine_tune='after imitation, 256 PPO updates of 64 bouts; shared physical schedule; official ring-out outcome',
        capture='retain imitation and final PPO checkpoints for every arm, regardless of outcome',
        test='all 20 archive policies, W-tap, Sumo Seven, frozen best-sumo, new league winner and learned response; matched seats and both pings',
        claim='compare the legal GRU to the blind and known-ID MLP and per-opponent specialists. A causal memory claim requires matched history interventions and a fresh prospective test.',
    ), fixed=True)

def teachers():
    """The ID-specific bias is the only difference between the twenty frozen exports."""
    weights=[]
    for foe in FIELD:
        tag, data=(TEACHERS/(foe+'.net')).read_bytes().strip().split(b'.')
        if tag != b'LEAGUE_MLP180':raise ValueError('Wrong known-ID teacher architecture')
        weights.append(np.frombuffer(base64.b64decode(data,validate=True),'>f4').astype('f4'))
    all_weights=torch.from_numpy(np.stack(weights)); start=180*25; end=start+180
    for cols in (all_weights[:,:start],all_weights[:,end:]):
        if not torch.equal(cols,cols[0].expand_as(cols)):raise ValueError('Teacher exports differ outside the ID bias')
    w=all_weights[0]
    return (w[:start].reshape(180,25),all_weights[:,start:end],w[end:-6].reshape(6,180),w[-6:])

def teacher_scores(weights, x, ids):
    first, biases, head, bias=weights
    return F.linear(F.softsign(F.linear(x,first)+biases[ids]),head,bias)

def button_kl(pred, target, ground):
    """Exact KL(teacher || student) for the legal joint physical action distribution."""
    def ordinal(score, low, high):
        return torch.stack([ordinal_logit(score,low,high,i) for i in
                            (torch.full_like(score,-1),torch.zeros_like(score),torch.ones_like(score))],dim=-1)
    def cat(a,b):return (a.exp()*(a-b)).sum(dim=-1)
    def binary(col):
        pred_logits=pred[:,col]/.25; target_logits=target[:,col]/.25
        return F.softplus(pred_logits)-F.softplus(target_logits)-torch.sigmoid(target_logits)*(pred_logits-target_logits)
    walk=ordinal(target[:,0],-.65,0)
    return (cat(walk,ordinal(pred[:,0],-.65,0))+walk[:,2].exp()*binary(1)+binary(2)+ground*binary(3)
            +cat(ordinal(target[:,4],-.45,.45),ordinal(pred[:,4],-.45,.45)))

def update(folder, role, until):
    register(); state=folder/'distill-state.pt'
    torch.manual_seed(SEED)
    kind='mlp' if role=='mlp' else 'gru'
    actor=Actor(kind)
    actor.load_state_dict(torch.load(initializer(role),weights_only=False)['net'])
    aux=torch.nn.Linear(32,len(FIELD)) if role=='gru-id-loss' else None
    params=list(actor.parameters())+(list(aux.parameters()) if aux is not None else [])
    opt=torch.optim.Adam(params,lr=.001); log=[]
    if state.exists():
        saved=torch.load(state,weights_only=False)
        actor.load_state_dict(saved['net']);opt.load_state_dict(saved['opt']);log=saved['log']
        if aux is not None:aux.load_state_dict(saved['aux'])
    weights=teachers(); pings=[p for p in range(151) if p not in (31,109)]
    worker=Worker()
    try:
        for step in range(len(log),min(until,STEPS)):
            rng=random.Random(SEED+step*1543); plan=[]
            current=worker.folder/'student.net';actor.export(current)
            driver=DIR/'shared-mlp/initial.net' if step<16 else current
            for _ in range(2):
                fixture=[rng.choice(CLOCKS['train']),rng.choice('ab'),rng.choice(pings),rng.choice(pings),
                         rng.getrandbits(60),rng.getrandbits(60)]
                plan.extend([[driver,foe,*fixture] for foe in FIELD])
            rows=worker.run(plan,True); episodes=[]
            for j,(_,_,row) in enumerate(rows):
                x=torch.from_numpy(row[:,:25].copy()); n=len(x)
                ids=torch.full((n,),j%len(FIELD),dtype=torch.long)
                target=teacher_scores(weights,x,ids).detach()
                markers=torch.zeros(n)
                indices={i-1 for i in (6,12,24,48,96,n) if 1<=i<=n}
                markers[list(indices)]=1/len(indices)
                episodes.append(torch.cat([x,target,ids[:,None],torch.full((n,1),1/n),markers[:,None]],dim=1))
            if not all(torch.equal(ep[0,:25],episodes[(j//len(FIELD))*len(FIELD)][0,:25]) for j,ep in enumerate(episodes)):
                raise ValueError('Assigned opponent altered a shared spawn observation')
            kls=[]; losses=[];indices=list(range(len(episodes)))
            for _ in range(2):
                rng.shuffle(indices)
                for start in range(0,len(indices),8):
                    batch=indices[start:start+8]
                    seq=pack_sequence([episodes[j] for j in batch],enforce_sorted=False)
                    d=seq.data;obs=PackedSequence(d[:,:25],seq.batch_sizes,seq.sorted_indices,seq.unsorted_indices)
                    hidden=F.softsign(actor.encoder(obs.data)) if kind=='mlp' else actor.encoder(obs)[0].data
                    y=actor.head(hidden)
                    kl=(button_kl(y,d[:,25:31],d[:,8]>0)*d[:,32]).sum()/len(batch)
                    loss=kl
                    if aux is not None:
                        mask=d[:,33]>0
                        ce=F.cross_entropy(aux(hidden[mask]),d[mask,31].long(),reduction='none')
                        loss=loss+.10*(ce*d[mask,33]).sum()/len(batch)
                    opt.zero_grad();loss.backward();torch.nn.utils.clip_grad_norm_(params,.6,error_if_nonfinite=True);opt.step()
                    losses.append(float(loss.detach()));kls.append(float(kl.detach()))
            log.append(dict(update=step+1,duels=(step+1)*BOUTS,behavior_score=float(np.mean([r[0] for r in rows])),
                            teacher_button_kl=float(np.mean(kls)),loss=float(np.mean(losses))))
            buf=io.BytesIO();torch.save(dict(net=actor.state_dict(),aux=aux.state_dict() if aux is not None else None,
                                             opt=opt.state_dict(),log=log),buf)
            atomic(state,buf.getvalue());write_json(folder/'progress.json',log[-1])
            if step+1 in (16,64,STEPS):
                actor.export(folder/f'u{step+1:03}.net')
    finally:worker.close()
    if len(log)==STEPS and not (folder/'trainer.pt').exists():
        with torch.no_grad():actor.head.weight[-1].zero_();actor.head.bias[-1].zero_()
        actor.export(folder/'initial.net');buf=io.BytesIO();torch.save(dict(net=actor.state_dict()),buf);atomic(folder/'trainer.pt',buf.getvalue())

def launch(*args):
    subprocess.run([sys.executable,HERE.parent/'cpu_limit.py','--policy','nice','-n','8',sys.executable,__file__,*map(str,args)],check=True)

def run():
    register()
    with (OUT/'run.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        for until in range(4,STEPS+1,4):
            for role in ROLES:
                p=OUT/role/'imitation';progress=p/'progress.json'
                done=json.loads(progress.read_text())['update'] if progress.exists() else 0
                if done<until or (until==STEPS and not (p/'trainer.pt').exists()):launch('update',role,until)
        cfg=json.loads((EVIDENCE/'g001/main/config.json').read_text())
        for role in ROLES:
            kind='mlp' if role=='mlp' else 'gru';p=OUT/role
            configure(p/'ppo',kind,'main',SEED+913,SEED+2423,cfg['incumbent'],cfg['counter'],
                      cfg['families'],cfg['scores'],p/'imitation/trainer.pt')
        for until in range(16,257,16):
            for role in ROLES:train(OUT/role/'ppo',until)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('op',choices=['run','update'])
    parser.add_argument('role',choices=ROLES,nargs='?');parser.add_argument('until',type=int,nargs='?');args=parser.parse_args()
    if args.op=='run':run()
    else:update(OUT/args.role/'imitation',args.role,args.until)
