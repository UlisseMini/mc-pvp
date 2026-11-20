"""Test whether an independently learned answer to the weakest opponent enables useful series adaptation."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import hashlib
import sys
import time

import numpy as np
import torch

from assay import lower_contrast
from learner import ROOT,write_json
from remote_league import OUT,read
from series_bank import CACHE,DEV_REPS,FOLDER,ROUNDS
from series_eval import assess,load_models,matches,series,tally
from series_expand import build,load
from series_learn import HORIZONS,SEEDS,fit

DEST=FOLDER/'targeted-response'
DATA=CACHE/'targeted-response'


def register():
    write_json(DEST/'design.json',dict(
        question='Against the weakest opponent, no original expert won even half its games. Does adding the independent 65536-bout response make remembering this matchup worthwhile?',
        qualification='Use the one previously registered search. If its frozen 512-bout outright win rate fails to exceed 60%, record the miss and do not expand the library with it.',
        controls='Reuse all original eight experts and exactly the same fights for every shared action. Add only the single validated response. Compare uniform opponent training with a second training distribution that draws half the time uniformly from everyone and half from the four hardest training opponents. Rank hardness by the strongest fixed nine-expert outright game rate on training only. Use independently identical seeds, update budgets and action sets. All 27 opponents retain equal weight in evaluation.',
        reporting='Use the first 2 and then 8 schedules per training clock. Grade all five memory horizons, ping-only and true-ID lookups and every fixed expert, with plain W-tap on the matched panel. Compare the 9-game policies with and without the new expert and with and without the difficult-opponent curriculum. Report outright wins, draws and losses across the field and on the original weakest opponent. The native test uses only a participant’s own feedback across matches.'),fixed=True)


def bank(split,reps):return load(split,reps,DEST,DATA)


def run():
    spec=read(DEST/'protocol.json')
    path=ROOT/spec['extra']['new-response']['reference']
    if hashlib.sha256(path.read_bytes()).hexdigest()!=spec['extra']['new-response']['sha256']:raise ValueError('Targeted expert changed')
    curriculum=DEST/'hard-curriculum'
    write_json(curriculum/'protocol.json',spec,fixed=True)
    names=list(spec['field']);target=spec['target']
    for budget in (2,8):
        build('train',budget,DEST,DATA);train,cues,_=bank('train',budget)
        win_rates=(train[...,0]==1).float().mean((0,1,2)).max(1).values.numpy()
        nearest=sorted(range(len(names)),key=lambda i:(win_rates[i],names[i]))[:4]
        weights=torch.full((len(names),),.5/len(names));weights[nearest]+=.5/len(nearest)
        write_json(curriculum/f'sampling-{budget:02}.json',dict(hardest=[names[i] for i in nearest],
            sampling_probability=dict(zip(names,weights.tolist())),strongest_fixed_ringout=dict(zip(names,win_rates.tolist()))),fixed=True)
        build('dev',DEV_REPS,DEST,DATA)
        for root,sampling in ((DEST,None),(curriculum,weights)):
            for h in HORIZONS:
                seed=SEEDS[0]
                fit(train,cues,budget,h,seed,folder=root/f'learn-{budget:02}'/f'h{h}-s{seed}',opponent_weights=sampling)
            assess(budget,root=root,bank_loader=bank)
        from series_learn import bank as original
        outcomes={}
        for label,root,loader in [('eight',FOLDER,original),('nine',DEST,bank),('curriculum',curriculum,bank)]:
            data,c,wt=loader('dev',DEV_REPS);nc,nr,_,nf,na,_=data.shape
            rivals=np.tile(np.arange(nf),nc*nr);clocks=np.repeat(np.arange(nc),nr*nf)
            world=data.permute(0,1,3,2,4,5).reshape(-1,ROUNDS,na,2)
            context=c[:,:,None].expand(nc,nr,nf,ROUNDS,3).reshape(-1,ROUNDS,3)
            outcomes[label]=matches(load_models(budget,9,na,nf,root),world,context,rivals,clocks)['won']
        outcomes['wtap']=wt.permute(0,1,3,2).reshape(-1,ROUNDS).numpy()
        result={}
        for scope,mask in [('field',np.ones_like(rivals,dtype=bool)),(target,rivals==names.index(target))]:
            runs={n:w[mask] for n,w in outcomes.items()}
            effects={}
            for a,b in [('nine','eight'),('curriculum','nine'),('curriculum','eight')]:
                for unit,group in [('game',runs),('series',{n:series(w) for n,w in runs.items()})]:
                    d=((group[a]==1).astype(float)-(group[b]==1)).reshape(nc,-1).mean(1)
                    effects[f'{a} minus {b} {unit}']=dict(mean=float(d.mean()),adjusted_bounds=lower_contrast(d,.05/24))
            result[scope]=dict(scores={n:dict(series=tally(series(w)),games=tally(w)) for n,w in runs.items()},effects=effects)
        write_json(DEST/'dev'/f'comparison-{budget:02}.json',result,fixed=True)


def follow():
    from remote_fit import HOST,REMOTE,RSYNC,SSH,invoke
    from remote_import import wait_for
    from series_follow import collect,native
    search=FOLDER/'weakest-response'
    while not (search/'result.json').exists():time.sleep(15)
    verdict=read(search/'result.json')
    if not verdict['confirmed']:
        write_json(DEST/'closed.json',dict(budget=verdict['budget'],target=verdict['target'],score=verdict['challenger_result'],wtap=verdict['wtap']),fixed=True)
        return
    base=read(FOLDER/'protocol.json');target=read(search/'protocol.json')['target']
    if base['field'][target]['sha256']!=verdict['target']['sha256']:raise ValueError('Wrong adversary target')
    extra={'new-response':verdict['policy']}
    spec=dict(field=base['field'],experts={**base['experts'],**extra},extra=extra,target=target)
    write_json(DEST/'protocol.json',spec,fixed=True)
    # The frozen response was already trained on this host. Send the exact graded model alongside its specification.
    files=[str((DEST/'protocol.json').relative_to(ROOT)),verdict['policy']['reference']]
    invoke([*RSYNC,'--relative',*files,HOST+':'+REMOTE+'/'])
    invoke([*SSH,'cd '+REMOTE+' && python3 research/elite-league/single-sumo/remote_jobs.py'])
    prefix=str(DEST.relative_to(OUT))+'/'
    with ThreadPoolExecutor(max_workers=2) as pool:
        jobs=[]
        for budget in (2,8):
            wait_for([prefix+f'hard-curriculum/sampling-{budget:02}.json',prefix+'hard-curriculum/protocol.json',
                      prefix+f'dev/evaluation-{budget:02}.json',prefix+f'hard-curriculum/dev/evaluation-{budget:02}.json',
                      prefix+f'dev/comparison-{budget:02}.json'])
            if budget==8:
                for root in (DEST,DEST/'hard-curriculum'):
                    collect(budget,root);jobs.append(pool.submit(native,budget,root))
        for job in jobs:job.result()


if __name__=='__main__':
    if sys.argv[1:]==['register']:register()
    elif sys.argv[1:]==['follow']:follow()
    else:run()
