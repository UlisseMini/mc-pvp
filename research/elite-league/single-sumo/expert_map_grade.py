"""Run a frozen rematch router on validation terrain; persist only choices and outcomes."""
import hashlib
import io
import time

import numpy as np
import torch

from expert_population import Actor,SEED,STUDY
from expert_replication import DEST,REPS
from learner import CLOCKS,ROOT,RTTS,Worker,atomic,write_json
from remote_league import read
from series_eval import series,tally
from series_hidden import donor_rows
from series_targeted import DEST as LIB
from series_trace import CHANNELS
from specialist_ablation import bootstrap
from specialist_diagnosis import DATA,fixture_inputs,signal
from specialist_router import visible_feedback


@torch.inference_mode()
def part(name,shard):
    design=read(DEST/'map-inference.json');runtime=ROOT/design['relative_runtime']
    manifest=read(runtime/'map-runtime.json')
    if hashlib.sha256((runtime/'map-runtime.json').read_bytes()).hexdigest()!=design['manifest_sha256']:raise ValueError('Map runtime changed')
    for file,stamp in manifest['files'].items():
        if hashlib.sha256((runtime/file).read_bytes()).hexdigest()!=stamp:raise ValueError('Invalid map binary')
    spec=read(LIB/'protocol.json');cfg=read(STUDY/'protocol.json');names=cfg['small'];na=len(names)
    expert=[cfg['pairs'][f] for f in names]+['original-mixture','wtap'];n=len(CLOCKS['dev'])
    model=Actor(na,False);model.load_state_dict(torch.load(STUDY/'small'/f'seed-{SEED}'/'memory/u512.pt',weights_only=True));model.eval()
    tapes=[[v for v in fixture_inputs('exp-replication-8329719',r,i)] for i in range(n) for r in range(REPS)]
    cues=torch.tensor([[v[:3] for v in tape] for tape in tapes]).reshape(n,REPS,10,3)
    same,wrong=donor_rows(cues,na)
    worker=Worker(runtime_directory=runtime,map_name=name)
    # Evaluate a frozen actor. Only network embeddings retained briefly in RAM. No training examples go to disk.
    pool={}
    def cell(schedule):
        if schedule in pool:return pool[schedule]
        obs=[];scores=[];clock=CLOCKS['dev'][schedule//REPS]
        for side,a,b,x,y in tapes[schedule]:
            sc=[];em=[]
            for j,arm in enumerate(expert):
                ref='wtap' if arm=='wtap' else spec['experts'][arm]['reference']
                plan=[[ref,spec['field'][f]['reference'],clock,'ab'[side],a,b,x,y] for f in names]
                if j<na:
                    played=worker.observe_frozen_evaluation(plan,spec['experts'][arm]['sha256'],lambda v:signal(v[:,CHANNELS])[360:])
                    feedback=np.stack([np.concatenate((m,visible_feedback(np.asarray(w),np.asarray(ms),np.asarray([a,b])))) for w,ms,m in played])
                    token=model.event(torch.from_numpy(feedback),torch.full((na,),j),na)
                    em.append(model.encoder(token))
                else:played=worker.run(plan,False)
                sc.append([v[0] for v in played])
            obs.append(torch.stack(em,1));scores.append(np.stack(sc,1))
        pool[schedule]=(torch.stack(obs),np.stack(scores));return pool[schedule]
    target=DATA/'map-evaluation-scores'/name
    try:
        for s in range(shard,len(tapes),2):
            path=target/f'{s:03}.npz'
            if path.exists():continue
            emp,w=cell(s);indices=np.arange(na);source=int(same[s*na])//na
            e_other,_=cell(source);out={'broad':w[:,:,-2].T,'wtap':w[:,:,-1].T,'dedicated':w[:,indices,indices].T}
            for j,f in enumerate(names):out['fixed-'+f]=w[:,:,j].T
            for role,seen,ids in [('memory',emp,indices),('reset',emp,indices),('same',e_other,indices),('wrong',e_other,wrong[s*na:(s+1)*na]%na)]:
                h=torch.zeros(1,na,48);win=np.empty((na,10))
                for g,(_,a,b,_,_) in enumerate(tapes[s]):
                    p=torch.tensor([a,b],dtype=torch.float32).expand(na,2)
                    act=model.readout(h[0],p)[0].argmax(1);win[:,g]=w[g,indices,act.numpy()]
                    h=model.memory(seen[g,ids,act][:,None],h)[1]
                    if role=='reset':h.zero_()
                out[role]=win
            buf=io.BytesIO();np.savez_compressed(buf,**out);atomic(path,buf.getvalue())
    finally:worker.close()
    write_json(DEST/name/f'part-{shard}.json',dict(complete=True),fixed=True)


def grade(name):
    root=DEST/name
    while not all((root/f'part-{j}.json').exists() for j in range(2)):time.sleep(5)
    names=read(STUDY/'protocol.json')['small'];n=len(CLOCKS['dev']);data={};ct=[]
    for s in range(n*REPS):
        with np.load(DATA/'map-evaluation-scores'/name/f'{s:03}.npz') as z:
            for k in z.files:data.setdefault(k,[]).append(z[k])
        ct.append([v[:3] for v in fixture_inputs('exp-replication-8329719',s%REPS,s//REPS)])
    w={k:np.stack(v) for k,v in data.items()};c=np.broadcast_to(np.array(ct)[:,None],(n*REPS,len(names),10,3))
    report={k:dict(games=tally(v),series=tally(series(v.reshape(-1,10))),opener=tally(v[:,:,0]),rematches=tally(v[:,:,1:]),
        opponents={f:tally(v[:,j]) for j,f in enumerate(names)},seats={s:tally(v[c[...,0]==j]) for j,s in enumerate('ab')},
        rtts={f'{a}/{b}':tally(v[(c[...,1]==a)&(c[...,2]==b)]) for a,b in RTTS}) for k,v in w.items()}
    effects={}
    for a,b in [('memory','reset'),('memory','broad'),('memory','dedicated'),('same','wrong')]:
        for label,col in [('all',slice(None)),('rematches',slice(1,None))]:
            d=((w[a][:,:,col]==1).astype(float)-(w[b][:,:,col]==1)).reshape(n,REPS,-1).mean(2)
            effects[f'{a} minus {b} {label}']=dict(delta=float(d.mean()),conditional_95=bootstrap(d))
    write_json(root/'result.json',dict(scores=report,effects=effects,unique_physical_cells=n*REPS*10*len(names)*(len(names)+2)),fixed=True)
