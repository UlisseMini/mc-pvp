"""Learn which combat expert to call as the league adds independently trained counters."""
import io
import sys
import time

import numpy as np
import torch
from torch.nn import functional as F

from expert_population import Actor, SEED
from expanded_crossplay import FOLDER
from learner import CLOCKS, RTTS, atomic, write_json
from remote_league import read
from series_eval import series, tally
from series_hidden import donor_rows
from series_targeted import DEST as LIB, bank
from specialist_ablation import bootstrap
from specialist_diagnosis import DATA

DEST = FOLDER / 'learned-selection'
STEPS = (512, 2048)


def register():
    write_json(DEST/'protocol.json',dict(coverage=[7,9,27],seeds=[SEED,SEED+1],
        reason='The first two extra counters measurably raised the 27-opponent payoff of the privileged lookup and of the observer that was supervised to recognize opponents. Can the expanding set of counter policies also be used from terminal ring-out rewards alone?',
        design='Use the same nine, eleven or final set of combat MLPs from the frozen coverage ledger. Every arm plays the same original generalist on game one. Its first 48 legal observations have twelve visible channels. Supply the mean and standard deviation of each channel over eight successive six-decision windows, plus the actual opening result and public pings. Subsequent hidden updates see only the chosen expert, public pings and the observed win/draw/loss. No real or inferred opponent ID, expert annotations, demonstration or unselected reward is an input or a training target. No weight updates in play.',
        training='48-unit GRU over the ten matches. Fit action values to the outright ring-out of the sampled action on games 2–10. Use both full ten-game sequences and the blind controls in evaluation. Each update uses 96 ten-game sequences, three Adam epochs at 0.0007, and exploration with a 30% uniform floor. Two training schedules per training calendar, with frozen 512- and 2048-update checks. Compare all arms on the original 8640 development games, including first bout, intact and erased state, matched same- and other-opponent history, all opponent and latency/seat cells, the train-fitted true-ID and ping-only lookup and W-tap. This optimizer targets exploitation conditional on the robust common opening.'),fixed=True)


def load(coverage, split):
    receipt=read(FOLDER/f'crossplay/covered-{coverage:02}.json')
    spec=read(LIB/'protocol.json')
    base=list(spec['experts'])
    arms=receipt['experts']
    if arms[:len(base)]!=base:
        raise ValueError('Expanded library changed the original order')
    reps=2 if split=='train' else 4
    old,c,wt=bank(split,reps)
    columns=[old[...,0].numpy()]
    for name in arms[len(base):]:
        vals=[]
        for i in range(len(CLOCKS[split])):
            with np.load(DATA/'full-columns'/name.removeprefix('dedicated-')/split/f'{i:02}.npz') as z:
                vals.append(z['scores'])
        columns.append(np.stack(vals)[...,None])
    w=torch.from_numpy(np.concatenate(columns,axis=-1))
    views=[]
    for i in range(len(CLOCKS[split])):
        row=[]
        for r in range(reps):
            with np.load(DATA/'opponent-motion'/split/f'{i:02}-{r:02}.npz') as z:
                seq=z['trace']
            seq=seq.reshape(*seq.shape[:-2],8,6,12)
            row.append(np.concatenate((seq.mean(-2),seq.std(-2)),axis=-1).reshape(*seq.shape[:-3],192))
        views.append(np.stack(row))
    return w,c,torch.from_numpy(np.stack(views)),wt,arms


@torch.no_grad()
def trajectory(net,world,p,opening,opener,train=False,erase=False,donor=None):
    b,ng,na=world.shape
    row=torch.arange(b)
    source=row if donor is None else donor
    h=torch.zeros(1,b,48)
    choices=[];rewards=[];events=[]
    for g in range(ng):
        logits=net.readout(h[0],p[:,g])[0]
        if g==0:
            act=torch.full((b,),opener)
        elif train:
            prob=.7*(12*logits.sigmoid()).softmax(-1)+.3/na
            act=torch.distributions.Categorical(probs=prob).sample()
        else:
            act=logits.argmax(1)
        reward=world[row,g,act]
        seen=world[source,g,act]
        motion=opening[source] if g==0 else torch.zeros_like(opening)
        observation=torch.cat((motion,F.one_hot((seen*2).long(),3),torch.zeros(b,1),p[:,g]/150),1)
        token=net.event(observation,act,na)
        h=net.memory(net.encoder(token)[:,None],h)[1]
        if erase:h.zero_()
        choices.append(act);rewards.append(reward);events.append(token)
    return tuple(torch.stack(a,1) for a in (choices,rewards,events))


def evaluate(net,data,coverage,folder,step):
    world,c,motion,wt,arms=data
    n,r,g,nf,na=world.shape
    points=world.permute(0,1,3,2,4).reshape(-1,g,na)
    opening=motion[:,:,0].reshape(-1,192)
    ctx=c[:,:,None].expand(n,r,nf,g,3).reshape(-1,g,3)
    p=ctx[...,1:].float();opener=arms.index('original-mixture')
    si,di=donor_rows(c,nf)
    outcomes={'wtap':wt.permute(0,1,3,2).reshape(-1,g).numpy()}
    choices={}
    for label,src,clear in [('memory',None,False),('erased',None,True),('same',torch.from_numpy(si),False),('wrong',torch.from_numpy(di),False)]:
        a,reward,_=trajectory(net,points,p,opening,opener,erase=clear,donor=src)
        outcomes[label]=reward.numpy()
        choices[label]=torch.bincount(a[:,1:].flatten(),minlength=na).tolist()
    ref=read(FOLDER/f'crossplay/routed-{coverage:02}.json')
    if tally(outcomes['wtap'])!=ref['scores']['wtap']['games']:
        raise ValueError('Plain W-tap fixtures differ')
    names=list(read(LIB/'protocol.json')['field']);ids=np.tile(np.arange(nf),n*r)
    score={k:dict(games=tally(v),series=tally(series(v)),first=tally(v[:,0]),rematches=tally(v[:,1:]),
        opponents={foe:tally(v[ids==i]) for i,foe in enumerate(names)},
        seats={s:tally(v[ctx[...,0].numpy()==i]) for i,s in enumerate('ab')},
        rtt={f'{a}/{b}':tally(v[(p[...,0].numpy()==a)&(p[...,1].numpy()==b)]) for a,b in RTTS}) for k,v in outcomes.items()}
    effects={}
    for a,b in [('memory','erased'),('same','wrong')]:
        delta=((outcomes[a]==1).astype(float)-(outcomes[b]==1)).reshape(n,r,-1).mean(2)
        effects[a+' minus '+b]=dict(delta=float(delta.mean()),conditional_95=bootstrap(delta))
    buf=io.BytesIO();torch.save(net.state_dict(),buf);atomic(folder/f'u{step}.pt',buf.getvalue())
    write_json(folder/f'u{step}.json',dict(scores=score,choice_counts=choices,contrasts=effects,
        matched_controls={k:ref['scores'][k]['games'] for k in ('expanded-history','known-id','blind','wtap')},
        source_experts=arms,training_actions=(step*96*9)),fixed=True)


def run(coverage,seed):
    while not (FOLDER/f'crossplay/routed-{coverage:02}.json').exists():time.sleep(15)
    world,c,motion,_,arms=load(coverage,'train');dev=load(coverage,'dev')
    n,r,g,nf,na=world.shape;opener=arms.index('original-mixture');b=96
    torch.manual_seed(seed);rng=torch.Generator().manual_seed(seed+41)
    net=Actor(na,False);opt=torch.optim.Adam(net.parameters(),lr=.0007)
    folder=DEST/f'covered-{coverage:02}'/f'seed-{seed}'
    resume=folder/'resume.pt';start=0
    if resume.exists():
        saved=torch.load(resume,weights_only=True);start=saved['step']
        net.load_state_dict(saved['net']);opt.load_state_dict(saved['opt'])
        rng.set_state(saved['rng']);torch.set_rng_state(saved['torch'])
    for step in range(start+1,STEPS[-1]+1):
        foe=torch.randint(nf,(b,1),generator=rng)
        ci,ri,j=[torch.randint(k,(b,g),generator=rng) for k in (n,r,g)]
        outcomes=world[ci,ri,j,foe];p=c[ci,ri,j][...,1:].float()
        views=motion[ci[:,0],ri[:,0],j[:,0],foe[:,0]]
        a,won,events=trajectory(net,outcomes,p,views,opener,train=True)
        for _ in range(3):
            logits,_=net.sequence(events,p)
            chosen=logits.gather(-1,a.unsqueeze(-1)).squeeze(-1)[:,1:]
            opt.zero_grad();F.binary_cross_entropy_with_logits(chosen,(won[:,1:]==1).float()).backward()
            torch.nn.utils.clip_grad_norm_(net.parameters(),.8);opt.step()
        if step in STEPS:evaluate(net.eval(),dev,coverage,folder,step)
        if step%64==0:
            buf=io.BytesIO();torch.save(dict(step=step,net=net.state_dict(),opt=opt.state_dict(),
                rng=rng.get_state(),torch=torch.get_rng_state()),buf);atomic(resume,buf.getvalue())


def compare(coverage, seed):
    # Re-run the locked first seed on the same physical cells for both coverage levels.
    selected=[7,coverage]
    while not all((DEST/f'covered-{n:02}/seed-{seed}/u2048.json').exists() for n in selected):
        time.sleep(10)
    seen={}; names=list(read(LIB/'protocol.json')['field'])
    for ncovered in selected:
        world,c,motion,wt,arms=load(ncovered,'dev')
        n,r,g,nf,na=world.shape;opener=arms.index('original-mixture')
        cfg=DEST/f'covered-{ncovered:02}/seed-{seed}'
        net=Actor(na,False);net.load_state_dict(torch.load(cfg/'u2048.pt',weights_only=True));net.eval()
        z=world.permute(0,1,3,2,4).reshape(-1,g,na)
        ctx=c[:,:,None].expand(n,r,nf,g,3).reshape(-1,g,3);p=ctx[...,1:].float()
        _,wins,_=trajectory(net,z,p,motion[:,:,0].reshape(-1,192),opener)
        seen[f'reward-{ncovered}']=wins.numpy()
        if tally(wins.numpy())!=read(cfg/'u2048.json')['scores']['memory']['games']:
            raise ValueError('Learned router did not reproduce its official fixture scores')
    train,tc,_,_,_=load(coverage,'train')
    ids=np.tile(np.arange(nf),n*r);rows=np.arange(len(z))[:,None];games=np.arange(g)[None,:]
    k=tc[...,1:].numpy()>=70;b=2*k[...,0]+k[...,1];test=(2*(p[...,0]>=70)+(p[...,1]>=70)).numpy()
    # Use the identical Laplace-smoothed observation and payoff model as expanded_crossplay.
    counts=np.stack([np.stack([(train.numpy()[b==i]==s).sum(0)+1 for s in (0,.5,1)],-1) for i in range(4)])
    rates=(counts/counts.sum(-1,keepdims=True))[...,2]
    # Use the exact contraction and axis order of route_expansion. A mean over
    # opponents rounds differently for tied actions. After the opener the
    # original blind belief inherits float32 from the motion encoder.
    for label,belief in [('blind',np.full((len(ids),nf),1/nf,dtype=np.float32)),('known-id',np.eye(nf)[ids])]:
        choice=np.full((len(ids),g),opener)
        for j in range(1,g):
            choice[:,j]=np.einsum('bf,bfa->ba',belief,rates[test[:,j]]).argmax(1)
        seen[label]=z.numpy()[rows,games,choice]
    seen['wtap']=wt.permute(0,1,3,2).reshape(-1,g).numpy()
    official=read(FOLDER/f'crossplay/routed-{coverage:02}.json')['scores']
    for k in ('blind','known-id','wtap'):
        if tally(seen[k])!=official[k]['games']:raise ValueError('Reference mismatch: '+k)
    out={};strong=seen[f'reward-{coverage}']
    for label,v in seen.items():
        delta=((strong==1).astype(float)-(v==1)).reshape(n,r,-1).mean(2)
        out[label]=dict(wdl=tally(v),delta=float(delta.mean()),conditional_95=bootstrap(delta),
            weakest=min(names,key=lambda f:(v[ids==names.index(f)]==1).sum()),against_wtap=tally(v[ids==names.index('wtap')]))
    write_json(DEST/f'covered-{coverage:02}/seed-{seed}/comparison.json',out,fixed=True)


if __name__=='__main__':
    if sys.argv[1]=='register':register()
    elif sys.argv[1]=='compare':compare(int(sys.argv[2]),int(sys.argv[3]))
    else:run(int(sys.argv[1]),int(sys.argv[2]))
