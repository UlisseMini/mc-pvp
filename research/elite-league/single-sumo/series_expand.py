"""Add genuinely different response policies to the full league while reusing the exact eight-expert fixtures."""
import sys
import time

import numpy as np
import torch

from assay import lower_contrast
from learner import CLOCKS,write_json
from remote_league import read
from series_bank import CACHE,DEV_REPS,FOLDER,ROUNDS,tape
from series_eval import assess,load_models,matches,series,tally
from series_learn import HORIZONS,SEEDS,bank,fit
from series_memory import evaluate as memory_test

DEST=FOLDER/'diverse-experts'
DATA=CACHE/'diverse-experts'


def register():
    base=read(FOLDER/'protocol.json');archive=read(FOLDER/'archive-reversal/protocol.json')['experts']
    # An explicit order preserves the original eight logits when reading their bank.
    extra={f'archive-{n}':v for n,v in archive.items()}
    experts=dict(sorted({**base['experts'],**extra}.items()))
    write_json(DEST/'protocol.json',dict(field=base['field'],experts=experts,extra=extra,
        rationale='Sixteen of 27 opponents lost all 32 pilot series against the nine-game GRU. The eight recent responders rarely reversed their ranking. Add the two actual opposing styles from the historical tournament, then compare with the original eight-expert policies on exactly the same 27-opponent series.',
        design='All original eight expert outcomes are reused byte-for-byte. Simulate only the two additional experts. The training distribution and budgets match the original PPO for a direct library comparison. Evaluate the 2 and 8 schedule checkpoints. Grade series, ring-outs and whole-memory swaps separately. The archive-style selection used development data.'),fixed=True)


def build(split,reps):
    base=read(FOLDER/'protocol.json');spec=read(DEST/'protocol.json');extra=dict(spec,experts=spec['extra'])
    while not (FOLDER/split/f'bank-{reps:02}.json').is_file():time.sleep(15)
    clocks=CLOCKS[split]
    for r in range(reps):
        for i,clock in enumerate(clocks):tape(extra,split,r,i,clock,DATA)
    write_json(DEST/split/f'bank-{reps:02}.json',dict(recordings=len(clocks),reps=reps,
        original_experts=list(base['experts']),extra_experts=list(spec['extra']),new_fights=len(clocks)*reps*ROUNDS*len(spec['field'])*len(spec['extra'])),fixed=True)


def load(split,reps):
    spec=read(DEST/'protocol.json');record=read(DEST/split/f'bank-{reps:02}.json')
    a,c,w=bank(split,reps);b,n,v=bank(split,reps,DEST,DATA)
    if not torch.equal(c,n) or not torch.equal(w,v):raise ValueError('Expanded library changed seats, pings or matched W-tap outcomes')
    ids=record['original_experts']+record['extra_experts']
    order=[ids.index(k) for k in spec['experts']]
    return torch.cat((a,b),4).index_select(4,torch.tensor(order)),c,w


def run():
    for budget in (2,8):
        build('train',budget);train,cues,_=load('train',budget)
        for h in HORIZONS:
            seed=SEEDS[0];fit(train,cues,budget,h,seed,folder=DEST/f'learn-{budget:02}'/f'h{h}-s{seed}')
        build('dev',DEV_REPS);assess(budget,root=DEST,bank_loader=load)
        memory_test('diverse-experts',budget,bank_loader=load)
        while not (FOLDER/'dev'/f'evaluation-{budget:02}.json').exists():time.sleep(15)
        outcomes={}
        for label,root,loader in [('eight',FOLDER,bank),('ten',DEST,load)]:
            data,ctx,wt=loader('dev',DEV_REPS);nc,nr,_,nf,na,_=data.shape
            rivals=np.tile(np.arange(nf),nc*nr);clocks=np.repeat(np.arange(nc),nr*nf)
            full=data.permute(0,1,3,2,4,5).reshape(-1,ROUNDS,na,2)
            context=ctx[:,:,None].expand(nc,nr,nf,ROUNDS,3).reshape(-1,ROUNDS,3)
            outcomes[label]=matches(load_models(budget,9,na,nf,root),full,context,rivals,clocks)['won']
        diff=((series(outcomes['ten'])==1).astype(float)-(series(outcomes['eight'])==1)).reshape(nc,-1).mean(1)
        outcomes['wtap']=wt.permute(0,1,3,2).reshape(-1,ROUNDS).numpy()
        write_json(DEST/'dev'/f'library-{budget:02}.json',dict(paired_series_gain=float(diff.mean()),bounds=lower_contrast(diff,.025),
            scores={n:dict(series=tally(series(v)),ringouts=tally(v)) for n,v in outcomes.items()}),fixed=True)


if __name__=='__main__':
    if sys.argv[1:]==['register']:register()
    else:run()
