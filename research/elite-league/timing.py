"""Fit transport residuals and check the frozen assumptions using two-sided native actions."""
import argparse
import importlib.util
import itertools
import json
from pathlib import Path
import sys
from statistics import mean
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[1];S=ROOT/'research/sumo-rl'
sys.path.insert(0,str(S))
from input_replay import compare
from run import compile_model

def check(mid,pairs):
    g=json.loads((ROOT/f'runtime/results/{mid}.json').read_text())
    if g['mode']!='sumo':return dict(mid=mid,unsupported='Not a sumo opening')
    try:return compare(mid,pairs)
    except (ValueError,IndexError,StopIteration,KeyError,FileNotFoundError) as e:return dict(mid=mid,unsupported=str(e))

def fit():
    mids=json.loads((HERE/'protocol.json').read_text())['training_clocks']
    values=(0,.35,1,2,4,8);pairs=list(itertools.product(values,repeat=2))
    rows={m:check(m,pairs) for m in mids};ok=[x for x in rows.values() if isinstance(x,list)]
    if not ok:raise ValueError('No usable calibration games')
    rank=[]
    for i,(up,down) in enumerate(pairs):
        rank.append(dict(up=up,down=down,exact=sum(x[i]['exact'] for x in ok),total=len(ok),
            mean_max=mean(x[i]['max_position_error'] for x in ok)))
    rank.sort(key=lambda r:(-r['exact'],r['mean_max'],abs(r['up']-.35)+abs(r['down']-.35),r['up'],r['down']))
    return dict(winner=rank[0],ranking=rank,rows={k: v if isinstance(v,dict) else [
        [x['exact'],x['max_position_error']] for x in v] for k,v in rows.items()})

def validate(frozen,manifest):
    win=json.loads(frozen.read_text())['winner'];pairs=[(.35,.35),(win['up'],win['down'])]
    m=json.loads(manifest.read_text());groups={k:[] for k in ('research-wtap20','elite-v1-linear','elite-v1-mlp','elite-v1-recurrent','elite-v1-hybrid')}
    for req,mid in sorted(m['matches'].items(),key=lambda x:int(x[1][1:])):
        p=m['plan'][req];n=p['arm']
        if int(mid[1:])<2750 or n not in groups or len(groups[n])>=3 or (p['mode'],p['profile'])!=('sumo','mmc'):continue
        g=json.loads((ROOT/f'runtime/results/{mid}.json').read_text())
        if all(g['parameters_'+s].get('science_trace')=='1' for s in 'ab'):groups[n].append(mid)
    if m['matches'].keys()!=m['plan'].keys():raise ValueError('Wait for the complete transfer cohort before closing coverage')
    return dict(groups=groups,pairs=pairs,rows={mid:check(mid,pairs) for ids in groups.values() for mid in ids})

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('out',type=Path);p.add_argument('--frozen',type=Path);p.add_argument('--manifest',type=Path);a=p.parse_args();compile_model()
    d=validate(a.frozen,a.manifest) if a.frozen is not None else fit()
    with a.out.open('x') as f:json.dump(d,f,indent=2)
    print(d.get('winner',d.get('groups')))
