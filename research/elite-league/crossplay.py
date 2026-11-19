"""Find compositional weaknesses on the archived, balanced square development tournament."""
import argparse
from collections import Counter,defaultdict
import gzip
import itertools
import json
from pathlib import Path

def matrix(rows):
    cell=defaultdict(list)
    for r in rows:cell[r['arm'],r['opponent']].append(r['score'])
    return {key:sum(v)/len(v) for key,v in cell.items()}

def value(m,policy,rivals):
    rates=[sum(weight*m[arm,opp] for arm,weight in policy) for opp in rivals]
    return min(rates),sum(rates)/len(rates)

def choose(m,candidates,opps):
    scores={a:[m[a,o] for o in opps] for a in candidates}
    singles=sorted(((-min(v),-sum(v)/len(v),a) for a,v in scores.items()))
    best=(-singles[0][0],-singles[0][1]);policy=[(singles[0][2],1.)]
    for support in (2,3):
        for names in itertools.combinations(candidates,support):
            for cuts in itertools.combinations(range(1,10),support-1):
                edges=(0,*cuts,10);w=[(b-a)/10 for a,b in zip(edges,edges[1:])]
                v=[sum(w[i]*scores[n][k] for i,n in enumerate(names)) for k in range(len(opps))]
                objective=(min(v),sum(v)/len(v))
                if objective>best:best=objective;policy=list(zip(names,w))
    return singles[0][2],policy

def study(path,out):
    with gzip.open(path,'rt') as f:rows=[json.loads(l) for l in f]
    all_names=sorted({r['arm'] for r in rows});donors=sorted({r['clock'] for r in rows})
    m=matrix(rows)
    if any(abs(m[a,b]+m[b,a]-1)>1e-12 for a in all_names for b in all_names):raise ValueError('Seat-balanced payoff matrix is not complementary')
    eligible=[n for n in all_names if n in ('wtap','hold','ppo','reference','control-trained','human-trained','v1-hybrid','anti-hold','anti-hybrid') or n.startswith(('s01-','s04-','s08-','s12-','trade-r'))]
    triangles=[]
    for a,b,c in itertools.combinations(eligible,3):
        for u,v,w in ((a,b,c),(a,c,b)):
            edges=[m[u,v],m[v,w],m[w,u]]
            if min(edges)>.5:triangles.append((min(edges),[u,v,w],edges))
    triangles.sort(reverse=True)
    partitions=[]
    # A split of previously exposed donors is only an internal stability diagnostic.
    for train,val in ((donors[::2],donors[1::2]),(donors[1::2],donors[::2])):
        design=matrix([r for r in rows if r['clock'] in train]);test=matrix([r for r in rows if r['clock'] in val])
        singleton,mix=choose(design,eligible,all_names)
        partitions.append(dict(fit=train,score=val,singleton=singleton,mixture=mix,
                               wtap_score=value(test,[('wtap',1.)],all_names),
                               single_score=value(test,[(singleton,1.)],all_names),mixed_score=value(test,mix,all_names)))
    out.write_text(json.dumps(dict(entrants=len(all_names),strongest_cycles=triangles[:12],diagnostic=partitions),indent=2)+'\n')

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('matrix',type=Path);p.add_argument('out',type=Path)
    a=p.parse_args();study(a.matrix,a.out)
