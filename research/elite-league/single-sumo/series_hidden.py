"""Remove the scoreboard, then replace prior public feedback with a coherent different opponent's history."""
import sys
import time
import gzip
import hashlib

import numpy as np
import torch

from assay import lower_contrast
from learner import CLOCKS,ROOT,write_json
from remote_league import read
from series_bank import CACHE,DEV_REPS,FOLDER,ROUNDS,tape
from series_eval import load_models,matches,oracle,series,tally
from series_learn import SEEDS,bank,features,fit


def register():
    write_json(FOLDER/'hidden-score-test.json',dict(reason='In the first two-opponent exercise, preserving exact scoreboard points left a wrong-opponent donor for only 19 of 576 post-opening decisions in the nine-game arm. Test what the network learns when it must infer the opponent from previous public game feedback without also receiving the cumulative scoreboard.',
        training='Retrain one- and nine-game GRUs with the score feature blank. Keep opponent distribution, game fixtures, PPO updates, network sizes and ring-out reward identical to the ring-out-trained blind comparator. No direct opponent ID. Run both the full 27-opponent league and the two historical opponent exercise.',
        intervention='Freeze the policy. Pair each test series with a different timing recording that has exactly the same ten public ping pairs and seats. Choose previous observed feedback either from that same opponent or from one consistently different opponent. The same random schedule supplies both donors. In game two the observer had used the identical first expert, so only the prior game result and displayed duration can differ. Every arm plays the original recipient fight at that decision.',
        grade='Report ring-outs and series separately, including game two alone, all later games, the blind comparator and W-tap. Estimate paired differences across timing recordings and adjust for both memory horizons, both metrics and the planned history comparisons. The development result must be distinguished from prospective confirmation.'),fixed=True)


def donor_rows(c,nf):
    nc,nr=c.shape[:2];schedules=c.reshape(nc*nr,ROUNDS,3).numpy()
    rng=np.random.default_rng(783512);same=[];wrong=[]
    for i,timing in enumerate(schedules):
        pool=np.flatnonzero((schedules==timing).all(axis=(1,2))&(np.arange(len(schedules))//nr!=i//nr))
        if not len(pool):raise ValueError('No independent timing recording with the same public schedule')
        other=int(rng.choice(pool))*nf
        perm=rng.permutation(nf);mismatch=np.empty(nf,int);mismatch[perm]=np.roll(perm,1)
        same.extend(other+np.arange(nf));wrong.extend(other+mismatch)
    return np.array(same),np.array(wrong)


def field_confirmation():
    src=FOLDER/'hidden-score';cache=CACHE/'hidden-score'
    clocks=read(src/'prospective/calendars/manifest.json')['selected']
    def fingerprint(clock):
        path=ROOT/clock
        content=gzip.decompress(path.read_bytes()) if path.suffix=='.gz' else path.read_bytes()
        return hashlib.sha256(content).hexdigest()
    fresh=set(map(fingerprint,clocks))
    old=set(map(fingerprint,(*CLOCKS['train'],*CLOCKS['dev'])))
    if len(fresh)!=24 or fresh&old:raise ValueError('Future clocks duplicate each other or an exposed recording')
    spec=read(FOLDER/'protocol.json')
    for r in range(DEV_REPS):
        for i,clock in enumerate(clocks):tape(spec,'fresh',r,i,clock,cache)
    write_json(src/'fresh'/f'bank-{DEV_REPS:02}.json',dict(recordings=len(clocks),reps=DEV_REPS,
        exposed_overlap=len(fresh&old),experts=list(spec['experts']),expert_fights=len(clocks)*DEV_REPS*ROUNDS*len(spec['experts'])*len(spec['field'])),fixed=True)
    return lambda split,reps:bank(split,reps,src,cache) if split=='fresh' else bank(split,reps)


@torch.no_grad()
def evaluate(task,split='dev',loader=None):
    src=FOLDER if task=='field' else FOLDER/'archive-reversal'
    root=src/'hidden-score'
    if loader is None:
        if task=='field':loader=bank
        else:
            from series_archive import load as loader
    data,c,wt=loader(split,DEV_REPS);nc,nr,_,nf,na,_=data.shape
    world=data.permute(0,1,3,2,4,5).reshape(-1,ROUNDS,na,2)
    ctx=c[:,:,None].expand(nc,nr,nf,ROUNDS,3).reshape(-1,ROUNDS,3)
    foes=np.tile(np.arange(nf),nc*nr);tapes=np.repeat(np.arange(nc),nr*nf)
    same,wrong=donor_rows(c,nf);idx=np.arange(len(foes));time_index=np.arange(ROUNDS)[None,:]
    baseline=load_models(2,-1,na,nf,src/'ringout-reward')
    control=matches(baseline,world,ctx,foes,tapes)['won']
    results={};zero=torch.zeros(len(idx));wts=wt.permute(0,1,3,2).reshape(-1,ROUNDS).numpy()
    for h in (1,9):
        nets=load_models(2,h,na,nf,root)
        if any(m.scoreboard for m in nets):raise ValueError('Scoreboard was visible in the diagnostic')
        actual=matches(nets,world,ctx,foes,tapes)
        scores={'intact':actual['won'],'blind':control,'wtap':wts}
        for name,take in [('same',same),('wrong',wrong)]:
            controls=[];selected=[]
            for g in range(ROUNDS):
                controls.append(features(zero,g,ctx[:,g,1:]))
                score=torch.stack([m(actual['history'][take,:g],torch.stack(controls,1))[0][:,-1] for m in nets]).mean(0)
                selected.append(score.argmax(1).numpy())
            chosen=np.stack(selected,1)
            if not np.array_equal(chosen,actual['choices'][take]):raise ValueError('Donor replay did not reproduce its actions')
            if not np.array_equal(chosen[:,0],actual['choices'][:,0]):raise ValueError('First expert changed before any feedback')
            scores[name]=world[idx[:,None],time_index,chosen,0].numpy()
        effects={}
        for a,b in [('intact','blind'),('intact','wrong'),('same','wrong')]:
            d=(scores[a]==1).astype(float)-(scores[b]==1)
            ve={'all_games':d,'game_two':d[:,1],'later_games':d[:,1:],
                'series':(series(scores[a])==1).astype(float)-(series(scores[b])==1)}
            effects[a+' minus '+b]={}
            for k,v in ve.items():
                clocks=v.reshape(nc,-1).mean(1)
                effects[a+' minus '+b][k]=dict(diff=float(clocks.mean()),bounds=lower_contrast(clocks,.05/24))
        results[str(h)]=dict(scores={n:dict(series=tally(series(v)),games=tally(v),by_game=[tally(col) for col in v.T]) for n,v in scores.items()},
                             effects=effects,eligible_decisions=int(len(idx)*(ROUNDS-1)))
    write_json(root/split/'result.json',dict(scope=task,series_per_arm=len(idx),results=results),fixed=True)


@torch.no_grad()
def public_controls(task,split,loader):
    src=FOLDER if task=='field' else FOLDER/'archive-reversal'
    root=src/'hidden-score';spec=read(src/'protocol.json')
    data,c,wt=loader(split,DEV_REPS);nc,nr,_,nf,na,_=data.shape
    world=data.permute(0,1,3,2,4,5).reshape(-1,ROUNDS,na,2).clone()
    world[...,1]=100*torch.floor((world[...,1]+50)/100)
    ctx=c[:,:,None].expand(nc,nr,nf,ROUNDS,3).reshape(-1,ROUNDS,3)
    rivals=np.tile(np.arange(nf),nc*nr);tapes=np.repeat(np.arange(nc),nr*nf)
    starts,per_ping=oracle(*loader('train',2)[:2])
    ping=ctx[...,1:].numpy()>=70;bins=2*ping[...,0]+ping[...,1]
    i=np.arange(len(rivals))[:,None];g=np.arange(ROUNDS)[None,:]
    scores={'train-id':world[i,g,starts[bins,rivals[:,None]],0].numpy(),'ping-only':world[i,g,per_ping[bins],0].numpy(),
        'wtap':wt.permute(0,1,3,2).reshape(-1,ROUNDS).numpy()}
    for j,name in enumerate(spec['experts']):scores['fixed-'+name]=world[:,:,j,0].numpy()
    same,wrong=donor_rows(c,nf)
    for label,group,h in [('hidden',root,9),('last-game',root,1),('blind',src/'ringout-reward',-1),('scoreboard',src/'ringout-reward',0)]:
        batch=matches(load_models(2,h,na,nf,group),world,ctx,rivals,tapes)
        scores[label]=batch['won']
        if label=='hidden':
            for name,donor in [('same',same),('wrong',wrong)]:
                scores[name]=world[i,g,batch['choices'][donor],0].numpy()
    effects={}
    for a,b in [('same','wrong'),('hidden','wrong'),('hidden','blind'),('hidden','scoreboard'),('hidden','ping-only')]:
        for u,sc in [('games',scores),('series',{n:series(v) for n,v in scores.items()})]:
            d=((sc[a]==1).astype(float)-(sc[b]==1)).reshape(nc,-1).mean(1)
            effects[f'{a} minus {b} {u}']=dict(mean=float(d.mean()),adjusted_bounds=lower_contrast(d,.05/(2*10)))
    recap={}
    for name,w in scores.items():
        recap[name]=dict(series=tally(series(w)),games=tally(w),opponents={n:tally(w[rivals==j]) for j,n in enumerate(spec['field'])},
            seat={s:tally(w[ctx[...,0].numpy()==j]) for j,s in enumerate('ab')},
            rtt={f'{a}/{b}':tally(w[(ctx[...,1].numpy()==a)&(ctx[...,2].numpy()==b)]) for a in (31,109) for b in (31,109)})
    write_json(root/split/'public-controls.json',dict(scores=recap,effects=effects,series_per_arm=len(rivals),
        scoring='Every policy receives times rounded to the tenth of a second visible in native chat.'),fixed=True)


def run(task,split):
    src=FOLDER if task=='field' else FOLDER/'archive-reversal'
    root=src/'hidden-score'
    if task=='field':loader=bank
    else:
        from series_archive import load as loader
    while not (src/'ringout-reward/dev/reward-comparison.json').is_file():time.sleep(10)
    train,cues,_=loader('train',2)
    for h in (1,9):
        seed=SEEDS[0];fit(train,cues,2,h,seed,folder=root/'learn-02'/f'h{h}-s{seed}',objective='ringouts',scoreboard=False)
    if task=='field' and split=='fresh':loader=field_confirmation()
    else:
        while not (src/split/f'bank-{DEV_REPS:02}.json').is_file():time.sleep(10)
    if not (root/split/'result.json').exists():evaluate(task,split,loader)
    public_controls(task,split,loader)


if __name__=='__main__':
    if sys.argv[1:]==['register']:register()
    elif sys.argv[1:]==['reserve-field']:
        from remote_import import send_future_clocks,wait_for
        from remote_league import OUT
        src=FOLDER/'hidden-score'
        send_future_clocks(src/'prospective',dict(
            study='Freeze the already evaluated single-seed, no-scoreboard nine-game model and equal-budget controls before collecting 24 independent real timing records. Play 27 stationary opponents. Outcomes on these records have not informed policy changes.',
            primary='At publicly displayed time precision compare same-opponent donor with wrong-opponent donor, and genuine history with wrong history. Separately compare ordinary series and ring-outs to the scoreboard, blind and ping-only policies. Correct all ten comparisons together; distinguish recognition from a gain over the strongest practical control.',
            actor=read(src/'learn-02/h9-s4101/trained.json')['sha256']))
        prefix=str(src.relative_to(OUT))+'/'
        wait_for([prefix+'fresh/bank-04.json',prefix+'fresh/result.json',prefix+'fresh/public-controls.json'])
    else:run(sys.argv[1],sys.argv[2] if len(sys.argv)>2 else 'dev')
