"""Bound what a new affine strafe readout can reproduce without changing sampled parent actions."""
import gzip
import json
import os
import sys

# The LP is small in parameter count. Keep the solver and vector library single-threaded.
for key in ('OPENBLAS_NUM_THREADS','OMP_NUM_THREADS','VECLIB_MAXIMUM_THREADS','MKL_NUM_THREADS'):
    os.environ[key]='1'

import numpy as np
from scipy.optimize import linprog

from adaptation import dump, sha
from mmc_edge_fit import ACTOR, BASE, SPLIT, inputs, pool, raw_score, unpack
from mmc_league import EVIDENCE

DEST=EVIDENCE/'edge-ablation'
TOL=1e-7


def label(v):return int(v>.45)-int(v<-.45)


def run():
    p=json.loads((SPLIT/'protocol.json').read_text());fit={name.split('.')[0] for name in p['training']}
    _,actor=unpack(pool()[ACTOR].read_text());original=np.asarray(actor[-9:])
    paths=sorted(SPLIT.glob('examples-*.jsonl.gz'))
    expanded=sys.argv[1:]==['--geometry']
    if sys.argv[1:] and not expanded:raise ValueError('Expected --geometry or no option')
    size=14 if expanded else 9
    proto='geometry-capacity-protocol.json' if expanded else 'capacity-protocol.json'
    extra={}
    def representation(v):
        h=inputs(v,actor)
        if expanded:
            # All values are available locally. The two clipped projections approximate radial speed.
            left=v[13];velocity=-(v[12]*v[2]+left*v[3])
            outward=float(velocity>0 and v[14]>=.4)
            h.extend((left,v[14],velocity,left*outward,((left>0)-(left<0))*outward))
        return np.asarray(h)
    if expanded:
        original=np.pad(original,(0,5))
        extra['extension']='Keep all nine hidden readout coordinates and append local center projection on left, radius feature, approximate outward velocity, and two center-side terms enabled above radius feature 0.4 with outward movement. The 14-variable question tests a representational upper bound. These augmented weights are not a deployed player.'
    dump(DEST/proto,dict(**extra,sources={p.name:sha(p) for p in paths},fit_donors=sorted(fit),
        question=('Can any fourteen real-valued affine' if expanded else 'Can any nine real-valued affine')+' strafe weights in the frozen MLP produce each desired changed key while preserving the parent classification on all recorded anchor inputs? Anchor inequalities include both thresholds, a relaxation of the actual discrete rule. Use the range of achievable scores for each changed example independently. This is a diagnostic bound on the observed fitting set, without a norm bound, held-out scoring, or controller deployment.',
        anchors='Sampled stock input heads and unchanged window heads from trials where a six-tick center intervention did not improve the outcome. Remove identical feature/label pairs. Target examples come only from strictly improving branches at both radii.'))
    anchors={};desired=[]
    for path in paths:
        with gzip.open(path,'rt') as f:
            for r in map(json.loads,f):
                if r['arm']!=BASE or r['clock'] not in fit:continue
                for sample in r['anchors']:
                    anchors[tuple(sample[:-1])]=int(sample[-1])
                if not r.get('branches'):continue
                improves=r['branches']['center-strafe-6'][0]>r['branches']['unchanged'][0]
                name='center-strafe-6' if improves else 'unchanged'
                for i,sample in enumerate(r['examples'][name]):
                    if improves:
                        desired.append((tuple(sample[:-1]),int(sample[-1]),[r[k] for k in ('radius','clock','rtt','side','opponent')]+[i]))
                    else:anchors[tuple(sample[:-1])]=int(sample[-1])
    A=[];b=[]
    for v,k in anchors.items():
        h=representation(v)
        if label(raw_score(h,original))!=k:raise ValueError('Anchor differs from frozen output')
        if k<=0:A.append(h);b.append(-.45 if k==-1 else .45)
        if k>=0:A.append(-h);b.append(-.45 if k==1 else .45)
    A=np.asarray(A);b=np.asarray(b)
    if np.max(A@original-b)>1e-11:raise ValueError('Original is outside relaxed anchor constraints')
    def low(c):
        res=linprog(c,A_ub=A,b_ub=b,bounds=[(None,None)]*size,method='highs-ds',
                    options={'threads':1,'primal_feasibility_tolerance':1e-9,'dual_feasibility_tolerance':1e-9})
        if res.status==3:return None
        if res.status!=0:raise RuntimeError(res.message)
        if np.max(A@res.x-b)>TOL:raise ValueError('Unreliable solver certificate')
        return res.fun
    results=[];unchanged=0;cache={}
    for v,k,idx in desired:
        h=representation(v);old=label(float(h@original))
        if k==old:unchanged+=1;continue
        if v not in cache:
            lo=low(h);neg=low(-h);cache[v]=(lo,-neg if neg is not None else None)
        lo,hi=cache[v]
        impossible=((k==1 and hi is not None and hi<.45-TOL) or (k==-1 and lo is not None and lo>-.45+TOL)
                    or (k==0 and ((hi is not None and hi<-.45-TOL) or (lo is not None and lo>.45+TOL))))
        results.append(dict(index=idx,parent=old,desired=k,lower=lo,upper=hi,excluded=bool(impossible)))
    dump(DEST/('geometry-capacity.json' if expanded else 'readout-capacity.json'),dict(protocol=sha(DEST/proto),distinct_anchor_states=len(anchors),
        inequalities=len(A),improving_branch_heads=len(desired),already_matches=unchanged,different=len(results),
        individually_excluded=sum(row['excluded'] for row in results),queries=results))
    print(len(anchors),len(desired),unchanged,len(results),sum(r['excluded'] for r in results),flush=True)


if __name__=='__main__':run()
