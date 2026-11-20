"""Grade the 27 literal dedicated counters and each enlarged training-only router on one common panel."""
import io
import sys
import time

import numpy as np

from learner import CLOCKS, ROOT, RTTS, Worker, atomic, write_json
from remote_league import OUT, read
from series_eval import tally, series
from series_targeted import DEST as LIB, bank
from specialist_diagnosis import DATA, DEST, fixture_inputs
from specialist_ablation import bootstrap

FOLDER=DEST/'expanded-library'


def column(name):
    spec=read(LIB/'protocol.json');target=read(FOLDER/'counters'/(name+'.json'));ref=target['policy']['reference']
    import hashlib
    if hashlib.sha256((ROOT/ref).read_bytes()).hexdigest()!=target['policy']['sha256']:raise ValueError('Counter changed')
    worker=Worker(current_runtime=True)
    try:
        for split,reps in [('train',2),('dev',4)]:
            for i,clock in enumerate(CLOCKS[split]):
                path=DATA/'full-columns'/name/split/f'{i:02}.npz'
                if path.exists():continue
                rows=[]
                for r in range(reps):
                    bouts=[]
                    for side,a,b,x,y in fixture_inputs(split,r,i):
                        plan=[[ref,other['reference'],clock,'ab'[side],a,b,x,y] for other in spec['field'].values()]
                        bouts.append([v[0] for v in worker.run(plan,False)])
                    rows.append(bouts)
                buf=io.BytesIO();np.savez_compressed(buf,scores=np.asarray(rows,np.float32));atomic(path,buf.getvalue())
    finally:worker.close()
    write_json(FOLDER/'crossplay/columns'/(name+'.json'),dict(target=target['target'],expert=target['policy'],
        training_fights=len(CLOCKS['train'])*2*10*len(spec['field']),development_fights=len(CLOCKS['dev'])*4*10*len(spec['field'])),fixed=True)


def grade():
    spec=read(LIB/'protocol.json');protocol=read(FOLDER/'protocol.json');foes=list(spec['field']);old=list(spec['experts'])
    added=[f for f in protocol['targets'] if f not in protocol['existing'] and (FOLDER/'crossplay/columns'/(f+'.json')).exists()]
    experts=old+['dedicated-'+f for f in added];arrays={}
    for split,reps in [('train',2),('dev',4)]:
        w,c,wt=bank(split,reps);columns=[w[...,0].numpy()]
        for name in added:
            vals=[]
            for i in range(len(CLOCKS[split])):
                with np.load(DATA/'full-columns'/name/split/f'{i:02}.npz') as z:vals.append(z['scores'])
            columns.append(np.stack(vals)[...,None])
        p=c.numpy()[...,1:]>=70;arrays[split]=(np.concatenate(columns,4),2*p[...,0]+p[...,1],c.numpy(),wt.numpy())
    tr,bins,_,_=arrays['train'];v,p,c,wt=arrays['dev'];tab=np.stack([(tr[bins==i]==1).mean(0) for i in range(4)])
    restrict=len(old);scores={'wtap':wt,'fixed-old':v[...,int(tab[:,:,:restrict].mean((0,1)).argmax())],
        'fixed-all':v[...,int(tab.mean((0,1)).argmax())]}
    for label,width in [('old-id',restrict),('expanded-id',len(experts))]:
        choice=tab[:,:,:width].argmax(2)[p];scores[label]=np.take_along_axis(v,choice[...,None],4)[...,0]
    diag={}
    for i,f in enumerate(foes):
        n=protocol['existing'].get(f,'dedicated-'+f)
        if n in experts:diag[f]=v[:,:,:,i,experts.index(n)]
    if len(diag)==len(foes):scores['literal-dedicated']=np.stack([diag[f] for f in foes],3)
    summary={k:dict(wdl=tally(z),series=tally(series(z.transpose(0,1,3,2).reshape(-1,10))),
        opponents={f:tally(z[:,:,:,i]) for i,f in enumerate(foes)},
        seats={s:tally(z[c[...,0]==j]) for j,s in enumerate('ab')},
        rtts={f'{a}/{b}':tally(z[(c[...,1]==a)&(c[...,2]==b)]) for a,b in RTTS}) for k,z in scores.items()}
    delta=((scores['expanded-id']==1).astype(float)-(scores['old-id']==1)).mean((2,3))
    receipt=dict(experts=experts,dedicated_covered=len(diag),dedicated={f:tally(v) for f,v in diag.items()},scores=summary,
        id_library_gain=float(delta.mean()),conditional_fixed_timing_95=bootstrap(delta),training_schedules_per_clock=2)
    write_json(FOLDER/'crossplay'/f'covered-{len(diag):02}.json',receipt,fixed=True)
    route_expansion(tr,bins,v,p,c,wt,experts,foes,len(diag))


def route_expansion(train,train_bins,world,bins,ctx,wt,experts,foes,covered):
    # Keep the old opening observer fixed. The response library can grow without inventing
    # sensor embeddings for actors it never observed. Later updates need only a public result.
    import torch
    from specialist_router import Decoder,visible_feedback
    base=read(LIB/'protocol.json');old=list(base['experts']);nf=len(foes);nc,nr,ng,_,na=world.shape
    root=DEST/'models/motion-5213-n8';reader=Decoder(len(old),nf,True)
    reader.load_state_dict(torch.load(root/'actor.pt',weights_only=True));reader.eval()
    opener=old.index('original-mixture');memory=[]
    ordinary,_,_=bank('dev',4)
    for i in range(nc):
        for r in range(nr):
            with np.load(DATA/'dev'/f'{i:02}-{r:02}.npz') as z:motion=z['features'][0,:,opener]
            played=ordinary[i,r,0,:,opener].numpy()
            feedback=visible_feedback(played[:,0],played[:,1],np.broadcast_to(ctx[i,r,0,1:],(nf,2)))
            fixed=np.broadcast_to(np.eye(len(old),dtype=np.float32)[opener],(nf,len(old)))
            with torch.inference_mode():
                memory.append(reader(torch.from_numpy(np.concatenate((motion,feedback,fixed),1))).log_softmax(1).numpy())
    initial=np.concatenate(memory);total=len(initial);row=np.arange(total)
    cue=np.broadcast_to(bins[:,:,None,:],(nc,nr,nf,ng)).reshape(total,ng)
    games=world.transpose(0,1,3,2,4).reshape(total,ng,na)
    counts=np.stack([np.stack([(train[train_bins==b]==s).sum(0)+1 for s in (0,.5,1)],-1) for b in range(4)])
    prob=counts/counts.sum(-1,keepdims=True);win=prob[...,2];ids=np.tile(np.arange(nf),nc*nr)
    scores={'wtap':wt.transpose(0,1,3,2).reshape(total,ng)}
    for mode in ('expanded-history','first-only','old-history','blind','known-id'):
        evidence=np.zeros_like(initial,dtype=float);won=np.zeros((total,ng))
        for g in range(ng):
            b=cue[:,g];width=len(old) if mode=='old-history' else na
            if mode=='known-id':belief=np.eye(nf)[ids]
            else:
                h=evidence if mode!='blind' else np.zeros_like(evidence)
                belief=np.exp(h-h.max(1,keepdims=True));belief/=belief.sum(1,keepdims=True)
            a=np.einsum('bf,bfa->ba',belief,win[b,:,:width]).argmax(1) if g else np.full(total,opener)
            won[:,g]=games[row,g,a]
            if g==0:evidence=initial.copy()
            elif mode!='first-only':evidence+=np.log(prob[b,:,a,(2*won[:,g]).astype(int)])
        scores[mode]=won
    delta=((scores['expanded-history']==1).astype(float)-(scores['old-history']==1)).reshape(nc,nr,-1).mean(2)
    write_json(FOLDER/'crossplay'/f'routed-{covered:02}.json',dict(scores={k:dict(games=tally(w),series=tally(series(w)),
        opponents={f:tally(w[ids==j]) for j,f in enumerate(foes)}) for k,w in scores.items()},gain=float(delta.mean()),
        conditional_95=bootstrap(delta),opening='same broad actor; GRU from its first physical trace, then public results under each chosen actor'),fixed=True)


def follow():
    from remote_fit import HOST,REMOTE,RSYNC,SSH,invoke
    from remote_import import retrieve,available
    spec=read(FOLDER/'protocol.json');todo=[f for f in spec['targets'] if f not in spec['existing']];sent=set()
    rel=str(FOLDER.relative_to(OUT))+'/'
    grade_command='cd '+REMOTE+'/research/elite-league/single-sumo && ~/mc-sumo-league/venv/bin/python expanded_crossplay.py'
    while todo:
        for f in list(todo):
            path=FOLDER/'counters'/(f+'.json')
            if not path.exists():continue
            # Same frozen checkpoint previously verified by the canonical adversary search.
            if f not in sent:
                invoke([*RSYNC,'-R',str(path.relative_to(ROOT)),read(path)['policy']['reference'],HOST+':'+REMOTE+'/'])
                invoke([*SSH,grade_command+' start '+f]);sent.add(f)
            col=rel+'crossplay/columns/'+f+'.json'
            if available(col):
                retrieve([col]);todo.remove(f)
        if todo:time.sleep(30)
    invoke([*SSH,grade_command+' grade'])
    retrieve([rel+'crossplay/covered-27.json',rel+'crossplay/routed-27.json'])


if __name__=='__main__':
    if sys.argv[1]=='grade':grade()
    elif sys.argv[1]=='follow':follow()
    elif sys.argv[1]=='start':
        from remote_jobs import start
        name=sys.argv[2];start('counter-column-'+name,'expanded_crossplay.py',['column',name],FOLDER.relative_to(OUT)/'crossplay/columns'/(name+'.json'))
    else:column(sys.argv[2])
