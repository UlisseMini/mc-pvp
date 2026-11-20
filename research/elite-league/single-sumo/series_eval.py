"""Play closed-loop ten-game series on distinct evaluation fixtures, then intervene on previous games."""
import sys
import time

import numpy as np
import torch

from assay import lower_contrast
from learner import write_json
from remote_league import read
from series_bank import BUDGETS,DEV_REPS,FOLDER,ROUNDS
from series_learn import HORIZONS,SEEDS,SeriesPolicy,bank,features,feedback


def load_models(budget,horizon,arms,foes,root=None):
    models=[]
    root=FOLDER if root is None else root
    for seed in SEEDS if budget==BUDGETS[-1] else SEEDS[:1]:
        m=SeriesPolicy(arms,foes,horizon)
        folder=root/f'learn-{budget:02}'/f'h{horizon}-s{seed}'
        m.load_state_dict(torch.load(folder/'actor.pt',weights_only=True));models.append(m.eval())
    return models


def donors(round_no,points,opponents,tapes,cues,catalog,keep_last=1):
    # Replace the older prefix, preserving the last real game and exactly matching earlier points.
    outside=catalog['points'][:,round_no-keep_last];indices=np.arange(len(opponents))
    same=indices.copy();wrong=indices.copy();covered=np.zeros(len(indices),bool)
    for i in indices:
        mask=(outside==points[i])&(tapes!=tapes[i])
        # Both donor arms have the same current ping pair and come from a different timing recording.
        mask&=(cues==cues[i]).all(1)
        a=indices[mask&(opponents==opponents[i])];b=indices[mask&(opponents!=opponents[i])]
        if not len(a) or not len(b):continue
        rng=np.random.default_rng(4927+31*i+1901*round_no)
        same[i]=rng.choice(a);wrong[i]=rng.choice(b);covered[i]=True
    return same,wrong,covered


@torch.no_grad()
def matches(models,worlds,ctx,foes,tapes,mode='intact',catalog=None):
    count,_,arms,_=worlds.shape;rows=torch.arange(count)
    history=worlds.new_empty(count,0,arms+6);points=torch.zeros(count)
    choices=[];outcomes=[];seen=[];points_before=[];matched=[]
    for g in range(ROUNDS):
        points_before.append(points.clone());ping=ctx[:,g,1:];seen.append(features(points,g,ping))
        h=history
        keep_last=0 if mode.startswith('all-') else 1
        if mode in ('same','wrong','all-same','all-wrong') and g>keep_last:
            same,wrong,cover=donors(g,points_before[-1-keep_last].numpy(),foes,tapes,ping.numpy(),catalog,keep_last)
            ix=same if mode.endswith('same') else wrong
            h=history.clone()
            h[cover,:g-keep_last]=catalog['history'][ix[cover],:g-keep_last]
            matched.append(int(cover.sum()))
        if mode=='reset':h=torch.zeros_like(history)
        scores=torch.stack([m(h,torch.stack(seen,1))[0][:,-1] for m in models]).mean(0)
        a=scores.argmax(1);w,ms=worlds[rows,g,a].unbind(-1)
        choices.append(a);outcomes.append(w);points+=w
        history=torch.cat((history,feedback(a,w,ms,ping,arms)[:,None]),1)
    return dict(won=torch.stack(outcomes,1).numpy(),choices=torch.stack(choices,1).numpy(),history=history,
                points=torch.stack(points_before,1).numpy(),covered=matched)


def oracle(training,cues):
    win=training[...,0].numpy();p=cues[...,1:].numpy();lookup=[];global_choices=[]
    for a in (False,True):
        for b in (False,True):
            mask=((p[...,0]>=70)==a)&((p[...,1]>=70)==b)
            if not mask.any():raise ValueError('Missing training ping bin')
            avg=win[mask].mean(0);lookup.append(avg.argmax(1));global_choices.append(avg.mean(0).argmax())
    return np.stack(lookup),np.array(global_choices)


def tally(w):return [int((w==s).sum()) for s in (1,.5,0)]
def series(w):return .5+.5*np.sign(w.sum(1)-5)


def assess(budget,split='dev',root=None,opponents=None,arms=None,bank_loader=None):
    root=FOLDER if root is None else root
    spec=read(root/'protocol.json');names=list(spec['field']);experts=list(spec['experts'])
    loader=bank if bank_loader is None else bank_loader
    stats,c,wt=loader(split,DEV_REPS);train,train_context,_=loader('train',budget)
    if opponents is not None:
        stats=stats[:,:,:,opponents];wt=wt[:,:,:,opponents];train=train[:,:,:,opponents]
    if arms is not None:
        stats=stats[:,:,:,:,arms];train=train[:,:,:,:,arms]
    nclock,nrep,_,nfoe,narm,_=stats.shape
    worlds=stats.permute(0,1,3,2,4,5).reshape(-1,ROUNDS,narm,2)
    ctx=c[:,:,None].expand(nclock,nrep,nfoe,ROUNDS,3).reshape(-1,ROUNDS,3)
    rivals=np.tile(np.arange(nfoe),nclock*nrep);tapes=np.repeat(np.arange(nclock),nrep*nfoe)
    lookup,default=oracle(train,train_context)
    p=ctx[...,1:].numpy()>=70;bins=p[...,0]*2+p[...,1];s=np.arange(len(rivals))[:,None];g=np.arange(ROUNDS)[None,:]
    won={'wtap':wt.permute(0,1,3,2).reshape(-1,ROUNDS).numpy(),
         **{'fixed-'+arm:worlds[:,:,j,0].numpy() for j,arm in enumerate(experts)},
         'train-id-lookup':worlds[s,g,lookup[bins,rivals[:,None]],0].numpy(),
         'train-ping-lookup':worlds[s,g,default[bins],0].numpy()}
    choice_counts={};coverage={}
    for h in HORIZONS:
        models=load_models(budget,h,narm,nfoe,root);role='blind' if h<0 else 'memory-'+str(h)
        full=matches(models,worlds,ctx,rivals,tapes)
        won[role]=full['won']
        choice_counts[role]={name:np.stack([(full['choices'][rivals==i]==j).sum(0) for j in range(narm)]).tolist()
                             for i,name in enumerate(names)}
        if len(models)>1:
            for seed,m in zip(SEEDS,models):won[f'{role}-seed-{seed}']=matches([m],worlds,ctx,rivals,tapes)['won']
        if h==9:
            for treatment in ('reset','same','wrong'):
                alt=matches(models,worlds,ctx,rivals,tapes,treatment,full)
                won[treatment]=alt['won'];coverage[treatment]=alt['covered']
    def report(w):
        return dict(series=tally(series(w)),ringouts=tally(w),by_game=[tally(v) for v in w.T],
                    opponents={n:dict(series=tally(series(w[rivals==i])),ringouts=tally(w[rivals==i])) for i,n in enumerate(names)},
                    seat={k:tally(w[ctx[...,0].numpy()==j]) for j,k in enumerate('ab')},
                    rtt={f'{a}/{b}':tally(w[(ctx[...,1].numpy()==a)&(ctx[...,2].numpy()==b)]) for a in (31,109) for b in (31,109)})
    controls=['blind','memory-0','train-ping-lookup',*['fixed-'+n for n in experts]]
    contrasts=[('memory-9',n) for n in controls]+[('memory-9','wrong'),('same','wrong')]
    effects={}
    for a,b in contrasts:
        games=(won[a]==1).astype(float)-(won[b]==1)
        titles=(series(won[a])==1).astype(float)-(series(won[b])==1)
        effects[a+' minus '+b]={}
        for label,v in [('bouts',games),('series',titles)]:
            d=v.reshape(nclock,-1).mean(1)
            effects[a+' minus '+b][label]=dict(diff=float(d.mean()),bounds=lower_contrast(d,.05/(len(contrasts)*(2 if split=='fresh' else 1))),by_timing_recording=d.tolist())
    write_json(root/split/f'evaluation-{budget:02}.json',dict(fights_per_arm=int(won['wtap'].size),series_per_arm=len(rivals),
               scores={k:report(v) for k,v in won.items()},paired_effects=effects,expert_choices=choice_counts,history_coverage=coverage,
               passed=all(x['series']['bounds'][0]>0 for x in effects.values())),fixed=True)


def run(split):
    for budget in BUDGETS if split=='dev' else BUDGETS[-1:]:
        files=[FOLDER/split/f'bank-{DEV_REPS:02}.json',*[FOLDER/f'learn-{budget:02}'/f'h{h}-s{s}'/'trained.json' for h in HORIZONS for s in (SEEDS if budget==BUDGETS[-1] else SEEDS[:1])]]
        while not all(p.is_file() for p in files):time.sleep(15)
        assess(budget,split)


if __name__=='__main__':run(sys.argv[1] if len(sys.argv)>1 else 'dev')
