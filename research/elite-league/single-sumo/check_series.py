"""Exercise the ten-game trainer and history ablations in a game with a known informative outcome."""
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch
import random

import numpy as np
import torch

from learner import BUILD,CLOCKS,RTTS,Worker,write_json
from remote_league import read
from series_bank import CACHE,FOLDER
import series_eval
import series_bayes
from series_eval import matches,series
from series_learn import SeriesPolicy,choose,fit


def run():
    # Two fixed opponent types. Exactly one expert beats each. The first outcome identifies the type.
    x=torch.zeros(2,2,10,2,2,2);x[...,1]=5000
    for i in range(2):x[:,:,:,i,i,0]=1
    ctx=torch.zeros(2,2,10,3,dtype=torch.int16);ctx[...,1:]=torch.tensor([RTTS[i%4] for i in range(10)])
    rivals=np.tile([0,1],4);tapes=np.repeat([0,1],4)
    worlds=x.permute(0,1,3,2,4,5).reshape(8,10,2,2)
    obs=ctx[:,:,None].expand(2,2,2,10,3).reshape(-1,10,3)
    edges,prob=series_bayes.learn(x,ctx)
    belief=series_bayes.play(edges,prob,worlds.numpy(),obs.numpy(),rivals,tapes,9,True)
    assert belief['won'].sum()==76
    for j in range(2):
        history=[]
        for g in range(10):
            a,b=obs[j,g,1:].tolist();action=series_bayes.choose_public(edges,prob,history,a,b,True)
            assert action==belief['act'][j,g]
            history.append((action,belief['won'][j,g],5000.,a,b))
    with TemporaryDirectory(dir=BUILD) as temp:
        learners={}
        for h in (-1,0,1,3,9):
            folder=Path(temp)/str(h)
            fit(x,ctx,2,h,4101,updates=192 if h in (-1,9) else 2,batch=64,folder=folder)
            model=SeriesPolicy(2,2,h);model.load_state_dict(torch.load(folder/'actor.pt',weights_only=True));model.eval()
            learners[h]=[model]
            full=matches([model],worlds,obs,rivals,tapes)
            assert np.all(full['choices'][:,0]==full['choices'][0,0]),'Hidden opponent identity at game one'
            if h in (-1,9):
                print('horizon',h,'series W/T/L',[(series(full['won'])==v).sum().item() for v in (1,.5,0)],
                      'ring-outs',int(full['won'].sum()),'/ 80',flush=True)
            if h<0:assert (series(full['won'])==1).sum()<=4
            if h==9:
                assert (series(full['won'])==1).sum()==8 and full['won'].sum()>=72
                for variant in ('same','wrong','reset'):
                    out=matches([model],worlds,obs,rivals,tapes,variant,full)
                    assert out['won'].shape==(8,10)
                for j in range(2):
                    history=[]
                    for g in range(10):
                        a,b=obs[j,g,1:].tolist();choice=choose([model],history,a,b,2)
                        assert choice==full['choices'][j,g]
                        history.append((choice,float(full['won'][j,g]),5000.,a,b))
        path=Path(temp)/'report'
        write_json(path/'protocol.json',dict(field={'one':{},'two':{}},experts={'east':{},'west':{}}))
        with patch.object(series_eval,'FOLDER',path),patch.object(series_eval,'load_models',side_effect=lambda budget,h,*args:learners[h]),patch.object(series_eval,'bank',return_value=(x,ctx,x[:,:,:,:,0,0])):
            series_eval.assess(2)
        score=read(path/'dev/evaluation-02.json')
        assert score['scores']['memory-9']['series']==[8,0,0] and score['scores']['wtap']['ringouts']==[40,0,40]


def replay():
    spec=read(FOLDER/'protocol.json');arms=list(spec['experts']);foes=list(spec['field'])
    chosen=[foes.index(n) for n in ('wtap','sg003-long-g1','reply-to-seven')]
    rng=random.Random('sumo-series-434811/dev/0/0');n=0
    w=Worker(current_runtime=True)
    try:
        with np.load(CACHE/'dev/00-00.npz') as data:
            for g in range(10):
                a,b=RTTS[g%4];x,y=rng.getrandbits(60),rng.getrandbits(60)
                rows=[];expected=[]
                for j in chosen:
                    ref=spec['field'][foes[j]]['reference']
                    for i,arm in enumerate(arms):
                        rows.append([spec['experts'][arm]['reference'],ref,CLOCKS['dev'][0],'ab'[g%2],a,b,x,y])
                        expected.append(data['stats'][g,j,i])
                actual=np.array([[r[0],r[1]] for r in w.run(rows,False)],dtype='f4')
                if not np.array_equal(actual,np.array(expected)):raise ValueError('Stored score or ending time differs')
                n+=len(rows)
    finally:w.close()
    write_json(FOLDER/'bank-parity.json',dict(independent_regulation_replays=n,matched='every ring-out, draw and elapsed millisecond',
               mode='three fixed opponents, eight experts, all ten bouts of an independently reconstructed development fixture'),fixed=True)


if __name__=='__main__':
    import sys
    if sys.argv[1:]==['replay']:replay()
    else:run()
