"""Seal future timing fixtures for the first expanded, wins-only league router."""
import hashlib
import shlex
import sys
import tempfile
import time
from pathlib import Path

import numpy as np
import torch
from scipy.stats import t

from expanded_crossplay import FOLDER
from expanded_reward import DEST as FIT, SEED, load, trajectory
from expert_population import Actor
from learner import ROOT, RTTS, Worker, write_json
from opponent_motion import KEEP
from remote_league import OUT, read
from series_eval import series, tally
from series_targeted import DEST as LIB
from specialist_diagnosis import fixture_inputs

DEST=FOLDER/'future-nine-targets'


def register():
    if (DEST/'assay.json').exists():return
    latest=max(int(f.stem[1:]) for f in (ROOT/'runtime/results').glob('m*.json') if f.stem[1:].isdigit())
    digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
    write_json(DEST/'assay.json',dict(first_future_id=latest+6,calendar_count=16,minimum_gap_ms=60000,reps=2,
        models={str(n):digest(FIT/f'covered-{n:02}/seed-{SEED}/u2048.pt') for n in (7,9)},
        libraries={str(n):digest(FOLDER/f'crossplay/covered-{n:02}.json') for n in (7,9)},
        purpose='Confirm actual outright ring-outs of the reward-only, eleven-expert router across all 27 fixed foes on new independent timing donors. Only score the fixed primary training seed at the fixed final update. Frozen comparison is the similarly trained nine-expert agent, erased memory, matched current-ping router, true identity, best fixed actor and W-tap. High-level controllers share the same broad opener. Distinguish full series and individual ring-outs.',
        selection='First 16 eligible bot-versus-bot sumo donor matches, starting after both selected controllers froze and separated by 60 seconds after the previous donor finished. Keep all 27 foes and both seeded ten-game schedules together per donor for the paired source-level uncertainty. Only frozen policy inference and final outcomes may use these timing recordings.'),fixed=True)


def reserve():
    from calendars_holdout import collect
    from remote_fit import HOST, REMOTE, RSYNC, SSH, invoke
    from remote_import import wait_for
    register()
    manifest=DEST/'calendars/manifest.json'
    while not manifest.exists():
        try:collect(DEST)
        except FileNotFoundError as e:
            if str(e.filename)!=str(ROOT/'runtime/status.json'):raise
            time.sleep(3)
    paths=[str((DEST/'assay.json').relative_to(ROOT)),str(manifest.relative_to(ROOT)),*read(manifest)['selected']]
    with tempfile.TemporaryDirectory() as tmp:
        p=Path(tmp)/'files';p.write_text(''.join(f+'\n' for f in paths))
        invoke([*RSYNC,'--files-from='+str(p),str(ROOT)+'/',HOST+':'+REMOTE+'/'])
    name=str(DEST.relative_to(OUT))
    code='from pathlib import Path;from remote_jobs import start;start("expanded-future-nine","expanded_validation.py",["grade"],Path('+repr(name)+')/"result.json")'
    invoke([*SSH,'cd '+REMOTE+'/research/elite-league/single-sumo && ~/mc-sumo-league/venv/bin/python -c '+shlex.quote(code)])
    wait_for([name+'/result.json'])


@torch.inference_mode()
def grade():
    reg=read(DEST/'assay.json'); clocks=read(DEST/'calendars/manifest.json')['selected']
    if len(set(clocks))!=reg['calendar_count']:raise ValueError('Incomplete future timing fixture set')
    spec=read(LIB/'protocol.json');foes=list(spec['field']);nf=len(foes);models={}
    for n in (7,9):
        f=FIT/f'covered-{n:02}/seed-{SEED}/u2048.pt';lib=FOLDER/f'crossplay/covered-{n:02}.json'
        if (hashlib.sha256(f.read_bytes()).hexdigest(),hashlib.sha256(lib.read_bytes()).hexdigest())!=(reg['models'][str(n)],reg['libraries'][str(n)]):
            raise ValueError('A prospectively frozen policy or library changed')
        names=read(lib)['experts'];net=Actor(len(names),False);net.load_state_dict(torch.load(f,weights_only=True));models[n]=(names,net.eval())
    arms=models[9][0];opener=arms.index('original-mixture')
    refs=[]
    for name in arms:
        d=(read(FOLDER/'counters'/(name.removeprefix('dedicated-')+'.json'))['policy'] if name.startswith('dedicated-') else spec['experts'][name])
        if hashlib.sha256((ROOT/d['reference']).read_bytes()).hexdigest()!=d['sha256']:raise ValueError('Combat expert changed')
        refs.append(d['reference'])
    train,ctx,_,_,_=load(9,'train');pbin=ctx[...,1:].numpy()>=70;b=2*pbin[...,0]+pbin[...,1]
    counts=np.stack([np.stack([(train.numpy()[b==i]==s).sum(0)+1 for s in (0,.5,1)],-1) for i in range(4)])
    prob=(counts/counts.sum(-1,keepdims=True))[...,2]
    fixed=int(np.stack([(train.numpy()[b==i]==1).mean(0) for i in range(4)]).mean((0,1)).argmax())
    totals={};contexts=[];worker=Worker(current_runtime=True)
    try:
        for i,clock in enumerate(clocks):
            for rep in range(reg['reps']):
                tape=list(fixture_inputs('expanded-timing-702715',rep,i));wins=[];opening=None
                for g,(side,own,other,s1,s2) in enumerate(tape):
                    sc=[]
                    for j,ref in enumerate([*refs,'wtap']):
                        rows=[[ref,spec['field'][f]['reference'],clock,'ab'[side],own,other,s1,s2] for f in foes]
                        track=g==0 and j==opener
                        games=worker.run(rows,track)
                        if track:
                            raw=np.stack([z[np.minimum(np.arange(48),len(z)-1)][:,KEEP] for _,_,z in games]).reshape(nf,8,6,12)
                            opening=torch.from_numpy(np.concatenate((raw.mean(2),raw.std(2)),-1).reshape(nf,192))
                        sc.append([w for w,_,_ in games])
                    wins.append(np.stack(sc,-1))
                z=torch.from_numpy(np.stack(wins,1));c=np.broadcast_to(np.array([row[:3] for row in tape])[None],(nf,10,3)).copy()
                p=torch.from_numpy(c[...,1:]).float();contexts.append(c)
                out={'wtap':z[:,:,-1].numpy(),'fixed':z[:,:,fixed].numpy()}
                for n,(names,net) in models.items():
                    which=[arms.index(name) for name in names]
                    for erase in ([False,True] if n==9 else [False]):
                        key='erased' if erase else f'learned-{n}'
                        _,v,_=trajectory(net,z[:,:,which],p,opening,names.index('original-mixture'),erase=erase)
                        out[key]=v.numpy()
                k=(2*(p[...,0]>=70)+(p[...,1]>=70)).numpy();ids=np.arange(nf)[:,None]
                for name,a in [('blind',prob.mean(1)[k].argmax(-1)),('known-id',prob[k,ids].argmax(-1))]:
                    a[:,0]=opener;out[name]=z.numpy()[ids,np.arange(10)[None,:],a]
                for name,v in out.items():totals.setdefault(name,[]).append(v)
    finally:worker.close()
    w={k:np.stack(v) for k,v in totals.items()};c=np.stack(contexts)
    scores={k:dict(games=tally(v),series=tally(series(v.reshape(-1,10))),rematches=tally(v[:,:,1:]),first=tally(v[:,:,0]),
        opponents={f:tally(v[:,i]) for i,f in enumerate(foes)},seats={s:tally(v[c[...,0]==i]) for i,s in enumerate('ab')},
        rtts={f'{a}/{b}':tally(v[(c[...,1]==a)&(c[...,2]==b)]) for a,b in RTTS}) for k,v in w.items()}
    effects={}
    for k,v in w.items():
        d=((w['learned-9']==1).astype(float)-(v==1)).reshape(len(clocks),-1).mean(1)
        mean=float(d.mean());half=float(t.ppf(.975,len(d)-1)*d.std(ddof=1)/np.sqrt(len(d)))
        effects[k]=dict(delta=mean,source_timing_95=[mean-half,mean+half])
    write_json(DEST/'result.json',dict(scores=scores,effects=effects,source_timing_count=len(clocks)),fixed=True)


if __name__=='__main__':
    if sys.argv[1]=='register':register()
    elif sys.argv[1]=='reserve':reserve()
    else:grade()
