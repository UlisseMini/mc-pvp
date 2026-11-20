"""Learn a response from what the bot actually saw during its first complete duel."""
import hashlib
import io
import random
import sys
import time

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

from assay import lower_contrast
from learner import CLOCKS,ROOT,Worker,atomic,write_json
from remote_league import read
from series_bank import CACHE,DEV_REPS,FOLDER,ROUNDS
from series_eval import series,tally
from series_hidden import donor_rows
from series_targeted import DEST as LIB,bank

DEST=LIB/'opening-observations'
CHANNELS=(0,1,2,3,4,8,9,10,11)
MODES=('feedback','last','trace')


def register():
    write_json(DEST/'protocol.json',dict(
        question='The new specialist lifted the weakest matchup but the blind learner could simply play it. Can observing a whole opponent in the first bout close more of the remaining gap to the training-only ID lookup?',
        opening='Always use the original 131072-fight broad MLP in game one. Reconstruct the exact opening from each frozen series fixture. Check its ring-out, elapsed milliseconds, seat and both pings against the saved bank before storing any observations.',
        public_inputs='Store only nine inputs to the opening policy: relative horizontal distance and height, the two components of own horizontal motion in target coordinates, own vertical motion, grounded, sprinting, and the two visible hurt animations. Retain the first 16 observations and 24 chronological means across the completed duel. Also expose the public win/draw/loss, time rounded to 0.1 seconds and both pings. No other POV, opponent identity or referee state enters the policy.',
        learning='At the 8-schedule budget, train three equal-seed recurrent networks using only scoreboard feedback, additionally the last observed state, or the opening motion trace. From training recordings predict expected points for each expert at each current public ping bin, supervised by averages for the known training opponent from the opposite half of timing recordings. An auxiliary opponent-ID classifier sees identities only during training. After game one, keep this single learned memory fixed. Grade actual ring-outs and series titles, not classification accuracy alone.',
        controls='Include a ping-only table, every fixed response after the common opener, a privileged train-fitted opponent-ID table, the unrestricted original nine-game policy and matched plain W-tap. Swap first-game observations with another timing recording matching all pings and seats from the same or a consistently different opponent. Also replace just motion while retaining real public feedback. Report timing-recording bounds and both seat and latency spreads. These are exposed 27-opponent development results.'),fixed=True)


def summarize(rows):
    if not len(rows):raise ValueError('No decisions in the opening game')
    n=len(rows);early=rows[np.minimum(np.arange(16),n-1)]
    pooled=[rows[i*n//24:max(i*n//24+1,(i+1)*n//24)].mean(0) for i in range(24)]
    return np.concatenate((early,np.stack(pooled))).astype(np.float32)


def load_traces(split,reps,data,c,opening):
    spec=read(LIB/'protocol.json');foes=list(spec['field']);opener=spec['experts']['original-mixture']['reference']
    base=CACHE/'opening-observations'/split;clocks=CLOCKS[split]
    worker=Worker(current_runtime=True);out=[];last=[]
    try:
        for i,clock in enumerate(clocks):
            first=[];final=[]
            for r in range(reps):
                path=base/f'{i:02}-{r:02}.npz'
                if not path.exists():
                    rng=random.Random(f'sumo-series-434811/{split}/{r}/{i}')
                    from learner import RTTS
                    seat=(r+i)%2;a,b=RTTS[(i+r//2)%len(RTTS)]
                    if split=='train' and rng.random()<.5:a,b=rng.randrange(151),rng.randrange(151)
                    if [seat,a,b]!=c[i,r,0].tolist():raise ValueError('Opening schedule changed')
                    x,y=rng.getrandbits(60),rng.getrandbits(60)
                    plan=[[opener,spec['field'][n]['reference'],clock,'ab'[seat],a,b,x,y] for n in foes]
                    played=worker.run(plan,True)
                    actual=np.array([[w,ms] for w,ms,_ in played],np.float32)
                    if not np.array_equal(actual,data[i,r,0,:,opening].numpy()):raise ValueError('Observed opener diverged from the ordinary duel')
                    visible=[rows[:,CHANNELS] for _,_,rows in played]
                    if not all(np.array_equal(v[0],visible[0][0]) for v in visible):raise ValueError('Hidden opponent changed the spawn observation')
                    buf=io.BytesIO();np.savez_compressed(buf,trace=np.stack([summarize(v) for v in visible]),last=np.stack([v[-1] for v in visible]))
                    atomic(path,buf.getvalue())
                with np.load(path) as saved:first.append(saved['trace']);final.append(saved['last'])
            out.append(np.stack(first));last.append(np.stack(final))
    finally:worker.close()
    return torch.from_numpy(np.stack(out)),torch.from_numpy(np.stack(last))


class TraceResponse(nn.Module):
    def __init__(self,experts,opponents):
        super().__init__()
        self.encode=nn.GRU(len(CHANNELS),48,batch_first=True)
        self.combine=nn.Sequential(nn.Linear(54,64),nn.Tanh())
        self.payoff=nn.Linear(64,4*experts);self.identity=nn.Linear(64,opponents)

    def forward(self,trace,feedback,mode):
        hidden=feedback.new_zeros(len(feedback),48) if mode=='feedback' else self.encode(trace)[1][0]
        h=self.combine(torch.cat((feedback,hidden),1))
        return self.payoff(h).reshape(len(h),4,-1),self.identity(h)


def inputs(data,c,traces,opening):
    nc,nr,_,nf,_,_=data.shape
    w,ms=data[:,:,0,:,opening].reshape(-1,2).unbind(1)
    p=c[:,:,0,None,1:].expand(nc,nr,nf,2).reshape(-1,2)
    ms=100*torch.floor((ms+50)/100)
    feedback=torch.cat((F.one_hot((2*w).long(),3),torch.log1p(ms/1000)[:,None]/5,p.float()/150),1)
    trace,last=traces
    return trace.flatten(0,2),last.reshape(-1,1,last.shape[-1]),feedback,torch.arange(nf).repeat(nc*nr)


def tables(data,c):
    points=data[...,0].numpy();ping=c[...,1:].numpy()>=70;bins=2*ping[...,0]+ping[...,1]
    return np.stack([points[bins==k].mean(0) for k in range(4)],1)


def load_actor(role,na,nf):
    folder=DEST/role;receipt=read(folder/'trained.json');raw=(folder/'actor.pt').read_bytes()
    if hashlib.sha256(raw).hexdigest()!=receipt['sha256']:raise ValueError('Opening observer changed')
    model=TraceResponse(na,nf);model.load_state_dict(torch.load(io.BytesIO(raw),weights_only=True));return model.eval()


def fit(role,features,targets,na,nf):
    folder=DEST/role
    if (folder/'trained.json').exists():return load_actor(role,na,nf)
    trace,last,public,foes=features;x=last if role=='last' else trace
    torch.manual_seed(67219);model=TraceResponse(na,nf);opt=torch.optim.AdamW(model.parameters(),lr=.001)
    rng=torch.Generator().manual_seed(31067)
    for epoch in range(36):
        for idx in torch.randperm(len(x),generator=rng).split(128):
            pred,identity=model(x[idx],public[idx],role)
            loss=F.mse_loss(pred,targets[idx])+.075*F.cross_entropy(identity,foes[idx])
            opt.zero_grad();loss.backward();nn.utils.clip_grad_norm_(model.parameters(),1);opt.step()
    buf=io.BytesIO();torch.save(model.state_dict(),buf);atomic(folder/'actor.pt',buf.getvalue())
    write_json(folder/'trained.json',dict(sha256=hashlib.sha256(buf.getvalue()).hexdigest(),epochs=36,openers=len(x),
        seed=67219,role=role,channels=CHANNELS),fixed=True)
    return model.eval()


def run():
    while not (LIB/'dev/comparison-08.json').exists():time.sleep(15)
    spec=read(LIB/'protocol.json');names=list(spec['field']);experts=list(spec['experts']);opening=experts.index('original-mixture')
    train,c,_=bank('train',8);tr=inputs(train,c,load_traces('train',8,train,c,opening),opening)
    nclock,nrep,_,nf,na,_=train.shape
    # Center action values within each opponent and ping. Absolute strength does not identify the better response.
    targets=np.stack([tables(train[1-i%2::2],c[1-i%2::2]) for i in range(nclock)])
    targets=12*(targets-targets.mean(-1,keepdims=True))
    targets=torch.from_numpy(np.repeat(targets[:,None],nrep,axis=1).reshape(-1,4,na))
    models={role:fit(role,tr,targets,na,nf) for role in MODES}
    data,c,wt=bank('dev',DEV_REPS);nc,nr=data.shape[:2];trace,last,public,ids=inputs(data,c,load_traces('dev',DEV_REPS,data,c,opening),opening)
    world=data.permute(0,1,3,2,4,5).reshape(-1,ROUNDS,na,2).numpy()
    ctx=c[:,:,None].expand(nc,nr,nf,ROUNDS,3).reshape(-1,ROUNDS,3).numpy();idx=np.arange(len(ids));time_index=np.arange(ROUNDS)[None,:]
    ping=ctx[...,1:]>=70;bins=2*ping[...,0]+ping[...,1]
    same,wrong=donor_rows(c,nf);curves={};scores={};accuracy={}
    with torch.no_grad():
        for role in MODES:
            m=models[role];x=last if role=='last' else trace
            variants=[(role,idx,idx)] if role!='trace' else [('trace',idx,idx),('same',same,same),('wrong',wrong,wrong),
                ('same-motion',same,idx),('wrong-motion',wrong,idx)]
            for label,move,fb in variants:
                q,identity=m(x[move],public[fb],role);curves[label]=q.numpy()
                accuracy[label]=float((identity.argmax(1)==ids).float().mean())
    train_c=bank('train',8)[1];oracle=tables(train,train_c)
    curves['known-id']=oracle[ids.numpy()]
    curves['ping-only']=np.broadcast_to(oracle.mean(0),(len(ids),4,na))
    choices={}
    for label,q in curves.items():
        a=q[idx[:,None],bins].argmax(-1);a[:,0]=opening
        choices[label]=a;scores[label]=world[idx[:,None],time_index,a,0]
    for k,n in enumerate(experts):
        values=world[:,:,k,0].copy();values[:,0]=world[:,0,opening,0];scores['fixed-'+n]=values
    scores['wtap']=wt.permute(0,1,3,2).reshape(-1,ROUNDS).numpy()
    from series_eval import load_models,matches
    scores['unrestricted-gru']=matches(load_models(8,9,na,nf,LIB),torch.from_numpy(world),torch.from_numpy(ctx),ids.numpy(),np.repeat(np.arange(nc),nr*nf))['won']
    comparisons=[('trace','feedback'),('trace','last'),('trace','ping-only'),('trace','unrestricted-gru'),
                 ('trace','wrong'),('same','wrong'),('trace','wrong-motion'),('same-motion','wrong-motion')]
    effects={}
    for a,b in comparisons:
        for unit,vals in [('games',scores),('series',{n:series(v) for n,v in scores.items()})]:
            d=((vals[a]==1).astype(float)-(vals[b]==1)).reshape(nc,-1).mean(1)
            effects[f'{a} minus {b} {unit}']=dict(mean=float(d.mean()),adjusted_bounds=lower_contrast(d,.05/(4*len(comparisons))))
    counts={n:dict(series=tally(series(w)),games=tally(w),second=tally(w[:,1]),
        opponents={foe:dict(series=tally(series(w[ids.numpy()==j])),games=tally(w[ids.numpy()==j])) for j,foe in enumerate(names)},
        seat={s:tally(w[ctx[...,0]==j]) for j,s in enumerate('ab')},
        rtt={f'{a}/{b}':tally(w[(ctx[...,1]==a)&(ctx[...,2]==b)]) for a in (31,109) for b in (31,109)}) for n,w in scores.items()}
    write_json(DEST/'dev/result.json',dict(series_per_arm=len(ids),scores=counts,effects=effects,opponent_top1=accuracy,
        trace_second_choices={foe:np.bincount(choices['trace'][ids.numpy()==j,1],minlength=na).tolist() for j,foe in enumerate(names)},
        experts=experts,unique_opening_traces=dict(train=len(tr[-1]),development=len(ids))),fixed=True)


if __name__=='__main__':
    if sys.argv[1:]==['register']:register()
    else:run()
