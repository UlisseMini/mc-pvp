"""Optimize the recurrent selector and its physical button policies with fresh ring-out rewards."""
import base64
import io
import random
import sys
import time

import numpy as np
import torch
from torch.nn import functional as F

from expert_population import Actor as Router, SEED, STUDY
from learner import Actor, CLOCKS, ROOT, RTTS, Worker, atomic, prepare, train_update, write_json
from remote_league import read
from series_eval import series, tally
from series_targeted import DEST as LIB
from series_trace import CHANNELS
from specialist_ablation import bootstrap
from specialist_diagnosis import fixture_inputs, signal
from specialist_router import visible_feedback

DEST=STUDY/'joint'
BATCH=16
CHECKPOINTS=(0,64,256)


def register():
    write_json(DEST/'protocol.json',dict(opponents=read(STUDY/'protocol.json')['small'],parent_seed=SEED,parent_updates=512,
        budgets=[s*BATCH*10 for s in CHECKPOINTS],modes=['joint','router-only'],
        setup='Warm start both arms from the same three physical MLPs previously adversarially trained against the three targets, and the same recurrent PPO router trained solely on ring-outs. At fine-tuning, all inputs and ring-outs come from newly played ordinary fights with the actually selected expert. No opponent classifier, opponent labels, demonstrations, counterfactual action outcomes, auxiliary prediction, or hand-coded selection rule supplies a gradient.',
        objective='Maximize outright ring-outs over ten fights with a fixed anonymous opponent. Router PPO uses undiscounted episode return and GAE lambda .95. The joint arm also trains each chosen MLP through its own complete button trajectories, with terminal credit for the current and all later actual ring-outs in the series (centered to +1/-1 per game). PPO does not differentiate the physics. Both policy levels get policy gradients from the same outcomes. Expert LR 0.00002, router LR 0.0002, 3 PPO epochs. The comparison arm trains only the router. A win gets one point, ties get zero.',
        controls='Test checkpoints without sampling the router on the identical development fixtures as the small-population comparison. Match the zero-update controller to the previously audited outcomes and each individual action to the assembled router. Compare every checkpoint with the frozen parent, literal dedicated routing, known-ID lookup, strongest generalist and W-tap. Use identical opponent, seat, clocks and physical action seeds across arms.'),fixed=True)


def load_buttons(path):
    family,encoded=path.read_bytes().strip().split(b'.');assert family==b'LEAGUE_MLP180'
    data=np.frombuffer(base64.b64decode(encoded,validate=True),dtype='>f4').astype('f4')
    net=Actor('mlp');i=0
    with torch.no_grad():
        for param in (net.encoder.weight,net.encoder.bias,net.head.weight,net.head.bias):
            k=param.numel();param.copy_(torch.from_numpy(data[i:i+k]).reshape_as(param));i+=k
    if i!=len(data):raise ValueError('Incorrect expert export')
    return net


def sample(split,seed,step,foes):
    if split=='train':
        rng=random.Random(f'physical-joint-{seed}-{step}')
        rivals=[rng.choice(foes) for _ in range(BATCH)]
        schedules=[[(rng.choice(CLOCKS['train']),rng.randrange(2),*rng.choice(RTTS),rng.getrandbits(60),rng.getrandbits(60))
                    for _ in range(10)] for _ in rivals]
    else:
        rivals=[];schedules=[]
        for i,clock in enumerate(CLOCKS['dev']):
            for r in range(4):
                tape=[(clock,*v) for v in fixture_inputs('dev',r,i)]
                for f in foes:rivals.append(f);schedules.append(tape)
    return rivals,schedules


@torch.no_grad()
def rollout(worker,net,refs,rivals,schedules,field,training,erase=False,value_exploration=False):
    b=len(rivals);na=len(refs);h=torch.zeros(1,b,48)
    wins=[];events=[];actions=[];logps=[];values=[];pings=[];trajectories=[]
    for g in range(10):
        cells=[z[g] for z in schedules];p=torch.tensor([[v[2],v[3]] for v in cells],dtype=torch.float32)
        logits,val=net.readout(h[0],p)
        if value_exploration and training:
            distribution=.7*(12*logits.sigmoid()).softmax(-1)+.3/na
            dist=torch.distributions.Categorical(probs=distribution)
        else:
            dist=torch.distributions.Categorical(logits=logits)
        choices=dist.sample() if training else logits.argmax(1)
        if len(choices)!=b:raise ValueError('Short router plan')
        plan=[[str(refs[j].relative_to(ROOT)),field[f]['reference'],clock,'ab'[s],a,other,x,y]
              for j,f,(clock,s,a,other,x,y) in zip(choices.tolist(),rivals,cells,strict=True)]
        played=[v for start in range(0,b,24) for v in worker.run(plan[start:start+24],True)]
        win=np.asarray([v[0] for v in played],np.float32);ms=np.asarray([v[1] for v in played],np.float32)
        physical=np.stack([signal(v[:,CHANNELS])[360:] for _,_,v in played])
        public=visible_feedback(win,ms,p.numpy())
        event=net.event(torch.from_numpy(np.concatenate((physical,public),1)),choices,na)
        h=net.memory(net.encoder(event)[:,None],h)[1]
        if erase:
            h.zero_()
        wins.append(torch.from_numpy(win));pings.append(p);events.append(event);values.append(val);actions.append(choices);logps.append(dist.log_prob(choices))
        if training:trajectories.append(played)
    return tuple(torch.stack(v,1) for v in (wins,events,actions,logps,values,pings)),trajectories


def router_step(router,opt,batch):
    w,ev,a,lp,v,p=batch
    delta=(w==1).float()+F.pad(v[:,1:],(0,1))-v
    adv=delta.clone()
    for i in reversed(range(9)):adv[:,i]+=.95*adv[:,i+1]
    target=adv+v;adv=(adv-adv.mean())/(adv.std()+1e-6)
    for _ in range(3):
        policy,value=router.sequence(ev,p);d=torch.distributions.Categorical(logits=policy)
        ratio=(d.log_prob(a)-lp).exp()
        loss=-torch.minimum(ratio*adv,ratio.clamp(.8,1.2)*adv).mean()+.5*F.mse_loss(value,target)-.015*d.entropy().mean()
        opt.zero_grad();loss.backward();torch.nn.utils.clip_grad_norm_(router.parameters(),.8);opt.step()


def chosen_win_step(router,opt,batch):
    wins,events,actions,_,_,pings=batch
    for _ in range(3):
        logits,_=router.sequence(events,pings)
        selected=logits.gather(-1,actions.unsqueeze(-1)).squeeze(-1)
        loss=F.binary_cross_entropy_with_logits(selected,(wins==1).float())
        opt.zero_grad()
        loss.backward()
        torch.nn.utils.clip_grad_norm_(router.parameters(),.8)
        opt.step()


def grade(worker,net,refs,names,field,out,step,homogeneous=False):
    foes,tapes=sample('dev',0,0,names)
    batch,_=rollout(worker,net,refs,foes,tapes,field,False);w=batch[0].numpy()
    parent=read(STUDY/'small'/f'seed-{SEED}'/'dev-u512.json')['scores']
    erased = None
    if homogeneous:
        reset_batch, _ = rollout(worker,net,refs,foes,tapes,field,False,erase=True)
        erased = reset_batch[0].numpy()
        if net.blind and not np.array_equal(w, erased):
            raise ValueError('Removing hidden state changed a blind physical rollout')
    if step==0 and homogeneous:
        from expert_population import load
        _,_,_,_,all_actors,_,_,_=load('dev','small')
        actor=list(read(LIB/'protocol.json')['experts']).index('original-mixture')
        broad=all_actors[...,actor,0].permute(0,1,3,2).reshape(-1,10).numpy()
        if not np.array_equal(w,broad) or not np.array_equal(erased,broad):
            raise ValueError('Three identical initial experts must reproduce every original generalist fight')
    if step==0 and not homogeneous:
        if tally(w)!=parent['memory']['games']:raise ValueError('Real parent replay differs from frozen router')
        from expert_population import load,play
        xx,ss,cc,*_=load('dev','small')
        n,r,g,nf,na=ss.shape
        truth=play(net,xx.permute(0,1,3,2,4,5).reshape(-1,10,na,198),ss.permute(0,1,3,2,4).reshape(-1,10,na),
            cc[:,:,None,:,1:].expand(n,r,nf,g,2).reshape(-1,10,2).float(),True)
        if not torch.equal(truth[0],batch[2]) or not torch.equal(truth[2],batch[0]):raise ValueError('Physics and cached parent decisions disagree')
    if step:
        with np.load(out/'u000.npz') as z:base=z['wins']
    else:base=w
    n=len(CLOCKS['dev']);diff=((w==1).astype(float)-(base==1)).reshape(n,4,-1).mean(2)
    side=np.array([[v[1] for v in s] for s in tapes]);p=batch[-1].numpy()
    buf=io.BytesIO();np.savez_compressed(buf,wins=w,actions=batch[2].numpy());atomic(out/f'u{step:03}.npz',buf.getvalue())
    memory_grade = {}
    if homogeneous:
        d=((w==1).astype(float)-(erased==1)).reshape(n,4,-1).mean(2)
        memory_grade = dict(memory_erased=tally(erased),memory_minus_erased=float(d.mean()),memory_conditional_95=bootstrap(d),
            opener=tally(w[:,0]),rematches=tally(w[:,1:]),expert_choices=torch.bincount(batch[2].flatten(),minlength=len(refs)).tolist())
    write_json(out/f'u{step:03}.json',dict(**memory_grade,physical_fights=step*BATCH*10,games=tally(w),series=tally(series(w)),
        opponents={f:tally(w[np.array(foes)==f]) for f in names},seat={s:tally(w[side==j]) for j,s in enumerate('ab')},
        rtt={f'{a}/{b}':tally(w[(p[...,0]==a)&(p[...,1]==b)]) for a,b in RTTS},
        fixed_controls={k:parent[k]['games'] for k in ('memory','wtap','literal-dedicated','known-id-lookup','original-mixture','weakest-mixture')},
        gain_over_parent=float(diff.mean()),conditional_95=bootstrap(diff)),fixed=True)


def run(mode):
    learned_values = mode in ('joint-values','joint-values-blind')
    homogeneous = learned_values or mode in ('joint-broad','joint-broad-blind')
    if not homogeneous and mode not in ('router-only','joint','joint-local','joint-slow','joint-local-slow'):
        raise ValueError('Unregistered joint learner')
    checkpoints=(0,64,256,410,1024) if homogeneous else CHECKPOINTS
    limit=checkpoints[-1] if homogeneous or mode in ('router-only','joint') else 64
    config=read(STUDY/'protocol.json');names=config['small'];library=read(LIB/'protocol.json')
    parent=STUDY/'small'/f'seed-{SEED}'/'memory/u512.pt'
    while not (STUDY/'small'/f'seed-{SEED}'/'dev-u512.json').exists():time.sleep(5)
    out=DEST/mode;out.mkdir(parents=True,exist_ok=True);torch.manual_seed(93720)
    router=Router(len(names),mode.endswith('-blind'))
    if not homogeneous:
        router.load_state_dict(torch.load(parent,weights_only=True))
    opt=torch.optim.Adam(router.parameters(),lr=.0002)
    source=[ROOT/library['experts']['original-mixture' if homogeneous else config['pairs'][f]]['reference'] for f in names]
    nets=[load_buttons(p) for p in source];opts=[torch.optim.Adam(n.parameters(),lr=.000002 if mode.endswith('-slow') else .00002) for n in nets]
    refs=[out/f'live-expert-{i}.net' for i in range(len(nets))];checkpoint=out/'resume.pt'
    step=0
    if checkpoint.exists():
        d=torch.load(checkpoint,weights_only=True);router.load_state_dict(d['router']);opt.load_state_dict(d['opt']);step=d['step']
        for n,o,state,optim in zip(nets,opts,d['experts'],d['opts'],strict=True):n.load_state_dict(state);o.load_state_dict(optim)
        torch.set_rng_state(d['rng'])
    worker=Worker(current_runtime=True)
    try:
        while True:
            for n,p in zip(nets,refs):n.export(p)
            if step==0 and any(a.read_bytes()!=b.read_bytes() for a,b in zip(source,refs)):raise ValueError('Warm expert weights changed')
            if step in checkpoints:
                if not (out/f'u{step:03}.json').exists():
                    grade(worker,router,refs,names,library['field'],out,step,homogeneous)
                if not (out/f'u{step:03}-router.pt').exists():
                    for j,n in enumerate(nets):n.export(out/f'u{step:03}-expert-{j}.net')
                    buf=io.BytesIO();torch.save(router.state_dict(),buf);atomic(out/f'u{step:03}-router.pt',buf.getvalue())
            if step==limit:break
            foes,tapes=sample('train',428012,step,names)
            batch,played=rollout(worker,router,refs,foes,tapes,library['field'],True,value_exploration=learned_values)
            if learned_values:
                chosen_win_step(router,opt,batch)
            else:
                router_step(router,opt,batch)
            if mode!='router-only':
                centered=2*(batch[0]==1).float()-1
                total=centered if homogeneous or '-local' in mode else centered.flip(1).cumsum(1).flip(1)
                for arm,(n,o) in enumerate(zip(nets,opts)):
                    samples=[((float(total[k,g])+1)/2,ms,array) for g,rounds in enumerate(played)
                             for k,(_,ms,array) in enumerate(rounds) if int(batch[2][k,g])==arm]
                    if samples:train_update(n,o,prepare(samples),93721+step*3+arm)
            step+=1
            if step%8==0:
                buf=io.BytesIO();torch.save(dict(router=router.state_dict(),opt=opt.state_dict(),step=step,rng=torch.get_rng_state(),
                    experts=[m.state_dict() for m in nets],opts=[o.state_dict() for o in opts]),buf);atomic(checkpoint,buf.getvalue())
                write_json(out/'progress.json',dict(updates=step,fights=step*BATCH*10))
    finally:worker.close()


def register_values():
    write_json(DEST/'joint-values-protocol.json',dict(
        populations=read(STUDY/'protocol.json')['small'],budgets=[n*BATCH*10 for n in (0,64,256,410,1024)],
        motivation='The original jointly trained GRU used a single one of three MLPs at 65,600 fights. Across all 27 opponents, per-bout win prediction with exploration trained a better frozen-expert router than series PPO in both seeds. Test that recipe while the physical MLPs also learn.',
        training='Initialize all three MLPs identically from the broad generalist and initialize the GRU randomly. Compare a recurrent selector with a separately trained blind selector, on the same physical schedules and networks as the original joint PPO pair. Retain the router learning rate 0.0002, expert learning rate 0.00002 and three epochs. The revised router estimates the probability that its chosen expert wins the current bout. Train solely on the actual outcome of the chosen expert. Sample a mixture of 30% uniform exploration and 70% softmax over predicted win probabilities times 12. Update each expert by PPO on its actual physical button trajectory and the same terminal ring-out. No opponent IDs, pretrained counters, counterfactual outcomes, auxiliary losses or assignment by opponent. The shared motor initialization retains its original broad prior.',
        evaluation='The original three opponents and independent development games are fixed. Greedy test decisions. Score wins, draws, seats, both RTTs and plain W-tap. Run the same controller a second time with hidden state cleared before every game. Preserve every checkpoint and its actual expert selections.'),
        fixed=True)


if __name__=='__main__':
    if sys.argv[1]=='register':register()
    elif sys.argv[1]=='register-values':register_values()
    elif sys.argv[1]=='start-values':
        from remote_jobs import OUT,start
        read(DEST/'joint-values-protocol.json')
        for mode in ('joint-values','joint-values-blind'):
            start('ep-'+mode,'joint_experts.py',[mode],DEST.relative_to(OUT)/mode/'u1024-router.pt')
    else:run(sys.argv[1])
