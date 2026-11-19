"""Retune the eight hidden features after a fixed-readout fit could not express the requested keys."""
import argparse
import gzip
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

import champions
import focus
from adaptation import BLOCK, GATE, dump, freeze, sha
from history_cup import mirrors
from mechanism import encode, unpack
from mmc_click_bias import PARENT, ranking
from mmc_edge_fit import BASE, CONTACT, SPLIT as FIRST_FIT
from mmc_league import EVIDENCE, FROZEN, HERE, WORK, contest, setup
from mmc_trade_fit import ACTOR, PREVIOUS, pool
from run import calendars

STAGE='mmc13-hidden-strafe'
SPLIT=FROZEN/STAGE
SETTINGS=[(seed,mass) for seed in ('parent','center-unit') for mass in (.125,.5,2.,8.)]


def register():
    setup();SPLIT.mkdir(exist_ok=True)
    files=sorted(FIRST_FIT.glob('examples-*.jsonl.gz'));p=json.loads((FIRST_FIT/'protocol.json').read_text())
    dump(SPLIT/'protocol.json',dict(parent=sha(CONTACT/(BASE+'.net')),capacity=sha(EVIDENCE/'edge-ablation/readout-capacity.json'),
        fit=p['training'],selection=p['selection'],development=p['development'],sources={f.name:sha(f) for f in files},
        architecture='Two eight-unit softsign MLP copies with the unchanged fitted click selector. Refit the 22 input weights and bias of each hidden unit and all five output readouts. Expert two keeps its original forced-click readout. Both copies see the actually applied keys.',
        objective='Use only the twelve fit clocks. Preserve the parent discrete forward, sprint, click, jump and strafe boundaries on the stock anchors and on non-improving branch heads. Match center strafe on improving six-head branches while preserving the other four channels there. Use squared hinge loss with a 0.08 margin limited by the original action margin on preserved actions. Count each group separately. Penalize distance from the frozen weights. Try original initialization and one with the least disruptive hidden unit reoriented onto the local center direction.',
        settings=SETTINGS,solver='Full-batch CPU L-BFGS, 130 iterations per fit, regularization 0.0001 times the sum of squared parameter changes. Equal weighting across the two radii for targeting.',
        tournaments='Rank the eight networks with the parent, previous trade winner and plain W-tap on the four separate filter donors against W-tap, the previous and current PPO, and continuation-02. Advance the highest-ranked fitted contender to the broad exposed development field. Retain every actual winner as a playable bot. Apply the existing W-tap win and worst-opponent ranking.',
        confirmation='Use the eight previously reserved edge-confirm timing donors for validation only. Independent native matches on the measured map, both seats and RTTs, two repeats. Compare plain W-tap, contact parent, previous trade winner and selected network against W-tap, earlier PPO, and the selected network’s weakest distinct development opponent. Keep other frozen tournaments and the common final independent.'))


def dataset():
    anchors={};requests=[]
    spec=json.loads((SPLIT/'protocol.json').read_text());fit={p.split('.')[0] for p in spec['fit']}
    for filename,digest in spec['sources'].items():
        source=FIRST_FIT/filename
        if sha(source)!=digest:raise ValueError('Changed training source')
        with gzip.open(source,'rt') as stream:
            for r in map(json.loads,stream):
                if r['arm']!=BASE or r['clock'] not in fit:continue
                for s in r['anchors']:anchors[tuple(s[:-1])]=int(s[-1])
                if not r.get('branches'):continue
                improves=r['branches']['center-strafe-6'][0]>r['branches']['unchanged'][0]
                for s in r['examples']['center-strafe-6' if improves else 'unchanged']:
                    if improves:requests.append((s[:-1],int(s[-1]),r['radius']))
                    else:anchors[tuple(s[:-1])]=int(s[-1])
    return list(anchors.keys())+[x for x,_,_ in requests],list(anchors.values())+[y for _,y,_ in requests],[r for _,_,r in requests]


def fit():
    for key in ('OPENBLAS_NUM_THREADS','OMP_NUM_THREADS','VECLIB_MAXIMUM_THREADS','MKL_NUM_THREADS'):os.environ[key]='1'
    import numpy as np
    from scipy.optimize import minimize
    register();xs,labels,radii=dataset()
    features=np.column_stack((np.ones(len(xs)),np.asarray(xs)))
    _,old=unpack(pool()[ACTOR].read_text());old=np.asarray(old);nstock=len(xs)-len(radii)
    labels=np.asarray(labels)
    def evaluate(w):
        v=features@w[:184].reshape(8,23).T
        h=v/(1+np.abs(v));hout=np.column_stack((np.ones(len(v)),h))
        return v,hout,hout@w[184:].reshape(5,9).T
    _,_,orig=evaluate(old)
    def side(logits):return (logits>.45).astype(int)-(logits<-.45).astype(int)
    if not np.array_equal(side(orig[:nstock,4]),labels[:nstock]):raise ValueError('Features did not reproduce stock strafe')
    lower=np.full_like(orig,-np.inf);upper=np.full_like(orig,np.inf)
    for j,cuts in enumerate(([-.65,0],[0],[0],[0],[-.45,.45])):
        for c in cuts:
            over=orig[:,j]>c
            lower[over,j]=np.maximum(lower[over,j],np.minimum(c+.08,orig[over,j]))
            upper[~over,j]=np.minimum(upper[~over,j],np.maximum(c-.08,orig[~over,j]))
    positive=slice(nstock,None);goal=labels[positive]
    lower[positive,4]=np.select([goal>0,goal==0],[.53,-.37],default=-np.inf)
    upper[positive,4]=np.select([goal<0,goal==0],[-.53,.37],default=np.inf)
    _,hout,_=evaluate(old);outw=old[184:].reshape(5,9)
    def action(u):
        return np.column_stack((np.where(u[:,0]<-.65,3,np.where(u[:,0]>0,1+(u[:,1]>0),0)),
                u[:,2]>0,(u[:,3]>0)&(features[:,9]>0),side(u[:,4])))
    parent=action(orig)
    removed=[int(np.any(action(orig-hout[:,i+1:i+2]*outw[:,i+1])[:nstock]!=parent[:nstock],axis=1).sum()) for i in range(8)]
    least=int(np.argmin(removed));candidate={}
    p=8+GATE
    family,paired=unpack((CONTACT/(BASE+'.net')).read_text())
    if family!='PAIR' or list(old)!=paired[p:p+229]:raise ValueError('Different contact expert')
    for index,(seed,mass) in enumerate(SETTINGS):
        weights=np.full(orig.shape,1/nstock);weights[positive,:]=1/len(goal)
        for radius in set(radii):
            mask=np.asarray(radii)==radius
            weights[nstock+np.flatnonzero(mask),4]=mass/(len(set(radii))*mask.sum())
        def objective(w):
            v,h,u=evaluate(w);diff=u-np.clip(u,lower,upper)
            delta=w-old;loss=(diff*diff*weights).sum()+.0001*np.dot(delta,delta)
            gu=2*diff*weights
            gw_out=gu.T@h
            gv=(gu@w[184:].reshape(5,9)[:,1:])/np.square(1+np.abs(v))
            grad=np.concatenate(((gv.T@features).ravel(),gw_out.ravel()))+.0002*delta
            return loss,grad
        init=old.copy()
        if seed=='center-unit':
            wi=init[:184].reshape(8,23);wi[least]=0;wi[least,14]=3
            wo=init[184:].reshape(5,9);wo[:,least+1]=0;wo[4,least+1]=.75
        if index==0:
            # Check the analytic derivative away from the parent, including all five output thresholds.
            direction=np.random.default_rng(2810).normal(size=len(init));q=init+.002*direction
            observed=(objective(q+1e-6*direction)[0]-objective(q-1e-6*direction)[0])/2e-6
            if abs(observed-objective(q)[1]@direction)>1e-5:raise ValueError('Wrong softsign distillation gradient')
        fit=minimize(objective,init,jac=True,method='L-BFGS-B',options={'maxiter':130,'ftol':1e-11,'maxls':35})
        if not np.isfinite(fit.fun) or fit.status not in (0,1):raise ValueError(fit.message)
        exact=fit.x.astype(np.float32).astype(float);out=paired.copy()
        for off in (p,p+BLOCK):out[off:off+229]=exact.tolist()
        out[p+BLOCK+202:p+BLOCK+211]=[1.]+[0.]*8
        name=f'mmc13-hidden-{index:02d}'
        freeze(SPLIT/(name+'.net'),(encode('PAIR',out)+'\n').encode())
        act=action(evaluate(exact)[2])
        candidate[name]=dict(settings=[seed,mass],stock_changed_by_channel=np.sum(act[:nstock]!=parent[:nstock],axis=0).tolist(),
            stock_changed_any=int(np.any(act[:nstock]!=parent[:nstock],axis=1).sum()),
            desired_strafe_matched=int(np.sum(act[positive,3]==goal)),other_changed_on_targets=np.sum(act[positive,:3]!=parent[positive,:3],axis=0).tolist(),
            objective=float(fit.fun),iterations=int(fit.nit))
    dump(SPLIT/'fit.json',dict(stock=nstock,desired=len(goal),parent_matches=int((parent[positive,3]==goal).sum()),
        changed_if_unit_removed=removed,center_unit=least,candidates=candidate))


def tournament():
    register()
    if not (SPLIT/'fit.json').exists():
        python=shutil.which('python3.11')
        if not python:raise RuntimeError('Fitting requires the installed NumPy/SciPy Python')
        subprocess.run([sys.executable,str(HERE/'cpu_limit.py'),'nice','-n','8',python,__file__,'fit'],check=True)
    field=pool()|{BASE:CONTACT/(BASE+'.net'),PARENT:FROZEN/'mmc13-trades'/(PARENT+'.net')}
    controls={n:field[n] for n in ('wtap',BASE,PARENT)}
    options={n:SPLIT/(n+'.net') for n in json.loads((SPLIT/'fit.json').read_text())['candidates']}
    rivals={n:field[n] for n in ('wtap',PREVIOUS,ACTOR,'continuation-02-fit')}
    allarms=controls|options;train=calendars('training_clocks')[12:];out=SPLIT/'filter.jsonl.gz'
    focus.play_model(out,allarms,rivals,train);ranks=ranking(out,allarms,rivals,len(train))
    winner=ranks[0]['name'];contender=next(r['name'] for r in ranks if r['name'] in options)
    champions.install(STAGE+'-filter',focus.source(winner,allarms[winner]),f'Hidden-layer selection winner ({winner})',STAGE+'-filter')
    champions.install(contender,options[contender],f'Hidden-layer strafe finalist ({contender})',STAGE,'adaptive')
    arms=controls|{contender:options[contender]};out=SPLIT/'development.jsonl.gz';dev=calendars('development_selection_clocks')
    focus.play_model(out,arms,field,dev);rank=ranking(out,arms,field,len(dev));winner=rank[0]['name']
    champions.install(STAGE,focus.source(winner,arms[winner]),f'Hidden-layer strafe tournament winner ({winner})',STAGE)
    weak=next(o for o in next(r for r in rank if r['name']==contender)['weakest'] if focus.identity(o,field[o])!=sha(arms[contender]))
    dump(SPLIT/'selection.json',dict(winner=winner,contender=contender,weakest_distinct=weak,filter=ranks,development=rank))
    foes={n:field[n] for n in dict.fromkeys(['wtap',PREVIOUS,weak])};reps={n:2 for n in foes}
    dump(SPLIT/'confirmation.json',dict(arms={n:focus.identity(n,p) for n,p in arms.items()},opponents={n:focus.identity(n,p) for n,p in foes.items()},repeats=reps))
    print(winner,contender,weak,flush=True)
    contest('hidden-confirm',arms,foes,reps,mirrors('mmc13-edge-confirm',WORK))


if __name__=='__main__':
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('mode',choices=['register','fit','tournament'])
    {'register':register,'fit':fit,'tournament':tournament}[ap.parse_args().mode]()
