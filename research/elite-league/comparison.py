"""Pair each simulated or native result with its W-tap control and keep donor and seat spread."""
import argparse
from collections import defaultdict
import gzip
import json
from pathlib import Path

def records(path):
    with gzip.open(path,'rt') as f:
        if path.name=='native.json.gz':
            d=json.load(f)
            return [dict(r,score=1 if r['win'] else .5 if r['draw'] else 0) for r in d['matches']]
        return [json.loads(l) for l in f]

def cell(r):
    return (r.get('profile','mmc'),r['opponent'],r.get('diameter',10),
            'asymmetric' if r['rtt']!=r.get('opponent_rtt',r['rtt']) else 'symmetric')

def matches(r):
    return (r.get('clock',r.get('rep')),r['rtt'],r.get('opponent_rtt',r['rtt']),r['side'])

def outcome(xs):
    if not xs:return dict(games=0)
    pts=sum(xs)
    return dict(games=len(xs),wins=xs.count(1),losses=xs.count(0),draws=xs.count(.5),points=pts,rate=pts/len(xs))

def summarize(rows):
    by=defaultdict(lambda:defaultdict(dict))
    for r in rows:
        if r.get('mode','sumo')!='sumo':continue
        vs=by[cell(r)][r['arm']];k=matches(r)
        if k in vs:raise ValueError(f'Duplicate schedule {r["arm"]} {k}')
        vs[k]=r.get('score')
    result={}
    for c,arms in by.items():
        base=next((arms[n] for n in ('wtap','research-wtap20') if n in arms),None)
        if base is None:raise ValueError(f'No W-tap control for {c}')
        group=result[' '.join(map(str,c))]={}
        for n,rs in arms.items():
            both=[(k,x,base.get(k)) for k,x in rs.items() if x is not None and base.get(k) is not None]
            def pair(items):
                return dict(wtap=outcome([b for k,x,b in items]),challenger=outcome([x for k,x,b in items]),
                            difference_points=sum(x-b for k,x,b in items))
            sub=group[n]=dict(all=pair(both),unqualified=len(rs)-len(both))
            sub['rtt']={str(t):pair([r for r in both if r[0][1:3]==t]) for t in sorted({k[1:3] for k,x,b in both})}
            sub['seat']={s:pair([r for r in both if r[0][3]==s]) for s in ('a','b')}
            sub['clock_or_repeat']={str(d):pair([r for r in both if r[0][0]==d]) for d in sorted({k[0] for k,x,b in both})}
    return result

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('data',type=Path);p.add_argument('out',type=Path);a=p.parse_args()
    result=summarize(records(a.data))
    with a.out.open('xb') as f:f.write(gzip.compress(json.dumps(result,separators=(',',':')).encode(),mtime=0))
