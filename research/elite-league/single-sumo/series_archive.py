"""A ten-game response-learning experiment using a large observed reversal between real archive policies."""
import sys
import time

from learner import CLOCKS,EVIDENCE,write_json
from remote_league import read
from series_bank import CACHE,DEV_REPS,FOLDER,ROUNDS,tape
from series_eval import assess
from series_learn import HORIZONS,SEEDS,bank,fit
from series_memory import evaluate as memory_test

ROOT=FOLDER/'archive-reversal'
DATA=CACHE/'archive-reversal'


def register():
    tournament=read(EVIDENCE/'g002/tournament.json');matrix=tournament['matrix'];field=tournament['field']
    opponents=('mmc13-actor-2-fit','sumo-vote-final')
    experts=('v4-ppo-g0496','sumo-seven-first')
    write_json(ROOT/'protocol.json',dict(field={n:field[n] for n in opponents},experts={n:field[n] for n in experts},
        discovery={p:{o:matrix[p][o] for o in opponents} for p in (*experts,'wtap')},
        rationale='A scan of the exposed generation-two tournament found two real policies whose results reverse against two different opponents. Explicitly practice recognition and switching within this two-action game. All four actors are frozen; neither training nor inference alters combat mechanics. The full 27-opponent league and the original eight-expert studies continue independently.',
        splits='Opponent and expert choice used the old development clocks. Train on train clocks. Report the ordinary development calendars as exposed. Reserve 24 future real timing recordings after all final weights freeze. Candidate and controls play new series on these clocks.',
        budgets='At first train on 2 series schedules per timing recording, then 16. Final evaluation uses three independently trained policies per horizon and averages their logits. Always report plain W-tap and the strongest general-purpose MLP on these opponents as unrestricted references.',
        claims='A gain here establishes a mechanism in real game dynamics within the declared response library. It cannot establish full-league superiority. Scoreboard-only learning and information requiring memory are tested separately.'),fixed=True)


def load(split,reps):return bank(split,reps,ROOT,DATA)


def create(split,reps):
    spec=read(ROOT/'protocol.json')
    clocks=read(ROOT/'prospective/calendars/manifest.json')['selected'] if split=='fresh' else CLOCKS[split]
    for r in range(reps):
        for i,clock in enumerate(clocks):tape(spec,split,r,i,clock,DATA)
    write_json(ROOT/split/f'bank-{reps:02}.json',dict(recordings=len(clocks),reps=reps,experts=list(spec['experts']),
               expert_fights=len(clocks)*reps*ROUNDS*len(spec['field'])*len(spec['experts'])),fixed=True)


def evaluate(split,budget):
    assess(budget,split,root=ROOT,bank_loader=load)
    memory_test('archive-reversal',budget,split,bank_loader=load)
    from series_eval import series,tally
    from series_bank import DEST
    from learner import Worker
    import numpy as np
    # The full-field control uses identical fixtures on development. Replay its frozen weights on fresh clocks too.
    spec=read(ROOT/'protocol.json');names=list(spec['field']);control=read(DEST/'frontier-protocol.json')['controls']['original-mixture']
    if split=='dev':
        while not (FOLDER/'dev/evaluation-02.json').exists():time.sleep(5)
        d=read(FOLDER/'dev/evaluation-02.json')['scores']['fixed-original-mixture']['opponents']
        row={n:d[n] for n in names}
    else:
        schedules=read(ROOT/'prospective/calendars/manifest.json')['selected']
        import random
        from learner import RTTS
        w=Worker(current_runtime=True);games=[]
        try:
            for i,clock in enumerate(schedules):
                for r in range(DEV_REPS):
                    rng=random.Random(f'sumo-series-434811/{split}/{r}/{i}');rows=[]
                    for g in range(ROUNDS):
                        a,b=RTTS[(g+i+r//2)%len(RTTS)];x,y=rng.getrandbits(60),rng.getrandbits(60)
                        rows.extend([[control['reference'],spec['field'][n]['reference'],clock,'ab'[(r+i+g)%2],a,b,x,y] for n in names])
                    games.append(np.array([v[0] for v in w.run(rows,False)]).reshape(ROUNDS,-1))
        finally:w.close()
        games=np.stack(games)
        row={n:dict(series=tally(series(games[:,:,j])),ringouts=tally(games[:,:,j])) for j,n in enumerate(names)}
    write_json(ROOT/split/'unrestricted.json',dict(controls=control,opponents=row),fixed=True)


def run():
    for b in (2,16):
        create('train',b);train,cues,_=load('train',b)
        for h in HORIZONS:
            for seed in SEEDS if b==16 else SEEDS[:1]:
                fit(train,cues,b,h,seed,folder=ROOT/f'learn-{b:02}'/f'h{h}-s{seed}')
        create('dev',DEV_REPS);evaluate('dev',b)


if __name__=='__main__':
    if sys.argv[1:]==['register']:register()
    elif sys.argv[1:]==['fresh']:create('fresh',DEV_REPS);evaluate('fresh',16)
    else:run()
