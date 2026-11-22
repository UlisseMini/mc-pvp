"""Compare short context, recurrent history and privileged opponent identity."""

import math

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

from prepare import OUT, RECENT, TARGETS, load, save

SIZES = (4,3,2,2)
torch.set_num_threads(1)
torch.set_num_interop_threads(1)


class Panel:
    def __init__(self, name, setup):
        with np.load(OUT/f'{name}.npz') as data:
            self.x, self.y, self.time, self.cuts, self.foe, self.donor, self.actor = [
                data[k] for k in ('x','y','time','cuts','foe','donor','actor')]
        self.length = np.diff(self.cuts)
        self.unseen = np.array([setup['families'][setup['foes'][i]] in setup['unseen'] for i in self.foe])
        # Own-side eligibility is used only to summarize relevant exchanges.
        self.hard = (self.x[:,16]<=.35)&(self.x[:,1]<=4.5)
        self.recent = np.array(RECENT)

    def batches(self, ids, count, rng=None):
        ids = ids[np.argsort(self.length[ids],kind='stable')]
        if rng is not None:
            # Shuffle among similarly sized bouts to avoid spending CPU on padding.
            ids = np.concatenate([rng.permutation(g) for g in np.array_split(ids,max(1,len(ids)//(count*8)))])
        batches = [ids[j:j+count] for j in range(0,len(ids),count)]
        if rng is not None:
            rng.shuffle(batches)
        return batches

    def batch(self, ids, mean, scale):
        t = np.arange(self.length[ids].max())
        real = t[None,:]<self.length[ids,None]
        rows = self.cuts[ids,None]+np.minimum(t[None,:],self.length[ids,None]-1)
        x = np.clip((self.x[rows]-mean)/scale,-12,12).astype(np.float32)
        y = self.y[rows].astype(np.int64)
        y[~real] = -1
        short = x[:,np.maximum(t[:,None]-self.recent[None,:],0)].reshape(len(ids),len(t),-1)
        return (torch.from_numpy(x),torch.from_numpy(short),torch.from_numpy(y),
                torch.from_numpy(self.foe[ids]), rows, real)


class Predictor(nn.Module):
    def __init__(self, kind, width, foes):
        super().__init__()
        self.kind = kind
        self.foes = foes
        self.short = nn.Sequential(nn.Linear(width*len(RECENT),64),nn.SiLU())
        self.memory = nn.GRU(width,32,batch_first=True) if kind=='gru' else None
        extra = 32 if kind=='gru' else foes if kind=='id' else 0
        self.head = nn.Sequential(nn.Linear(64+extra,48),nn.SiLU(),nn.Linear(48,sum(SIZES)))

    def forward(self,x,short,foe,reset=False):
        local = self.short(short)
        if self.memory is not None:
            if reset:
                h = x.new_zeros(*x.shape[:2],32)
            else:
                past,_ = self.memory(x)
                # The feedforward path already sees the recent eight ticks.
                h = F.pad(past,(0,0,8,0))[:,:x.shape[1]]
                if self.training:
                    h = h*(torch.rand(len(x),1,1)>.12)
            local = torch.cat((local,h),-1)
        elif self.kind=='id':
            identity = F.one_hot(foe,self.foes).float()
            local = torch.cat((local,identity[:,None].expand(-1,x.shape[1],-1)),-1)
        return self.head(local)


def losses(logits, labels):
    return torch.stack([F.cross_entropy(part.flatten(0,1),labels[:,:,i].flatten(),
                                        ignore_index=-1,reduction='none').reshape(labels.shape[:2])
                        for i,part in enumerate(logits.split(SIZES,dim=-1))],dim=-1)


@torch.no_grad()
def measure(model,panel,ids,mean,scale,reset=False,store=False):
    model.eval()
    count = total = 0
    loss = np.full((len(panel.y),len(SIZES)),np.nan,dtype=np.float32) if store else None
    correct = np.full((len(panel.y),len(SIZES)),np.nan,dtype=np.float32) if store else None
    for batch in panel.batches(ids,48):
        x,short,y,foe,rows,_ = panel.batch(batch,mean,scale)
        valid = y[:,:,0]>=0
        z = model(x,short,foe,reset)
        each = losses(z,y)
        seen = valid.sum(1)
        total += float((each.mean(-1).sum(1)/seen.clamp_min(1))[seen>0].sum())
        count += int((seen>0).sum())
        if store:
            valid = valid.numpy()
            loss[rows[valid]] = each.numpy()[valid]
            pred = torch.stack([p.argmax(-1) for p in z.split(SIZES,dim=-1)],-1)
            correct[rows[valid]] = (pred==y).numpy()[valid]
    return (loss,correct) if store else total/max(1,count)


def fit(name, kind, train, valid, train_ids, val_ids, mean, scale, setup, seed, epochs=None):
    torch.manual_seed(seed)
    rng = np.random.default_rng(seed)
    model = Predictor(kind,train.x.shape[1],len(setup['foes']))
    path = OUT/'weights'/f'{seed}-{name}.pt'
    if path.exists():
        data = torch.load(path,map_location='cpu',weights_only=True)
        model.load_state_dict(data['weights'])
        return model,data['epoch']
    opt = torch.optim.AdamW(model.parameters(),lr=.002,weight_decay=.005)
    best = math.inf
    for epoch in range(1,(epochs or setup['epochs'])+1):
        model.train()
        for ids in train.batches(train_ids,32,rng):
            x,short,y,foe,rows,_ = train.batch(ids,mean,scale)
            weight = (y[:,:,0]>=0)*(1+torch.from_numpy(train.hard[rows]).float())
            per_head = losses(model(x,short,foe),y).mean(-1)
            loss = ((per_head*weight).sum(1)/weight.sum(1).clamp_min(1)).mean()
            opt.zero_grad()
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(),1.0)
            opt.step()
        score = measure(model,valid,val_ids,mean,scale)
        if score<best:
            best = score
            best_epoch = epoch
            best_state = {k:v.detach().clone() for k,v in model.state_dict().items()}
    model.load_state_dict(best_state)
    temporary = path.with_suffix('.tmp')
    torch.save(dict(weights=best_state,epoch=best_epoch),temporary)
    temporary.replace(path)
    print(f'{seed} {name} epoch {best_epoch}, validation {best:.4f}',flush=True)
    return model,best_epoch


def summarize(panel, loss, correct, mask):
    valid = mask&np.isfinite(loss[:,0])
    n = np.add.reduceat(valid.astype(np.int64),panel.cuts[:-1])
    xs = np.add.reduceat(np.where(valid[:,None],loss,0),panel.cuts[:-1],axis=0)/np.maximum(1,n[:,None])
    acc = (np.add.reduceat(np.where(valid[:,None],correct,0),panel.cuts[:-1],axis=0)
           /np.maximum(1,n[:,None]))
    return xs,acc,n


def summary(panel,predictions,setup):
    by_head = np.repeat(panel.foe,panel.length)
    foe_group = np.asarray([setup['families'][f] for f in setup['foes']])
    unseen_head = np.repeat(panel.unseen,panel.length)
    by_slice = {
        'unseen_all': unseen_head,
        'unseen_after_hurt': unseen_head&panel.hard,
        'unseen_first_half_second':unseen_head&(panel.time<=500),
        'unseen_after_two_seconds':unseen_head&(panel.time>=2000),
        'all':np.ones(len(panel.y),dtype=bool),
        'all_after_hurt':panel.hard,
        'wtap_only':by_head==setup['foes'].index('wtap'),
    }
    out = {}
    for slice_name, mask in by_slice.items():
        result = {}
        for model,(loss,correct) in predictions.items():
            if model.startswith('unseen_') and not slice_name.startswith('unseen_'):
                continue
            xs,acc,n = summarize(panel,loss,correct,mask)
            covers = n>0
            if not covers.any():
                continue
            groups = {}
            foes = {}
            for i,f in enumerate(setup['foes']):
                eligible = covers&(panel.foe==i)
                if eligible.any():
                    foes[f] = dict(loss=xs[eligible].mean(0).tolist(),
                                   accuracy=acc[eligible].mean(0).tolist(),bouts=int(eligible.sum()))
            for f in foes:
                groups.setdefault(setup['families'][f],[]).append(foes[f]['loss'])
            result[model] = dict(mean=float(np.mean([np.mean(g) for g in groups.values()])),
                                 by_action=np.mean([np.mean(g,axis=0) for g in groups.values()],axis=0).tolist(),
                                 by_foe=foes,heads=int(n.sum()),bouts=int(covers.sum()))
        out[slice_name] = result
    contrasts = {}
    rng = np.random.default_rng(32097)
    for name, mask in by_slice.items():
        if not name.startswith('unseen_'):
            continue
        comparisons = [('unseen_gru','unseen_mlp'),('unseen_gru','unseen_reset')]
        if 'unseen_selective' in predictions:
            comparisons.append(('unseen_selective','unseen_mlp'))
            comparisons.append(('unseen_selective','unseen_selective_reset'))
        for model, baseline in comparisons:
            xs,_,n = summarize(panel,predictions[model][0],predictions[model][1],mask)
            ys,_,_ = summarize(panel,predictions[baseline][0],predictions[baseline][1],mask)
            diff = (ys-xs).mean(1)
            eligible = n>0
            # Bootstrap clocks jointly across all opponents. The opponent
            # families are fixed and show their individual spread separately.
            foes = np.unique(panel.foe[eligible])
            donors = np.unique(panel.donor[eligible])
            totals = np.zeros((len(foes),len(donors)))
            counts = np.zeros_like(totals)
            for row,i in enumerate(foes):
                for col,d in enumerate(donors):
                    ids = eligible&(panel.foe==i)&(panel.donor==d)
                    totals[row,col] = diff[ids].sum()
                    counts[row,col] = ids.sum()
            family = foe_group[foes]
            drawn = rng.integers(len(donors),size=(4000,len(donors)))
            boot_counts = counts[:,drawn].sum(axis=2)
            boot_totals = totals[:,drawn].sum(axis=2)
            means = np.divide(boot_totals,boot_counts,out=np.full_like(boot_totals,np.nan),where=boot_counts>0)
            # Only keep draws with representation from each tested opponent.
            means = means[:,np.isfinite(means).all(axis=0)]
            spread = np.mean([means[family==f].mean(axis=0) for f in np.unique(family)],axis=0)
            contrasts[f'{name}:{baseline}-minus-{model}'] = dict(
                donor_count=len(donors), interval=np.quantile(spread,[.025,.975]).tolist(),
                loss_difference=out[name][baseline]['mean']-out[name][model]['mean'])
    return dict(slices=out,contrasts=contrasts)


def run(seed, setup, panels):
    support, validation, test = (panels[s] for s in ('support','validation','test'))
    full = np.arange(len(support.foe))
    unseen = np.flatnonzero(~support.unseen)
    valid = np.arange(len(validation.foe))
    test_ids = np.arange(len(test.foe))
    scale = {}
    seen_rows = ~np.repeat(support.unseen,support.length)
    for name, rows in [('all',support.x),('seen',support.x[seen_rows])]:
        scale[name] = (rows.mean(0),np.maximum(.02,rows.std(0)))
    predictions = {}
    epochs = {}
    for name,kind,train_ids,val_ids,stats in (
            ('unseen_mlp','mlp',unseen,valid[~validation.unseen],scale['seen']),
            ('unseen_gru','gru',unseen,valid[~validation.unseen],scale['seen']),
            ('matched_mlp','mlp',full,valid,scale['all']),
            ('matched_gru','gru',full,valid,scale['all']),
            ('matched_id','id',full,valid,scale['all'])):
        model,epochs[name] = fit(name,kind,support,validation,train_ids,val_ids,*stats,setup,seed)
        ids = test_ids[test.unseen] if name.startswith('unseen_') else test_ids
        predictions[name] = measure(model,test,ids,*stats,store=True)
        if name=='unseen_gru':
            predictions['unseen_reset'] = measure(model,test,ids,*stats,reset=True,store=True)
    specialist = (np.full_like(predictions['matched_id'][0],np.nan),
                  np.full_like(predictions['matched_id'][1],np.nan))
    for i,name in enumerate(setup['foes']):
        fit_ids = full[support.foe==i]
        val = valid[validation.foe==i]
        model,epochs[name] = fit(f'specialist-{i}','mlp',support,validation,fit_ids,val,*scale['all'],
                                 setup,seed,epochs=20)
        result = measure(model,test,test_ids[test.foe==i],*scale['all'],store=True)
        for dest, res in zip(specialist,result):
            known = np.isfinite(res[:,0])
            dest[known] = res[known]
    predictions['specialist'] = specialist
    save(OUT/f'normalization-{seed}.json',{k:[x.tolist() for x in v] for k,v in scale.items()})
    save(OUT/f'results-{seed}.json',dict(seed=seed,epochs=epochs,**summary(test,predictions,setup)))
    np.savez_compressed(OUT/f'test-losses-{seed}.npz',
                        **{f'{k}_loss':v[0] for k,v in predictions.items()},
                        **{f'{k}_correct':v[1] for k,v in predictions.items()})


def main():
    (OUT/'weights').mkdir(exist_ok=True)
    setup = load(OUT/'protocol.json')
    panels = {s:Panel(s,setup) for s in ('support','validation','test')}
    for seed in setup['seeds']:
        if not (OUT/f'results-{seed}.json').exists():
            run(seed, setup, panels)


if __name__ == '__main__':
    main()
