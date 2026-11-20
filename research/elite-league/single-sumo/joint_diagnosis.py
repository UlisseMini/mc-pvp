"""Cross the old/new routing and button policies on the same physical fixtures."""
import sys
import time

import numpy as np
import torch

from expert_population import Actor,SEED,STUDY
from joint_experts import DEST,rollout,sample
from learner import RTTS,Worker,write_json
from remote_league import read
from series_eval import series,tally
from series_targeted import DEST as LIB
from specialist_ablation import bootstrap


def run(step):
    root=DEST/'joint';spec=read(LIB/'protocol.json');names=read(STUDY/'protocol.json')['small'];na=len(names)
    while not all((root/f'u{step:03}-expert-{i}.net').exists() for i in range(na)) or not (root/f'u{step:03}-router.pt').exists():time.sleep(5)
    foes,tapes=sample('dev',0,0,names);ids=np.asarray(foes);worker=Worker(current_runtime=True);scores={};actions={}
    try:
        for age in (0,step):
            router=Actor(na,False);router.load_state_dict(torch.load(root/f'u{age:03}-router.pt',weights_only=True));router.eval()
            for low in (0,step):
                refs=[root/f'u{low:03}-expert-{i}.net' for i in range(na)]
                batch,_=rollout(worker,router,refs,foes,tapes,spec['field'],False)
                key=f'router{age}-buttons{low}';scores[key]=batch[0].numpy();actions[key]=batch[2].numpy()
                if age==low:
                    with np.load(root/f'u{low:03}.npz') as saved:
                        if not np.array_equal(saved['wins'],scores[key]) or not np.array_equal(saved['actions'],actions[key]):
                            raise ValueError('Component crossover diverged from official checkpoint')
    finally:worker.close()
    fixed=read(STUDY/'small'/f'seed-{SEED}'/'dev-u512.json')['scores']
    report={}
    for k,w in scores.items():
        report[k]=dict(games=tally(w),series=tally(series(w)),opponents={f:tally(w[ids==f]) for f in names},
            rtts={f'{a}/{b}':tally(w[np.array([[z[2:4]==(a,b) for z in t] for t in tapes])]) for a,b in RTTS},
            action_changes_vs_parent=int((actions[k]!=actions['router0-buttons0']).sum()))
    effects={}
    for a in scores:
        delta=((scores[a]==1).astype(float)-(scores['router0-buttons0']==1)).reshape(8,4,-1).mean(2)
        effects[a]=dict(gain=float(delta.mean()),conditional_95=bootstrap(delta))
    write_json(DEST/'diagnosis'/f'u{step:03}.json',dict(scores=report,effects=effects,wtap=fixed['wtap']['games']),fixed=True)


if __name__=='__main__':run(int(sys.argv[1]))
