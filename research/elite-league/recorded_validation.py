"""Freeze donor request IDs before native play and measure transfer on their timing alone."""
import gzip
import json
from pathlib import Path
import subprocess
import sys
import time
from collections import Counter,defaultdict

from adaptation import dump,freeze,sha
from mmc_league import native_name
from recorded_archive import field
from recorded_mmc_league import DEST,TEMP,evaluate
from run import ROOT,compile_model

def main():
    d=DEST/'clock-validation';d.mkdir(exist_ok=True)
    plan=json.loads((DEST/'v3-transfer-01/schedule.json').read_text())['plan']
    ids=[q for q,r in plan.items() if r['arm']==r['opponent']=='research-wtap20' and r['rep']<2]
    if len(ids)!=8:raise ValueError('Expected eight prospective clocks')
    dump(d/'donor-protocol.json',dict(requests=ids,usage='Fixed before these matches started. The first two repetitions per seat and RTT of the initial recorded-law W-tap mirror supply only event timing. Freeze the memory tournament before opening the score. All earlier selection donors remain development.'))
    while not (DEST/'v3-transfer-01/results.json').exists() or not (DEST/'memory/results.json').exists():time.sleep(10)
    done=json.loads((DEST/'v3-transfer-01/results.json').read_text())['accepted']
    path=TEMP/'donors.json'
    dump(path,dict(plan={q:plan[q] for q in ids},matches={q:done[q] for q in ids}))
    td=TEMP/'clocks'
    subprocess.run([sys.executable,str(ROOT/'research/sumo-rl/calendars.py'),str(td),'--frozen-manifest',str(path)],check=True)
    clocks=sorted(td.glob('*.txt.gz'))
    if len(clocks)!=8:raise ValueError('Incomplete donor set')
    for p in clocks:freeze(d/p.name,p.read_bytes())
    pool,students=field()
    memory=json.loads((DEST/'memory/results.json').read_text());arch=json.loads((DEST/'archive/ranks.json').read_text())[0]
    for n in [memory['winner'],*memory['tracks'].values()]:
        if n not in pool:pool[n]=DEST/'memory'/(n+'.net')
    arms={n:pool[n] for n in dict.fromkeys(['wtap','mmc13-actor-2-fit','mmc13-trade-2-fit',*students,memory['winner'],*memory['tracks'].values()])}
    foes={n:pool[n] for n in dict.fromkeys(['wtap','hold','mmc13-trade-2-fit','mmc13-actor-2-fit','adapt-01-pair',students[0],*[n for n in arch['weakest'] if n!=arch['name']][:2]])}
    dump(d/'weights.json',dict(arms={n:sha(p) if p else 'builtin '+n for n,p in arms.items()},foes=list(foes),donors=done and {q:done[q] for q in ids}))
    compile_model()
    raw=d/'scores.jsonl.gz';ranks=evaluate(raw,arms,foes,clocks)
    cells=defaultdict(lambda:defaultdict(lambda:defaultdict(Counter)))
    with gzip.open(raw,'rt') as f:
        for r in map(json.loads,f):
            cells[r['arm']][r['opponent']][r['side']+str(r['rtt'])][{1:'win',0:'loss',.5:'draw'}[r['score']]]+=1
    from recorded_mmc_league import publish
    publish('minemen-v3-clock-final',ranks[0]['name'],arms)
    dump(d/'results.json',dict(ranks=ranks,cells=cells))
    print('Calendar transfer',ranks[0]['name'],flush=True)

if __name__=='__main__':main()
