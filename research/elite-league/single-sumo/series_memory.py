"""Distinguish useful cross-game memory from the narrower claim that older games still help after the last one."""
import sys
import time

import numpy as np

from assay import lower_contrast
from learner import write_json
from remote_league import read
from series_bank import BUDGETS,DEV_REPS,FOLDER,ROUNDS
from series_eval import load_models,matches,series,tally
from series_learn import bank


def register():
    write_json(FOLDER/'memory-tests.json',dict(timing='Register after the first full-field and two-opponent 69120-fight results. Those two panels are exploratory. Grade the crossover drill and larger budgets with their controls frozen.',
      questions='How much do previous matches contribute beyond the scoreboard? Does one previous match already suffice? The original protocol also tests whether games older than the most recent one matter. Keep its stronger gate separate.',
      interventions='For horizon one and horizon nine swap every past game token from a different recording with exactly the same current scoreboard and current pings. Compare donors from the same opponent and a different opponent. Preserve the real remaining games and choices. Report the fraction that can be matched. No matches are discarded when evaluating a series.',
      qualification='Within each horizon require positive recording-level bounds on series wins versus blind and scoreboard-only learners, the training-only ping lookup, every fixed expert available in this task, mismatched memory, and same-opponent versus mismatched memory. For horizon nine separately calculate its advantage over one game of memory. Correct for both horizons and all planned contrasts. The restricted drill cannot promote the general league champion.'),fixed=True)


def evaluate(task,budget,split='dev',bank_loader=None):
    root=FOLDER if task=='field' else FOLDER/task
    spec=read(root/'protocol.json');names=list(spec['field']);experts=list(spec['experts'])
    loader=bank if bank_loader is None else bank_loader
    stats,c,wt=loader(split,DEV_REPS)
    idx=spec.get('opponent_indices',spec.get('indices'));arms=spec.get('expert_indices')
    if idx is not None:stats=stats[:,:,:,idx];wt=wt[:,:,:,idx]
    if arms is not None:stats=stats[:,:,:,:,arms]
    nc,nr,_,nf,na,_=stats.shape
    worlds=stats.permute(0,1,3,2,4,5).reshape(-1,ROUNDS,na,2)
    ctx=c[:,:,None].expand(nc,nr,nf,ROUNDS,3).reshape(-1,ROUNDS,3)
    rivals=np.tile(np.arange(nf),nc*nr);tapes=np.repeat(np.arange(nc),nr*nf)
    wtap=wt.permute(0,1,3,2).reshape(-1,ROUNDS).numpy()
    baseline={'fixed-'+n:worlds[:,:,j,0].numpy() for j,n in enumerate(experts)}
    for role,h in [('blind',-1),('scoreboard',0)]:
        baseline[role]=matches(load_models(budget,h,na,nf,root),worlds,ctx,rivals,tapes)['won']
    from series_eval import oracle
    train,train_ctx,_=loader('train',budget)
    if idx is not None:train=train[:,:,:,idx]
    if arms is not None:train=train[:,:,:,:,arms]
    _,default=oracle(train,train_ctx)
    p=ctx[...,1:].numpy()>=70;bins=p[...,0]*2+p[...,1]
    baseline['ping-lookup']=worlds[np.arange(len(rivals))[:,None],np.arange(ROUNDS)[None,:],default[bins],0].numpy()
    full_report=read(root/split/f'evaluation-{budget:02}.json')
    effects={};tallies={};coverage={};qualified={};past={}
    total_tests=2*(len(baseline)+2)+1
    def contrast(a,b):
        by=((series(a)==1).astype(float)-(series(b)==1)).reshape(nc,-1).mean(1)
        return dict(diff=float(by.mean()),bounds=lower_contrast(by,.05/total_tests),recordings=by.tolist())
    for h in (1,9):
        m=load_models(budget,h,na,nf,root)
        intact=matches(m,worlds,ctx,rivals,tapes)
        past[h]=intact['won']
        if tally(series(past[h]))!=full_report['scores'][f'memory-{h}']['series']:raise ValueError('Grading changed the original series')
        swaps={n:matches(m,worlds,ctx,rivals,tapes,'all-'+n,intact) for n in ('same','wrong')}
        test={n:contrast(past[h],v) for n,v in baseline.items()}
        test['intact minus wrong']=contrast(past[h],swaps['wrong']['won'])
        test['same minus wrong']=contrast(swaps['same']['won'],swaps['wrong']['won'])
        effects[str(h)]=test;qualified[str(h)]=all(v['bounds'][0]>0 for v in test.values())
        tally_all={**baseline,'wtap':wtap,'intact':past[h],**{k:v['won'] for k,v in swaps.items()}}
        tallies[str(h)]={k:dict(series=tally(series(v)),games=tally(v)) for k,v in tally_all.items()}
        coverage[str(h)]={n:val['covered'] for n,val in swaps.items()}
    write_json(root/split/f'whole-memory-{budget:02}.json',dict(qualified=qualified,effects=effects,all_scores=tallies,
        nine_minus_one=contrast(past[9],past[1]),coverage=coverage),fixed=True)


def run(task):
    root=FOLDER if task=='field' else FOLDER/task
    for b in BUDGETS if task=='field' else BUDGETS[:1]:
        while not (root/'dev'/f'evaluation-{b:02}.json').exists():time.sleep(10)
        evaluate(task,b)


if __name__=='__main__':
    if sys.argv[1:]==['register']:register()
    else:run(sys.argv[1])
