"""Hold training fixtures and architecture fixed while changing the reward for a ten-game series."""
import sys
import time

import numpy as np

from assay import lower_contrast
from learner import write_json
from remote_league import read
from series_bank import DEV_REPS,FOLDER,ROUNDS
from series_eval import assess,load_models,matches,series,tally
from series_learn import HORIZONS,SEEDS,bank,fit


def register():
    write_json(FOLDER/'reward-ablation.json',dict(discovery='In the archive-reversal pilot the blind learner won every series with 324 ring-outs and 310 draws. The nine-game GRU scored 492 ring-outs and won fewer series.',
        treatment='Keep the paired fixtures, opponent distribution, seeds, GRU, counterfactual auxiliary targets, optimizer and update budget fixed. Change only the PPO return to total outright ring-outs over ten games. Draws and losses both contribute zero ring-outs. Report ordinary series outcomes under the original points rule alongside game wins.',
        comparisons='Fit both rewards on the 27-opponent field and the two-opponent archive drill. At the first budget compare the new nine-game policy with its equal-reward blind, scoreboard and one-game controls, with plain W-tap, and with the nine-game policy trained for winning series. A gain in ring-outs and a gain in series wins are separate claims. This is a diagnostic on development clocks; the independent archive confirmation is scored separately.'),fixed=True)


def run(task,split='dev'):
    source=FOLDER if task=='field' else FOLDER/'archive-reversal'
    root=source/'ringout-reward'
    if task=='field':loader=bank
    else:
        from series_archive import load as loader
    spec=read(source/'protocol.json');write_json(root/'protocol.json',dict(field=spec['field'],experts=spec['experts'],objective='total outright ring-outs'),fixed=True)
    b=2
    while not (source/'dev/evaluation-02.json').is_file():time.sleep(10)
    train,cues,_=loader('train',b)
    for h in HORIZONS:
        seed=SEEDS[0]
        fit(train,cues,b,h,seed,folder=root/f'learn-{b:02}'/f'h{h}-s{seed}',objective='ringouts')
    while not (source/split/f'bank-{DEV_REPS:02}.json').is_file():time.sleep(10)
    assess(b,split,root=root,bank_loader=loader)
    data,c,wt=loader(split,DEV_REPS);nc,nr,_,nf,na,_=data.shape
    worlds=data.permute(0,1,3,2,4,5).reshape(-1,ROUNDS,na,2)
    ctx=c[:,:,None].expand(nc,nr,nf,ROUNDS,3).reshape(-1,ROUNDS,3)
    rivals=np.tile(np.arange(nf),nc*nr);tapes=np.repeat(np.arange(nc),nr*nf)
    scores={'wtap':wt.permute(0,1,3,2).reshape(-1,ROUNDS).numpy()}
    for label,folder,h in [('series-reward',source,9),('ringout-reward',root,9),('blind',root,-1),('scoreboard',root,0),('one-game',root,1)]:
        models=load_models(b,h,na,nf,folder)
        played=matches(models,worlds,ctx,rivals,tapes)
        scores[label]=played['won']
        if label=='ringout-reward':
            for mode in ('all-same','all-wrong','same','wrong'):
                scores[mode]=matches(models,worlds,ctx,rivals,tapes,mode,played)['won']
    effects={}
    contrasts=[('ringout-reward',n) for n in ('series-reward','blind','scoreboard','one-game','all-wrong')]+[('all-same','all-wrong'),('same','wrong')]
    for a,b in contrasts:
        va=scores[a];vb=scores[b]
        e={}
        for name,v in [('games',(va==1).astype(float)-(vb==1)),('series',(series(va)==1).astype(float)-(series(vb)==1))]:
            d=v.reshape(nc,-1).mean(1);e[name]=dict(diff=float(d.mean()),bounds=lower_contrast(d,.05/(2*len(contrasts))))
        effects[a+' minus '+b]=e
    write_json(root/split/'reward-comparison.json',dict(scores={n:dict(games=tally(v),series=tally(series(v))) for n,v in scores.items()},
               effects=effects,scope=task),fixed=True)


if __name__=='__main__':
    if sys.argv[1:]==['register']:register()
    else:run(sys.argv[1],sys.argv[2] if len(sys.argv)>2 else 'dev')
