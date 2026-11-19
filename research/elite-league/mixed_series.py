"""Preassign full-bout strategy changes independently of the combat trajectory and compare matched controls."""
import argparse
from collections import defaultdict
import gzip
import itertools
import json
from pathlib import Path
import random
from ladder_transfer import WT,HOLD,SELECTION
from run import HERE

def schedule():
    protocol=json.loads((HERE/'mixed-series-protocol.json').read_text())
    weights=protocol['weights'];ordered=list(weights);rows=[]
    for native in (True,False):
        rng=random.Random(118001 if native else 118019)
        for o,i,rtt,s in itertools.product(SELECTION,range(2 if native else 8),(0,80),'ab'):
            draw=rng.random();cumulative=0;component=ordered[-1]
            for name,prob in weights.items():
                cumulative+=prob
                if draw<cumulative:component=name;break
            rows.append(dict(native=native,opp=o,index=i,rtt=rtt,side=s,component=component))
    return rows
def score(assignment,native,sim):
    proto=json.loads((HERE/'mixed-series-protocol.json').read_text())
    with gzip.open(native,'rt') as f:actual=json.load(f)['matches']
    with gzip.open(sim,'rt') as f:modeled=[json.loads(l) for l in f]
    donors=sorted({x['clock'] for x in modeled},key=lambda x:int(x[1:]));assert len(donors)==8
    ids={'wtap':WT,'hold':HOLD}
    cells={(True,x['opponent'],x['rep'],x['rtt'],x['side'],x['arm']):(1 if x['win'] else .5 if x['draw'] else 0) for x in actual}
    cells.update({(False,ids.get(x['opponent'],x['opponent']),donors.index(x['clock']),x['rtt'],x['side'],ids.get(x['arm'],x['arm'])):x['score'] for x in modeled})
    hist=defaultdict(lambda:defaultdict(list))
    for item in assignment:
        k=tuple(item[s] for s in ('native','opp','index','rtt','side'))
        by=hist[('native' if item['native'] else 'model')+'/'+item['opp']]
        for a in proto['fixed_singletons']:by[a].append(cells[k+(a,)])
        by['assigned'].append(cells[k+(item['component'],)])
        by['expected'].append(sum(w*cells[k+(a,)] for a,w in proto['weights'].items()))
    return {k:{a:dict(n=len(xs),points=sum(xs)) for a,xs in by.items()} for k,by in hist.items()}
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('out',type=Path);p.add_argument('--assignment',type=Path);p.add_argument('--native',type=Path);p.add_argument('--sim',type=Path)
    a=p.parse_args();result=schedule() if not a.assignment else score(json.loads(a.assignment.read_text()),a.native,a.sim)
    with a.out.open('x') as f:json.dump(result,f,indent=2)
