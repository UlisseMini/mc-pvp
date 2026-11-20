"""Recover legal target-motion channels lost by the reduced nine-channel prefix observer."""
import io
import sys
import time

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

from learner import CLOCKS, RTTS, Worker, atomic, write_json
from remote_league import read
from series_eval import tally
from series_targeted import DEST as LIB,bank
from series_trace import CHANNELS
from specialist_ablation import bootstrap
from specialist_diagnosis import DATA, fixture_inputs
from specialist_prefix import HEADS, STUDY as PREFIX, arrays

DEST=PREFIX/'visible-target-motion'
KEEP=(*CHANNELS,5,6,7)


def register():
    write_json(DEST/'protocol.json',dict(channels=KEEP,
        reason='The reduced nine-channel opener caps horizontal distance at five blocks although sumo starts eight blocks apart. The ordinary legal combat MLP already sees its own client’s apparent target displacement as channels 5,6,7. The reduced observer discarded all three. Compare adding only those channels, early enough to matter, independently of training volume.',
        design='Train matched GRUs to infer the frozen rival from a common legal opening at decisions 8,24,48. Cross the original nine versus twelve channels with one versus ten opening fixtures per training series schedule. Freeze the same per-opponent and ping table estimated from actual fork outcomes. Report exact collision rates, 27-way recognition, and outright wins of the chosen real continuation versus the nine-channel control, a ping-only table, a dedicated counter where available, and plain W-tap. No future observations or privileged velocities enter. The expanded training set comprises 34560 ordinary observations of the broad opener with independent game seeds, including the original 3456.'),fixed=True)


def collect(split,shard):
    spec=read(LIB/'protocol.json');names=list(spec['field']);arms=list(spec['experts'])
    opening=arms.index('original-mixture');ref=spec['experts']['original-mixture']['reference'];reps=8 if split=='train' else 4
    world,_,_=bank(split,reps);worker=Worker(current_runtime=True)
    try:
        for i,clock in enumerate(CLOCKS[split]):
            if i%2!=shard:continue
            for r in range(reps):
                path=DATA/'opponent-motion'/split/f'{i:02}-{r:02}.npz'
                if path.exists():continue
                with np.load(DATA/'prefix'/split/f'{i:02}.npz') as saved:
                    original=saved['prefix'][r*len(names):(r+1)*len(names)]
                samples=[]
                for g,(s,p,q,x,y) in enumerate(fixture_inputs(split,r,i)):
                    if g and split!='train':break
                    rows=[[ref,spec['field'][n]['reference'],clock,'ab'[s],p,q,x,y] for n in names]
                    played=worker.run(rows,True)
                    if not np.array_equal([[w,ms] for w,ms,_ in played],world[i,r,g,:,opening].numpy()):raise ValueError('Changed ordinary fight')
                    seq=np.stack([v[np.minimum(np.arange(48),len(v)-1)][:,KEEP] for _,_,v in played])
                    if g==0 and not np.array_equal(seq[:,:,:9],original):raise ValueError('Unchanged nine features differ')
                    samples.append(seq)
                buf=io.BytesIO();np.savez_compressed(buf,trace=np.stack(samples));atomic(path,buf.getvalue())
    finally:worker.close()
    write_json(DEST/f'collection/{split}-{shard}.json',dict(complete=True),fixed=True)


def samples(split):
    reps=8 if split=='train' else 4
    _,p,_=bank(split,reps);x=[];c=[];num=10 if split=='train' else 1
    for i in range(len(CLOCKS[split])):
        for r in range(reps):
            with np.load(DATA/'opponent-motion'/split/f'{i:02}-{r:02}.npz') as z:x.append(z['trace'])
            c.append(np.broadcast_to(p[i,r,:num,None,1:].numpy(),(num,x[-1].shape[1],2)))
    return np.stack(x),np.stack(c)


class Reader(nn.Module):
    def __init__(self,channels,nf):
        super().__init__();self.encoder=nn.GRU(channels,48,batch_first=True)
        self.head=nn.Sequential(nn.Linear(50,64),nn.SiLU(),nn.Linear(64,nf))
    def forward(self,x,p):
        return self.head(torch.cat((self.encoder(x)[1][0],p/150),1))


def run(budget,channels):
    while not all((DEST/f'collection/{s}-{i}.json').exists() for i in range(2) for s in ('train','dev')):time.sleep(5)
    spec=read(LIB/'protocol.json');foes=list(spec['field']);nf=len(foes)
    x,p=samples('train');test,tp=samples('dev');x=x[:,:budget];p=p[:,:budget]
    x=torch.from_numpy(x.reshape(-1,48,12))[:,:,:channels];p=torch.from_numpy(p.reshape(-1,2)).float()
    test=torch.from_numpy(test.reshape(-1,48,12))[:,:,:channels];tp=torch.from_numpy(tp.reshape(-1,2)).float()
    ids=torch.arange(nf).repeat(len(x)//nf);truth=np.tile(np.arange(nf),len(test)//nf)
    _,tping,_,ti,tval,*_=arrays('train');_,dping,seat,di,dval,lengths,_,wt=arrays('dev')
    if not np.array_equal(di,truth) or not torch.equal(dping.float(),tp):raise ValueError('Prefix and fork fixtures differ')
    tb=2*(tping[:,0]>=70).numpy()+(tping[:,1]>=70).numpy();db=2*(tp[:,0]>=70).numpy()+(tp[:,1]>=70).numpy()
    rr=np.arange(len(truth));report={};root=DEST/f'n{budget}-c{channels}'
    for i,h in enumerate(HEADS):
        train_win=tval[i]==1;table=np.stack([[train_win[(tb==j)&(ti==f)].mean(0) for f in range(nf)] for j in range(4)])
        torch.manual_seed(84556);net=Reader(channels,nf);opt=torch.optim.AdamW(net.parameters(),lr=.001)
        gen=torch.Generator().manual_seed(84556)
        for _ in range(24):
            for j in torch.randperm(len(x),generator=gen).split(256):
                pred=net(x[j,:h],p[j]);loss=F.cross_entropy(pred,ids[j],label_smoothing=.03)
                opt.zero_grad();loss.backward();nn.utils.clip_grad_norm_(net.parameters(),1.);opt.step()
        with torch.inference_mode():post=net(test[:,:h],tp).softmax(1).numpy()
        choice=np.einsum('bf,bfa->ba',post,table[db]).argmax(1);w=dval[i]
        results={'adaptive':w[rr,choice],'blind':w[rr,table.mean(1)[db].argmax(1)],'true-id':w[rr,table[db,truth].argmax(1)],'wtap':wt}
        counts={n:dict(games=tally(z),opponents={f:tally(z[truth==j]) for j,f in enumerate(foes)},
            rtt={f'{a}/{b}':tally(z[(tp[:,0].numpy()==a)&(tp[:,1].numpy()==b)]) for a,b in RTTS},
            seat={s:tally(z[seat==j]) for j,s in enumerate('ab')}) for n,z in results.items()}
        collision=0
        for row in range(0,len(truth),nf):
            chunk=test[row:row+nf,:h][lengths[row:row+nf]>=h].numpy()
            _,count=np.unique(chunk.reshape(len(chunk),-1),axis=0,return_counts=True)
            collision+=sum((count*(count-1)//2).tolist())
        diff=((results['adaptive']==1).astype(float)-(results['blind']==1)).reshape(len(CLOCKS['dev']),4,-1).mean(2)
        report[str(h)]=dict(scores=counts,accuracy=float((post.argmax(1)==truth).mean()),identical_pairs=int(collision),
            gain_over_blind=float(diff.mean()),conditional_95=bootstrap(diff))
        buf=io.BytesIO();torch.save(net.state_dict(),buf);atomic(root/f'h{h}.pt',buf.getvalue())
    write_json(root/'result.json',dict(training_openers=len(x),test_openers=len(test),heads=report),fixed=True)


if __name__=='__main__':
    if sys.argv[1]=='register':register()
    elif sys.argv[1]=='fit':run(int(sys.argv[2]),int(sys.argv[3]))
    else:collect(sys.argv[1],int(sys.argv[2]))
