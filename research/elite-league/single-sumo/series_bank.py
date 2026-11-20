"""Matched regulation duels for ten-game series. Only the played game's result and duration reach the learner."""
import argparse
import hashlib
import io
import random

import numpy as np

from learner import BUILD,CLOCKS,ROOT,RTTS,Worker,atomic,write_json
from remote_league import read
from stationary_field import DEST

FOLDER=DEST/'series-ten'
CACHE=BUILD/'series-ten'
ROUNDS=10
TRAIN_REPS=16
DEV_REPS=4
BUDGETS=(2,8,16)


def register():
    field=read(DEST/'protocol.json')['field']
    broad=read(DEST/'frontier-protocol.json')['controls']
    experts={**broad,**{k:field[k] for k in ('sg003-long-g1','sg003-long-seven','sg003-long-incumbent','reply-to-g1','reply-to-seven','reply-to-incumbent')}}
    write_json(FOLDER/'protocol.json',dict(field=field,experts=experts,rounds=ROUNDS,training_checkpoints=BUDGETS,
        rules='Play all ten independent seven-CPS ring-outs against one fixed nonadaptive opponent. A win gives one point and a draw half. More than five wins the series on points, five ties. The arena, client, expert and both players reset each game. Only the outer learner carries history. Each new series clears it.',
        inputs='At selection see game number, current series points and both displayed pings. Past tokens contain selected expert, public win/draw/loss, elapsed duel time and both pings. The legal learner receives no opponent identity, hidden state or counterfactual outcomes. Counterfactual outcomes supervise the trainer offline.',
        fixtures='Each potential expert plays an ordinary full regulation match with the same opponent, seat, independent input seeds and clock as the other choices in the same cell. The opponent resets every game. Precomputing these branches is equivalent to applying the chosen expert online. Training draws from train clocks only. Development contains four actual ten-match schedules per timing recording per opponent. Each test series uses ten independent seeds. No resampling for confidence.',
        grade='Primary outcome is outright series wins. Also report ties, underlying ring-outs by round, matchup, seat, and both pings, and W-tap at the same cap. Train controls with zero, one and nine previous matches. Compare full memory with the best equally trained memoryless chooser and the fixed generalists. Intervene on earlier memory before interpreting a gain as opponent-specific adaptation. Reserve new timing recordings for confirmation after a development pass.',
        budgets='Evaluate independently trained learners at 2, 8 and 16 training schedules per recording. The maximum uses 552960 simulated expert fights before training-time reuse. Grade the final budget with three independent learning seeds. Opponents in this panel are development opponents.'),fixed=True)


def tape(spec,split,rep,index,clock,folder=CACHE):
    foes=list(spec['field']);arms=list(spec['experts'])
    path=folder/split/f'{rep:02}-{index:02}.npz'
    if path.exists():return
    stats=np.zeros((ROUNDS,len(foes),len(arms),2),np.float32)
    wt=np.empty((ROUNDS,len(foes)),np.float32) if split!='train' else np.empty(0,np.float32)
    context=np.empty((ROUNDS,3),np.int16)
    rng=random.Random(f'sumo-series-434811/{split}/{rep}/{index}')
    w=Worker(current_runtime=True)
    try:
        for g in range(ROUNDS):
            seat=(rep+index+g)%2
            own,foe=RTTS[(g+index+rep//2)%len(RTTS)]
            if split=='train' and rng.random()<.5:own,foe=rng.randrange(151),rng.randrange(151)
            context[g]=(seat,own,foe)
            # Common seeds across opponent identities prevent an identity fingerprint at spawn.
            x,y=rng.getrandbits(60),rng.getrandbits(60)
            tail=[[spec['field'][n]['reference'],clock,'ab'[seat],own,foe,x,y] for n in foes]
            for j,name in enumerate(arms):
                ref=spec['experts'][name]['reference']
                stats[g,:,j,:]=[[row[0],row[1]] for row in w.run([[ref,*r] for r in tail],False)]
            if split!='train':wt[g]=[r[0] for r in w.run([['wtap',*r] for r in tail],False)]
    finally:w.close()
    buf=io.BytesIO();np.savez_compressed(buf,stats=stats,wt=wt,context=context)
    atomic(path,buf.getvalue())


def create(split):
    spec=read(FOLDER/'protocol.json')
    for ref in spec['experts'].values():
        if hashlib.sha256((ROOT/ref['reference']).read_bytes()).hexdigest()!=ref['sha256']:raise ValueError('Changed series expert')
    if split=='fresh':
        clocks=read(FOLDER/'prospective/calendars/manifest.json')['selected'];reps=DEV_REPS
    else:clocks=CLOCKS[split];reps=TRAIN_REPS if split=='train' else DEV_REPS
    for rep in range(reps):
        for i,clock in enumerate(clocks):tape(spec,split,rep,i,clock)
        if rep+1 in BUDGETS or rep+1==reps:
            write_json(FOLDER/split/f'bank-{rep+1:02}.json',dict(recordings=len(clocks),reps=rep+1,experts=list(spec['experts']),
                expert_fights=len(clocks)*(rep+1)*ROUNDS*len(spec['field'])*len(spec['experts'])),fixed=True)


if __name__=='__main__':
    arg=argparse.ArgumentParser();arg.add_argument('task',choices=('register','train','dev','fresh'));op=arg.parse_args().task
    if op=='register':register()
    else:create(op)
