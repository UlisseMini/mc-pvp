"""Route to frozen experts using a posterior over rivals learned from legal observations."""
import io
import sys

import numpy as np
import torch
from scipy.special import logsumexp
from torch import nn
from torch.nn import functional as F

from assay import lower_contrast
from learner import CLOCKS, RTTS, atomic, write_json
from remote_league import read
from series_eval import load_models, matches, series, tally
from series_hidden import donor_rows
from series_targeted import DEST as LIB, bank
from specialist_diagnosis import DATA, DEST, DEV_REPS, TRAIN_REPS

SEEDS = (5213, 5214, 5215)


def payoffs(world, ctx, outright):
    scores = world[..., 0]
    if outright:
        scores = scores == 1
    p = ctx[..., 1:] >= 70
    bins = 2 * p[..., 0] + p[..., 1]
    return np.stack([scores[bins == i].mean(axis=0) for i in range(4)])


def layouts(split, reps):
    world, ctx, wt = bank(split, reps)
    n, r, g, f, a, _ = world.shape
    games = world.permute(0, 1, 3, 2, 4, 5).reshape(-1, g, a, 2).numpy()
    cues = ctx[:, :, None].expand(n, r, f, g, 3).reshape(-1, g, 3).numpy()
    return games, cues, np.tile(np.arange(f), n*r), ctx, wt


def diagnostics():
    spec = read(LIB / 'protocol.json')
    experts, foes = list(spec['experts']), list(spec['field'])
    train, context, _ = bank('train', TRAIN_REPS)
    games, cues, ids, clocks, wt = layouts('dev', DEV_REPS)
    win = payoffs(train.numpy(), context.numpy(), True)
    points = payoffs(train.numpy(), context.numpy(), False)
    ix = np.arange(len(ids))[:, None]
    g = np.arange(10)[None, :]
    ping = 2*(cues[..., 1] >= 70)+(cues[..., 2] >= 70)
    opening = experts.index('original-mixture')
    runs = {name: games[:, :, j, 0] for j, name in enumerate(experts)}
    runs['wtap'] = wt.permute(0, 1, 3, 2).reshape(-1, 10).numpy()
    fixed = int(win.mean((0, 1)).argmax())
    runs['train-best-fixed'] = runs[experts[fixed]]
    for name, table in [('id-ringout', win), ('id-points', points), ('ping-ringout', win.mean(1, keepdims=True)),
                        ('ping-points', points.mean(1, keepdims=True))]:
        actions = table[ping, ids[:, None] if table.shape[1] > 1 else 0].argmax(-1)
        runs[name+'-spawn'] = games[ix, g, actions, 0]
        actions[:, 0] = opening
        runs[name] = games[ix, g, actions, 0]
    n, r = clocks.shape[:2]
    runs['previous-ppo'] = matches(load_models(8,9,len(experts),len(foes),LIB), torch.from_numpy(games),
        torch.from_numpy(cues), ids, np.repeat(np.arange(n), r*len(foes)))['won']
    pairs = read(DEST/'protocol.json')['dedicated']
    def report(v):
        return dict(games=tally(v), series=tally(series(v)), by_game=[tally(x) for x in v.T],
                    seats=[tally(v[cues[...,0] == s]) for s in (0,1)],
                    rtts={f'{a}/{b}':tally(v[(cues[...,1]==a)&(cues[...,2]==b)]) for a,b in RTTS},
                    opponents={name:tally(v[ids==i]) for i,name in enumerate(foes)})
    results = {k:report(v) for k,v in runs.items()}
    arms = ['id-ringout','id-points','ping-ringout','previous-ppo','train-best-fixed','original-mixture','wtap']
    dedicated = {foe:{**{k:tally(runs[k][ids==foes.index(foe)]) for k in arms},
                         'dedicated':tally(runs[arm][ids==foes.index(foe)]), 'expert':arm} for foe,arm in pairs.items()}
    write_json(DEST/'dev/headroom.json',dict(dedicated=dedicated, scores=results, training_best_fixed=experts[fixed],
        max_ex_post_library_win=float((games[...,0]==1).any(-1).mean()), experts=experts),fixed=True)


def visible_feedback(score, ms, ping):
    rounded = 100*np.floor((ms+50)/100)
    return np.concatenate((np.eye(3)[(2*score).astype(int)], np.log1p(rounded[...,None]/1000)/5, ping/150), axis=-1).astype(np.float32)


class Decoder(nn.Module):
    def __init__(self, n_arms, n_foes, motion):
        super().__init__()
        self.motion = motion
        self.gru = nn.GRU(9, 32, batch_first=True) if motion else None
        self.movement = nn.Sequential(nn.Linear(192,64),nn.LayerNorm(64),nn.SiLU()) if motion else None
        self.trunk = nn.Sequential(nn.Linear(n_arms+6+(96 if motion else 0),128),nn.LayerNorm(128),nn.SiLU(),
                                   nn.Linear(128,96),nn.SiLU(),nn.Linear(96,n_foes))

    def forward(self, x):
        if self.motion:
            sequence = x[:, :360].reshape(-1,40,9)
            hidden = self.gru(sequence)[1][0]
            x = torch.cat((hidden,self.movement(x[:,360:552]),x[:,552:]),1)
        else:
            x = x[:,552:]
        return self.trunk(x)


def features(split, reps, world, ctx, nf, na):
    features, labels = [], []
    nclock = len(CLOCKS[split])
    for i in range(nclock):
        for r in range(reps):
            with np.load(DATA/split/f'{i:02}-{r:02}.npz') as saved:
                x = saved['features']
                arms = saved['arms']
            n = x.shape[2]
            play = world[i,r].numpy()[np.arange(10)[:,None,None],np.arange(nf)[None,:,None],arms[:,None,:]]
            actions = np.broadcast_to(arms[:,None,:],(10,nf,n))
            ping = np.broadcast_to(ctx[i,r,:,None,None,1:].numpy(),(10,nf,n,2))
            extra = np.concatenate((visible_feedback(play[...,0],play[...,1],ping),np.eye(na,dtype=np.float32)[actions]),-1)
            features.append(np.concatenate((x,extra),-1))
            labels.append(np.broadcast_to(np.arange(nf)[None,:,None],(10,nf,n)))
    shape=(nclock,reps,10,nf,n)
    return torch.from_numpy(np.stack(features).reshape(-1,x.shape[-1]+6+na)),torch.from_numpy(np.stack(labels).ravel().copy()),shape


def fit(x, y, na, nf, motion, seed, fraction):
    folder = DEST/'models'/f'{"motion" if motion else "feedback"}-{seed}-n{fraction}'
    torch.manual_seed(seed)
    m = Decoder(na,nf,motion)
    receipt = folder/'fit.json'
    if receipt.exists():
        m.load_state_dict(torch.load(folder/'actor.pt',weights_only=True))
        return m.eval()
    x, y = x[:len(x)*fraction//8], y[:len(y)*fraction//8]
    opt=torch.optim.AdamW(m.parameters(),lr=.001)
    rng=torch.Generator().manual_seed(seed)
    epochs=18
    for epoch in range(epochs):
        for idx in torch.randperm(len(y),generator=rng).split(256):
            loss=F.cross_entropy(m(x[idx]),y[idx],label_smoothing=.03)
            opt.zero_grad();loss.backward();nn.utils.clip_grad_norm_(m.parameters(),1.5);opt.step()
    data=io.BytesIO();torch.save(m.state_dict(),data);atomic(folder/'actor.pt',data.getvalue())
    import hashlib
    write_json(receipt,dict(seed=seed,training_games=len(y),epochs=epochs,sha256=hashlib.sha256(data.getvalue()).hexdigest()),fixed=True)
    return m.eval()


def rollout(logits, table, games, cues, ids, opener, history, donors=None):
    size,n,na,_=games.shape
    rows=np.arange(size); source=rows if donors is None else donors
    nf=table.shape[1]
    actions=np.zeros((size,n),int);outcomes=np.empty((size,n));acc=[];mass=[]
    evidence=np.zeros((size,n,nf))
    for g in range(n):
        p=cues[:,g,1:]>=70;bins=2*p[:,0]+p[:,1]
        if history=='oracle':
            belief=np.eye(nf)[ids]
        else:
            if history=='first':
                logp=evidence[:,:min(g,1)].sum(1)
            elif history=='latest':
                logp=evidence[:,max(0,g-1):g].sum(1)
            elif history=='none':
                logp=np.zeros((size,nf))
            else:
                logp=evidence[:,:g].sum(1)
            belief=np.exp(logp-logsumexp(logp,axis=1,keepdims=True))
        a=np.einsum('bf,bfa->ba',belief,table[bins]).argmax(-1) if g else np.full(size,opener)
        actions[:,g]=a;outcomes[:,g]=games[rows,g,a,0]
        acc.append(float((belief.argmax(1)==ids).mean()));mass.append(float(belief[rows,ids].mean()))
        if logits is not None:
            # For the donor intervention give the bot the donor's observation of the actor it actually chose.
            evidence[:,g]=logits[source,g,a]
    return outcomes,acc,mass,actions


def run(seed, fraction):
    spec=read(LIB/'protocol.json');experts=list(spec['experts']);foes=list(spec['field'])
    na,nf=len(experts),len(foes);opener=experts.index('original-mixture')
    train,context,_=bank('train',TRAIN_REPS);dev,c,wt=bank('dev',DEV_REPS)
    x,y,_=features('train',TRAIN_REPS,train,context,nf,na)
    d,_,shape=features('dev',DEV_REPS,dev,c,nf,na)
    games,cues,ids,_,_=layouts('dev',DEV_REPS)
    table=payoffs(train.numpy(),context.numpy(),True);same,other=donor_rows(c,nf)
    results={}; paths={}
    for motion in (False,True):
        role='motion' if motion else 'feedback'
        model=fit(x,y,na,nf,motion,seed,fraction)
        with torch.inference_mode():
            logits=torch.cat([F.log_softmax(model(batch),1) for batch in d.split(512)]).numpy()
        logits=logits.reshape(*shape,nf).transpose(0,1,3,2,4,5).reshape(-1,10,na,nf)
        variants=[(role,'all',None)] if not motion else [('motion','all',None),('first','first',None),('latest','latest',None),
            ('same','all',same),('wrong','all',other),('blind','none',None),('known-id','oracle',None)]
        for name,h,donors in variants:
            w,acc,mass,act=rollout(logits,table,games,cues,ids,opener,h,donors)
            paths[name]=w
            results[name]=dict(accuracy=acc,true_probability=mass,rounds=[tally(v) for v in w.T],
                               action_counts=np.bincount(act.ravel(),minlength=na).tolist())
    paths['wtap']=wt.permute(0,1,3,2).reshape(-1,10).numpy()
    n,r=c.shape[:2]
    paths['prior-ppo']=matches(load_models(8,9,na,nf,LIB),torch.from_numpy(games),torch.from_numpy(cues),
        ids,np.repeat(np.arange(n),r*nf))['won']
    best=table.mean((0,1)).argmax();paths['train-best-fixed']=games[:,:,best,0]
    for name,w in paths.items():
        results.setdefault(name,{}).update(games=tally(w),series=tally(series(w)),
            opponents={foe:tally(w[ids==j]) for j,foe in enumerate(foes)},
            rtts={f'{a}/{b}':tally(w[(cues[...,1]==a)&(cues[...,2]==b)]) for a,b in RTTS},
            seats={s:tally(w[cues[...,0]==j]) for j,s in enumerate('ab')})
    contrasts=[('motion',b) for b in ('feedback','latest','first','blind','prior-ppo','train-best-fixed','known-id')]+[('same','wrong')]
    effects={}
    for a,b in contrasts:
        delta=((paths[a]==1).astype(float)-(paths[b]==1)).reshape(n,-1).mean(1)
        effects[a+' minus '+b]=dict(delta=float(delta.mean()),bounds=lower_contrast(delta,.05/len(contrasts)))
    write_json(DEST/f'dev/decoder-{seed}-n{fraction}.json',dict(scores=results,effects=effects,
        clocks=n,training_examples=len(y)*fraction//8,training_best=experts[best]),fixed=True)


if __name__=='__main__':
    if sys.argv[1]=='diagnose':
        diagnostics()
    else:
        run(int(sys.argv[1]),int(sys.argv[2]))
