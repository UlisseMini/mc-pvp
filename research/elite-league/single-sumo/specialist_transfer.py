"""Evaluate a frozen opponent observer on rivals absent from decoder training, and on new clocks."""
import hashlib
import io
import sys
import time

import numpy as np
import torch
from torch.nn import functional as F

from assay import lower_contrast
from learner import CLOCKS, FIELD, ROOT, RTTS, Worker, atomic, write_json
from remote_league import OUT, read
from series_eval import load_models, matches, series, tally
from series_targeted import DEST as LIB, bank
from series_trace import CHANNELS
from specialist_diagnosis import DATA, DEST, TRAIN_REPS, fixture_inputs, signal
from specialist_router import Decoder, payoffs, rollout, visible_feedback

ACTOR=DEST/'models/motion-5213-n8'
CHALLENGE=DEST/'new-opponents'
FRESH=DEST/'prospective'


def register():
    spec=read(LIB/'protocol.json');field=spec['field']
    archive=read(ROOT/'research/elite-league/single-sumo/evidence/g002/generation.json')['field']
    candidates={n:archive[n] for n in FIELD if n not in field}
    candidates.update({n:spec['experts'][n] for n in ('new-response','original-mixture','weakest-mixture')})
    seen={v['sha256'] for v in field.values()}
    if any(v['sha256'] in seen for v in candidates.values()):raise ValueError('An opponent was already seen')
    write_json(CHALLENGE/'protocol.json',dict(opponents=candidates,expert_order=list(spec['experts']),
        design='Entire omitted original archive plus three recent strong MLPs that the decoder saw only as its own options. No training or selection on this panel. Use the same eight exposed development timing recordings and four full ten-game schedules per opponent. Router has only its 27 learned opponent categories and never receives the true opponent. Grade against the strongest fixed training expert, blind ping-only, previous nine-game PPO, the first game alone, feedback-only, and W-tap. All full runs use the same independently seeded fixtures. This tests new opponent policies for the observer; combat experts can have faced some during their own training.'),fixed=True)


def fixtures(kind):
    spec=read(LIB/'protocol.json')
    if kind=='new':
        return CHALLENGE,read(CHALLENGE/'protocol.json')['opponents'],CLOCKS['dev']
    from series_hidden import independent_clocks
    clocks=read(FRESH/'calendars/manifest.json')['selected']
    independent_clocks(clocks)
    return FRESH,spec['field'],clocks


def collect(kind,shard,shards):
    dest,field,clocks=fixtures(kind);spec=read(LIB/'protocol.json')
    expert=list(spec['experts'].values());foes=list(field.values())
    engine=Worker(current_runtime=True)
    try:
        for i,clock in enumerate(clocks):
            for r in range(4):
                if (4*i+r)%shards!=shard:continue
                path=DATA/kind/f'{i:02}-{r:02}.npz'
                if path.exists():continue
                all_scores=[];all_traces=[];wt=[];cues=[]
                for seat,own,other,x,y in fixture_inputs('counter-observer-'+kind,r,i):
                    tail=[[f['reference'],clock,'ab'[seat],own,other,x,y] for f in foes]
                    w=engine.run([['wtap',*t] for t in tail],False);wt.append([v[0] for v in w]);cues.append([seat,own,other])
                    scores=[];trace=[]
                    for model in expert:
                        played=engine.run([[model['reference'],*t] for t in tail],True)
                        scores.append([[w,ms] for w,ms,_ in played])
                        trace.append([signal(v[:,CHANNELS]) for _,_,v in played])
                    all_scores.append(np.stack(scores,1));all_traces.append(np.stack(trace,1))
                buf=io.BytesIO();np.savez_compressed(buf,scores=np.asarray(all_scores,np.float32),
                    traces=np.stack(all_traces),wt=np.asarray(wt,np.float32),cues=np.asarray(cues,np.int16))
                atomic(path,buf.getvalue())
    finally:engine.close()
    write_json(dest/f'collection/{shard}-of-{shards}.json',dict(checked=True),fixed=True)


def grade(kind):
    dest,field,clocks=fixtures(kind);spec=read(LIB/'protocol.json');experts=list(spec['experts'])
    while not ((ACTOR/'fit.json').is_file() and all((dest/f'collection/{s}-of-2.json').exists() for s in range(2))):time.sleep(5)
    trained=list(spec['field']);nf,na,n=len(trained),len(experts),len(field);names=list(field)
    if kind=='fresh' and read(FRESH/'assay.json')['actor_sha256']!=read(ACTOR/'fit.json')['sha256']:
        raise ValueError('Registered prospective actor differs')
    models={}
    for mode in ('motion','feedback'):
        m=Decoder(na,nf,mode=='motion');folder=ACTOR.with_name(mode+'-5213-n8')
        if hashlib.sha256((folder/'actor.pt').read_bytes()).hexdigest()!=read(folder/'fit.json')['sha256']:raise ValueError('Changed decoder')
        m.load_state_dict(torch.load(folder/'actor.pt',weights_only=True));models[mode]=m.eval()
    train,c,_=bank('train',TRAIN_REPS);table=payoffs(train.numpy(),c.numpy(),True)
    opener=experts.index('original-mixture');fixed=int(table.mean((0,1)).argmax())
    wins={};ct=[];prob=[]
    old=load_models(8,9,na,nf,LIB)
    for i in range(len(clocks)):
        for r in range(4):
            with np.load(DATA/kind/f'{i:02}-{r:02}.npz') as z:
                scores=z['scores'].transpose(1,0,2,3).copy();view=z['traces'].transpose(1,0,2,3)
                wt=z['wt'].T;cues=np.broadcast_to(z['cues'],(n,10,3)).copy()
            public=visible_feedback(scores[...,0],scores[...,1],np.broadcast_to(cues[:,:,None,1:],(n,10,na,2)))
            actions=np.broadcast_to(np.eye(na,dtype=np.float32),(n,10,na,na))
            x=torch.from_numpy(np.concatenate((view,public,actions),-1).reshape(n*10*na,-1))
            # Placeholder identity is used only for diagnostics; it is never fed to a legal chooser.
            ids=np.arange(n) if kind=='fresh' else np.zeros(n,dtype=int)
            outputs={'wtap':wt,'train-best-fixed':scores[:,:,fixed,0]}
            outputs['previous-ppo']=matches(old,torch.from_numpy(scores),torch.from_numpy(cues),ids,np.full(n,i))['won']
            for role,m in models.items():
                with torch.inference_mode():ll=torch.cat([F.log_softmax(m(b),1) for b in x.split(512)]).numpy().reshape(n,10,na,nf)
                for label,h in ([(role,'all')] if role=='feedback' else [('motion','all'),('blind','none'),('first','first')]):
                    outputs[label],_,p,_=rollout(ll,table,scores,cues,ids,opener,h)
                    if label=='motion' and kind=='fresh':prob.append(p)
            if kind=='fresh':outputs['known-id']=rollout(None,table,scores,cues,ids,opener,'oracle')[0]
            for k,v in outputs.items():wins.setdefault(k,[]).append(v)
            ct.append(cues)
    outcomes={k:np.stack(v) for k,v in wins.items()};cc=np.stack(ct)
    summaries={k:dict(games=tally(v),series=tally(series(v.reshape(-1,10))),opponents={foe:tally(v[:,j]) for j,foe in enumerate(names)},
               seats={s:tally(v[cc[...,0]==j]) for j,s in enumerate('ab')},
               rtts={f'{a}/{b}':tally(v[(cc[...,1]==a)&(cc[...,2]==b)]) for a,b in RTTS}) for k,v in outcomes.items()}
    effects={}
    for b in ('train-best-fixed','blind','previous-ppo','feedback','first'):
        d=((outcomes['motion']==1).astype(float)-(outcomes[b]==1)).reshape(len(clocks),-1).mean(1)
        effects[b]=dict(gain=float(d.mean()),bounds=lower_contrast(d,.05/10))
    write_json(dest/'results.json',dict(scores=summaries,effects=effects,recordings=len(clocks),
        actor=read(ACTOR/'fit.json')['sha256'],trained_opponents=nf,testing_opponents=n,
        posterior_true_class=np.mean(prob,0).tolist() if prob else None),fixed=True)


if __name__=='__main__':
    if sys.argv[1]=='register':register()
    elif sys.argv[1]=='reserve':
        from remote_import import wait_for,send_future_clocks
        root=str(ACTOR.relative_to(OUT))+'/'
        wait_for([root+'actor.pt',root+'fit.json'])
        send_future_clocks(FRESH,dict(actor_sha256=read(ACTOR/'fit.json')['sha256'],experts=read(LIB/'protocol.json')['experts'],
            comparison='Frozen recurrent opponent observer versus the previous PPO, best fixed expert, blind ping lookup, first opening only, feedback only and plain W-tap across the same twenty-seven opponents. Ring-outs primary.'))
        from remote_fit import HOST, REMOTE
        import subprocess
        subprocess.run(['ssh',HOST,'cd '+REMOTE+'/research/elite-league/single-sumo && ~/mc-sumo-league/venv/bin/python specialist_transfer.py launch fresh'],check=True)
        wait_for([str(FRESH.relative_to(OUT))+'/results.json'])
    elif sys.argv[1]=='launch':
        from remote_jobs import start
        kind=sys.argv[2];dest=FRESH if kind=='fresh' else CHALLENGE
        for s in range(2):start(f'spec-transfer-{kind}-{s}','specialist_transfer.py',[kind,str(s),'2'],dest.relative_to(OUT)/f'collection/{s}-of-2.json')
        start('spec-transfer-'+kind+'-grade','specialist_transfer.py',['grade',kind],dest.relative_to(OUT)/'results.json')
    elif sys.argv[1]=='grade':grade(sys.argv[2])
    else:collect(sys.argv[1],int(sys.argv[2]),int(sys.argv[3]))
