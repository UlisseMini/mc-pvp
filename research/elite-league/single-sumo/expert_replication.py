"""Fresh physical action seeds and a second real MMC platform for the frozen three-expert PPO router."""
import io
import sys
import time

import numpy as np
import torch

from expert_population import Actor,SEED,STUDY,play
from learner import CLOCKS,RTTS,Worker,atomic,write_json
from remote_league import read
from series_eval import series,tally
from series_hidden import donor_rows
from series_targeted import DEST as LIB
from series_trace import CHANNELS
from specialist_ablation import bootstrap
from specialist_diagnosis import DATA,fixture_inputs,signal
from specialist_router import visible_feedback

DEST=STUDY/'fresh-seeds-and-map'
REPS=16


def register():
    write_json(DEST/'protocol.json',dict(seed_namespace='exp-replication-8329719',clock_split='eight exposed development timing recordings',
        maps=['mmc13','mmc-221'],reps=REPS,opponents=read(STUDY/'protocol.json')['small'],training_seed=SEED,updates=512,
        grade='Freeze the seed-22819 recurrent PPO all three expert weights before simulating these action seeds. Repeat 128 ten-game schedules per opponent on each platform. All actors receive the same real physics seeds, both pings and seat at a given decision. The 221-block recorded MMC floor has spawns ten blocks apart. It is an exposed project map and was absent from this router’s training. Time recordings are reused and all new physical game seeds differ from training and first development. Never label this independent timing transfer.',
        arms='Ring-out-only memory PPO, erased memory, matched same or wrong history, literal identity to dedicated expert, the unrestricted generalist, all three fixed combat MLPs and W-tap. Report scores separately for the first bout and rematches. No selection or training on either map.'),fixed=True)


def cache(map_name,shard):
    if map_name!='mmc13':
        from expert_map_grade import part
        return part(map_name,shard)
    cfg=read(STUDY/'protocol.json');foes=cfg['small'];spec=read(LIB/'protocol.json');arms=[cfg['pairs'][f] for f in foes]+['original-mixture','wtap']
    worker=Worker(current_runtime=True,map_name=None if map_name=='mmc13' else map_name)
    try:
        for i,clock in enumerate(CLOCKS['dev']):
            for r in range(REPS):
                if (i*REPS+r)%2!=shard:continue
                path=DATA/'expert-replication'/map_name/f'{i:02}-{r:02}.npz'
                if path.exists():continue
                scores=[];features=[];ctx=[]
                for side,a,b,x,y in fixture_inputs('exp-replication-8329719',r,i):
                    ctx.append([side,a,b]);ww=[];samples=[]
                    for j,arm in enumerate(arms):
                        ref='wtap' if arm=='wtap' else spec['experts'][arm]['reference']
                        plan=[[ref,spec['field'][n]['reference'],clock,'ab'[side],a,b,x,y] for n in foes]
                        games=worker.run(plan,j<len(foes));ww.append([w for w,_,_ in games])
                        if j<len(foes):
                            samples.append([np.concatenate((signal(v[:,CHANNELS])[360:],visible_feedback(np.asarray(w),np.asarray(ms),np.asarray([a,b])))) for w,ms,v in games])
                    scores.append(np.stack(ww,1));features.append(np.stack(samples,1))
                buf=io.BytesIO();np.savez_compressed(buf,world=np.asarray(scores,np.float32),x=np.stack(features),ctx=np.array(ctx,np.int16));atomic(path,buf.getvalue())
    finally:worker.close()
    write_json(DEST/map_name/f'part-{shard}.json',dict(complete=True),fixed=True)


def grade(map_name):
    if map_name!='mmc13':
        from expert_map_grade import grade as map_grade
        return map_grade(map_name)
    dest=DEST/map_name
    while not all((dest/f'part-{i}.json').exists() for i in range(2)):time.sleep(5)
    names=read(STUDY/'protocol.json')['small'];nf=len(names);n=len(CLOCKS['dev']);w=[];x=[];c=[]
    for i in range(n):
        for r in range(REPS):
            with np.load(DATA/'expert-replication'/map_name/f'{i:02}-{r:02}.npz') as z:
                w.append(z['world']);x.append(z['x']);c.append(z['ctx'])
    ss=torch.from_numpy(np.stack(w).transpose(0,2,1,3).reshape(-1,10,nf+2));xx=torch.from_numpy(np.stack(x).transpose(0,2,1,3,4).reshape(-1,10,nf,198))
    cc=torch.from_numpy(np.stack(c).reshape(n,REPS,10,3));cue=cc[:,:,None].expand(n,REPS,nf,10,3).reshape(-1,10,3);p=cue[...,1:].float();idx=np.arange(len(xx))
    model=Actor(nf,False);model.load_state_dict(torch.load(STUDY/'small'/f'seed-{SEED}'/'memory/u512.pt',weights_only=True))
    same,wrong=donor_rows(cc,nf);ids=np.tile(np.arange(nf),n*REPS)
    scores={'wtap':ss[:,:,-1].numpy(),'broad':ss[:,:,-2].numpy(),'dedicated':ss[idx[:,None],np.arange(10)[None,:],ids[:,None]].numpy()}
    for j,f in enumerate(names):scores['fixed-'+f]=ss[:,:,j].numpy()
    for label,donor,clear in [('memory',None,False),('reset',None,True),('same',torch.from_numpy(same),False),('wrong',torch.from_numpy(wrong),False)]:
        scores[label]=play(model,xx,ss[:,:,:nf],p,True,donor,clear)[2].numpy()
    report={k:dict(games=tally(v),series=tally(series(v)),opener=tally(v[:,0]),rematches=tally(v[:,1:]),opponents={f:tally(v[ids==j]) for j,f in enumerate(names)},
          seats={s:tally(v[cue[...,0].numpy()==j]) for j,s in enumerate('ab')},rtts={f'{a}/{b}':tally(v[(p[...,0].numpy()==a)&(p[...,1].numpy()==b)]) for a,b in RTTS}) for k,v in scores.items()}
    effects={}
    for a,b in [('memory','reset'),('memory','broad'),('memory','dedicated'),('same','wrong')]:
        for label,col in [('all',slice(None)),('rematches',slice(1,None))]:
            delta=((scores[a][:,col]==1).astype(float)-(scores[b][:,col]==1)).reshape(n,REPS,-1).mean(2)
            effects[f'{a} minus {b} {label}']=dict(delta=float(delta.mean()),conditional_95=bootstrap(delta))
    write_json(dest/'result.json',dict(scores=report,effects=effects,physical_games=8*REPS*10*nf*(nf+2)),fixed=True)


if __name__=='__main__':
    if sys.argv[1]=='register':register()
    elif sys.argv[1]=='grade':grade(sys.argv[2])
    else:cache(sys.argv[1],int(sys.argv[2]))
