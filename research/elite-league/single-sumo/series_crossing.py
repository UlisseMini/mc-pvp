"""A controlled real-game practice task where the two responses reverse on actual ten-game victories."""
from itertools import combinations
import sys
import time

import numpy as np

from learner import write_json
from remote_league import read
from series_bank import DEV_REPS,FOLDER
from series_eval import assess
from series_learn import HORIZONS,SEEDS,bank,fit

DEST=FOLDER/'crossing-drill'


def register():
    write_json(DEST/'selection-rule.json',dict(data='First 69120 training fights only. No development or prospective recording for selecting the opponents or experts.',
        objective='Find two real frozen opponents and two real frozen experts. Maximize the smallest margin over a 50% series win rate in the two preferred matchups and below 50% in the two disfavored ones. Use the worst half of training recordings for each of the four margins. Break ties by their mean margin. Report all four raw series rates. Use ten-game series wins to select this drill, because a ring-out advantage can have almost no effect on who wins a series.',
        grade='A two-action mechanism exercise. Compare PPO memory lengths and wrong-history interventions on the frozen development games. Also report all eight unrestricted fixed experts, W-tap and the full-field nine-game learner on the same two opponents. A gain restricted to the two-action exercise does not qualify the general league policy.'),fixed=True)


def select(stats):
    cfg=read(FOLDER/'protocol.json');names=list(cfg['field']);experts=list(cfg['experts'])
    titles=(stats[...,0].sum(2).numpy()>5)
    rates=np.stack([titles[h::2].mean(axis=(0,1)) for h in (0,1)])
    best=None
    for i,j in combinations(range(len(names)),2):
        for a,b in combinations(range(len(experts)),2):
            for left,right in ((a,b),(b,a)):
                gaps=np.stack([rates[:,i,left]-.5,.5-rates[:,i,right],rates[:,j,right]-.5,.5-rates[:,j,left]])
                score=(float(gaps.min()),float(gaps.mean()),-i,-j,-left,-right)
                if best is None or score>best:best=score
    worst,mean,i,j,left,right=best
    ids=sorted([-i,-j]);arms=sorted([-left,-right])
    result=dict(field={names[k]:cfg['field'][names[k]] for k in ids},experts={experts[k]:cfg['experts'][experts[k]] for k in arms},
        opponent_indices=ids,expert_indices=arms,minimum_training_margin=worst,mean_margin=mean,
        train_series_win_rates={names[k]:{experts[a]:rates[:,k,a].tolist() for a in arms} for k in ids})
    write_json(DEST/'protocol.json',result,fixed=True)
    return ids,arms


def run():
    train,cues,_=bank('train',2);ids,arms=select(train)
    for h in HORIZONS:
        seed=SEEDS[0];fit(train[:,:,:,ids][:,:,:,:,arms],cues,2,h,seed,folder=DEST/'learn-02'/f'h{h}-s{seed}')
    while not (FOLDER/'dev'/f'bank-{DEV_REPS:02}.json').exists():time.sleep(15)
    assess(2,root=DEST,opponents=ids,arms=arms)
    full=read(FOLDER/'dev/evaluation-02.json')['scores']
    foes=read(DEST/'protocol.json')['field']
    write_json(DEST/'dev/unrestricted-controls.json',{n:{k:s['opponents'][k] for k in foes} for n,s in full.items()
               if n.startswith('fixed-') or n in ('wtap','memory-9','train-ping-lookup','train-id-lookup')},fixed=True)


if __name__=='__main__':
    if sys.argv[1:]==['register']:register()
    else:run()
