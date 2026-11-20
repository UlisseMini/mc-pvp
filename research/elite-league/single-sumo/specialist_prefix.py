"""Single-duel test of whether an exploiter can be selected before its opportunity passes."""
import io
import sys

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

from assay import java_rows, lower_contrast
from learner import CLOCKS, RTTS, Worker, atomic, write_json
from remote_league import read
from series_eval import tally
from series_targeted import DEST as LIB, bank
from series_trace import CHANNELS
from specialist_diagnosis import DATA, DEST, fixture_inputs

HEADS = (8, 24, 48)
STUDY = DEST/'single-duel'


def register():
    write_json(STUDY/'protocol.json',dict(primary=24,secondary=[8,48],training_repeats=8,development_repeats=4,
        arms='For each ordinary first-game fixture in the series panel, use the original broad expert to start. At decision 8, 24, or 48 branch to each of the nine real experts, primed on exactly the realized inputs. Include a no-switch branch. A fight ending before the cutoff keeps its actual outcome in every arm.',
        inference='Fit a per-opponent and ping table to the actual continuation payoffs on training clocks. Train a GRU opponent classifier and expected continuation advantages on the legal nine-channel prefix plus displayed pings. Compare soft classification plus table, direct regression, the latest observation only, a population ping table, keeping the original MLP, and privileged identity. Compare a switch to the dedicated counter with the exact expert from spawn.',
        information='Within each common seed, compute the fraction of the 351 opponent pairs that share exactly identical observed prefix bytes. Treat decisions after an already-finished match as unavailable. A difference can be detectable yet irrelevant to the best action. The fixed-fixture perfect-foresight score and its collision-constrained counterpart describe only this nine-action library.',
        scoring='All 27 development opponents, identical clocks and settings and plain W-tap from spawn. Confidence clusters by timing recording. The 24-decision soft posterior vs blind is the primary comparison. All 3 horizons and both legal choices recorded regardless of result. No development outcomes enter fitting.'),fixed=True)


def collect(split, shard, shards):
    spec=read(LIB/'protocol.json');foes=list(spec['field']);arms=list(spec['experts'])
    refs=[v['reference'] for v in spec['experts'].values()]
    opener=arms.index('original-mixture');others=[j for j in range(len(arms)) if j!=opener]
    reps=8 if split=='train' else 4
    world,_,_=bank(split,reps)
    worker=Worker(current_runtime=True)
    try:
        for i,clock in enumerate(CLOCKS[split]):
            if i%shards!=shard:continue
            path=DATA/'prefix'/split/f'{i:02}.npz'
            if path.exists():continue
            plan=[];prefix=[];lengths=[];branch=[]
            for r in range(reps):
                seat,own,other,x,y=next(fixture_inputs(split,r,i))
                rows=[[refs[opener],spec['field'][n]['reference'],clock,'ab'[seat],own,other,x,y] for n in foes]
                plan+=rows
                played=worker.run(rows,True)
                if not np.array_equal([[w,ms] for w,ms,_ in played],world[i,r,0,:,opener].numpy()):
                    raise ValueError('Prefix input capture changed ordinary fight')
                for _,_,v in played:
                    lengths.append(len(v))
                    prefix.append(v[np.minimum(np.arange(max(HEADS)),len(v)-1)][:,CHANNELS])
            for head in HEADS:
                cut=java_rows('ResponseReversal',plan,[','.join(refs[j] for j in others)],java_opts=[f'-Dleague.commitHeads={head}'])
                values=np.zeros((len(plan),len(refs)),np.float32)
                for row,w,v,length in zip(values,world[i,:,0,:,opener,0].reshape(-1),cut,lengths):
                    if w!=v['full'] or (v['cut'] is None)!=(length<head):raise ValueError('Invalid cutoff or replay')
                    row[:]=w
                    if v['cut'] is not None:
                        for j in others:row[j]=v[refs[j]]
                branch.append(values)
            buf=io.BytesIO();np.savez_compressed(buf,prefix=np.stack(prefix),lengths=lengths,values=np.stack(branch))
            atomic(path,buf.getvalue())
    finally:worker.close()
    write_json(STUDY/f'collection/{split}-{shard}-of-{shards}.json',dict(complete=True),fixed=True)


class PrefixReader(nn.Module):
    def __init__(self,nf,na):
        super().__init__()
        self.encode=nn.GRU(9,48,batch_first=True)
        self.hidden=nn.Sequential(nn.Linear(50,64),nn.SiLU())
        self.identity=nn.Linear(64,nf);self.advantage=nn.Linear(64,na)

    def forward(self,seq,ping):
        h=self.hidden(torch.cat((self.encode(seq)[1][0],ping),1))
        return self.identity(h),self.advantage(h)


def arrays(split):
    reps=8 if split=='train' else 4
    world,c,wt=bank(split,reps);nc,nr,_,nf,na,_=world.shape
    seq=[];values=[];lengths=[]
    for i in range(nc):
        with np.load(DATA/'prefix'/split/f'{i:02}.npz') as saved:
            seq.append(saved['prefix']);values.append(saved['values']);lengths.append(saved['lengths'])
    ping=c[:,:,0,None,1:].expand(nc,nr,nf,2).reshape(-1,2)
    seat=c[:,:,0,None,0].expand(nc,nr,nf).reshape(-1)
    return (torch.from_numpy(np.concatenate(seq)),ping,seat.numpy(),np.tile(np.arange(nf),nc*nr),
            np.concatenate(values,axis=1),np.concatenate(lengths),world[:,:,0,:,:,0].reshape(-1,na).numpy(),
            wt[:,:,0,:].reshape(-1).numpy() if split!='train' else None)


def fit(train,head,i,na,nf,mode):
    seq,ping,_,ids,vals,*_=train
    x=seq[:,head-1:head] if mode=='last' else seq[:,:head]
    win=torch.from_numpy((vals[i]==1).astype(np.float32));advantages=win-win.mean(1,keepdim=True)
    torch.manual_seed(38514);m=PrefixReader(nf,na);opt=torch.optim.AdamW(m.parameters(),lr=.001)
    gen=torch.Generator().manual_seed(7611)
    for _ in range(36):
        for j in torch.randperm(len(x),generator=gen).split(128):
            pred,q=m(x[j],ping[j].float()/150)
            loss=F.cross_entropy(pred,torch.from_numpy(ids[j]),label_smoothing=.05)+2*F.mse_loss(q,advantages[j])
            opt.zero_grad();loss.backward();nn.utils.clip_grad_norm_(m.parameters(),1);opt.step()
    return m.eval()


def grade():
    spec=read(LIB/'protocol.json');foes=list(spec['field']);arms=list(spec['experts']);nf=len(foes);na=len(arms)
    opener=arms.index('original-mixture');pairs=read(DEST/'protocol.json')['dedicated']
    train=arrays('train');seq,ping,seat,ids,dev,lengths,spawn,wt=arrays('dev')
    p=ping.numpy()>=70;bins=2*p[:,0]+p[:,1];rows=np.arange(len(ids))
    pt=train[1].numpy()>=70;bt=2*pt[:,0]+pt[:,1]
    out={}
    for i,h in enumerate(HEADS):
        win=train[4][i]==1
        table=np.stack([[win[(bt==b)&(train[3]==j)].mean(0) for j in range(nf)] for b in range(4)])
        if not np.isfinite(table).all():raise ValueError('Missing training bin')
        q=dev[i];res={'keep':q[:,opener],'wtap':wt,'known-id':q[rows,table[bins,ids].argmax(1)],
            'blind':q[rows,table.mean(1)[bins].argmax(1)],'foresight':q.max(1)}
        accuracy={}
        for mode in ('prefix','last'):
            model=fit(train,h,i,na,nf,mode)
            x=seq[:,h-1:h] if mode=='last' else seq[:,:h]
            with torch.inference_mode():logits,adv=model(x,ping.float()/150)
            posterior=logits.softmax(1).numpy()
            res[mode]=q[rows,np.einsum('bf,bfa->ba',posterior,table[bins]).argmax(1)]
            res[mode+'-direct']=q[rows,adv.numpy().argmax(1)]
            accuracy[mode]=float((posterior.argmax(1)==ids).mean())
        wins=0;collisions=0;eligible_pairs=0
        for first in range(0,len(ids),nf):
            groups={}
            for k in range(first,first+nf):
                if lengths[k]<h:
                    wins+=int(q[k,0]==1)
                    continue
                groups.setdefault(seq[k,:h].numpy().tobytes(),[]).append(k)
            num=sum(map(len,groups.values()));eligible_pairs+=num*(num-1)//2
            for group in groups.values():
                collisions+=len(group)*(len(group)-1)//2
                wins+=int((q[group]==1).sum(0).max())
        counts={k:dict(games=tally(v),seats=[tally(v[seat==j]) for j in (0,1)],
            rtts={f'{a}/{b}':tally(v[(ping[:,0].numpy()==a)&(ping[:,1].numpy()==b)]) for a,b in RTTS},
            opponents={f:tally(v[ids==j]) for j,f in enumerate(foes)}) for k,v in res.items()}
        dedicated={}
        for f,arm in pairs.items():
            mask=ids==foes.index(f);a=arms.index(arm)
            dedicated[f]=dict(dedicated_spawn=tally(spawn[mask,a]),dedicated_switch=tally(q[mask,a]),
                    keep=tally(res['keep'][mask]),adaptive=tally(res['prefix'][mask]),wtap=tally(wt[mask]))
        effects={}
        for a,b in [('prefix','blind'),('prefix','keep'),('known-id','blind'),('prefix','last'),('prefix-direct','blind')]:
            d=((res[a]==1).astype(float)-(res[b]==1)).reshape(len(CLOCKS['dev']),-1).mean(1)
            effects[a+' minus '+b]=dict(delta=float(d.mean()),bounds=lower_contrast(d,.05/(2*len(HEADS)*5)))
        out[str(h)]=dict(scores=counts,effects=effects,dedicated=dedicated,accuracy=accuracy,ended_early=int((lengths<h).sum()),
            indistinguishable_pairs=collisions,eligible_pairs=eligible_pairs,collision_constrained_foresight_wins=wins)
    write_json(STUDY/'dev.json',out,fixed=True)


if __name__=='__main__':
    if sys.argv[1]=='register':register()
    elif sys.argv[1]=='grade':
        import time
        while not all((STUDY/f'collection/{s}-{j}-of-2.json').exists() for s in ('train','dev') for j in range(2)):time.sleep(5)
        grade()
    else:collect(sys.argv[1],int(sys.argv[2]),int(sys.argv[3]))
