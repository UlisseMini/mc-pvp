"""Isolate genuine opponent-dependent responses in two real frozen matchups before grading the full league."""
from itertools import combinations
import time

import numpy as np

from learner import write_json
from remote_league import read
from series_bank import DEV_REPS,FOLDER
from series_eval import assess
from series_learn import HORIZONS,SEEDS,bank,fit

PAIR=FOLDER/'pair-drill'


def select(stats,ctx):
    spec=read(FOLDER/'protocol.json');names=list(spec['field']);arms=list(spec['experts'])
    x=stats[...,0].numpy();p=ctx[...,1:].numpy()>=70;bins=2*p[...,0]+p[...,1]
    folded=[]
    for parity in (0,1):
        train=np.arange(len(x))%2==parity
        fit=np.stack([x[train][bins[train]==v].mean(0) for v in range(4)])
        held=np.stack([x[~train][bins[~train]==v].mean(0) for v in range(4)])
        folded.append((fit,held))
    scores=[]
    for i,j in combinations(range(len(names)),2):
        gain=[]
        for train,test in folded:
            a=train[:,[i,j]];b=test[:,[i,j]]
            informed=np.take_along_axis(b,a.argmax(2)[:,:,None],axis=2).mean(axis=(0,2))
            shared=np.broadcast_to(a.mean(1).argmax(1)[:,None,None],(4,2,1))
            fixed=np.take_along_axis(b,shared,axis=2).mean(axis=(0,2))
            gain.append(informed-fixed)
        gains=np.mean(gain,axis=0)
        scores.append((float(gains.min()),float(gains.mean()),i,j))
    minimum,mean,i,j=max(scores)
    selected=[names[i],names[j]]
    write_json(PAIR/'protocol.json',dict(field={n:spec['field'][n] for n in selected},experts=spec['experts'],indices=[i,j],
        selection='Two opponents chosen exclusively from the first 69120 expert training fights. Maximize the smaller opponent-specific cross-fitted gain for an ID-and-ping lookup over a ping-only lookup. Every fixed expert is still available, and no table or ID enters the legal policy.',
        training_only_payoff_gain=dict(minimum=minimum,mean=mean),scope='Two-opponent mechanism diagnostic. Report separately from robustness across all 27 opponents.'),fixed=True)
    return [i,j]


def run():
    while not (FOLDER/'train/bank-02.json').is_file():time.sleep(15)
    train,cues,_=bank('train',2);idx=select(train,cues)
    for h in HORIZONS:
        seed=SEEDS[0];fit(train[:,:,:,idx],cues,2,h,seed,folder=PAIR/'learn-02'/f'h{h}-s{seed}')
    while not (FOLDER/'dev'/f'bank-{DEV_REPS:02}.json').is_file():time.sleep(15)
    assess(2,root=PAIR,opponents=idx)


if __name__=='__main__':run()
