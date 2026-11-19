"""Check the one-input release on the other registered development donors."""
import gzip
import json
from collections import defaultdict
from pathlib import Path
import subprocess
import tempfile

from adaptation import dump, freeze, sha
from mmc_contact_gate import SPLIT
from mmc_league import EVIDENCE, WORK
from mmc_scorecard import record
from mmc_trade_fit import ACTOR, PREVIOUS, pool
from run import calendars, compile_model, java
from tournament import roster


def key(r):
    return r['opponent'],r['clock'],r['rtt'],r['side']


def run():
    dest=EVIDENCE/'contact-footwork';field=pool()
    arm=json.loads((SPLIT/'selection.json').read_text())['contender']
    foes={n:field[n] for n in ('wtap',ACTOR,PREVIOUS)}
    clocks=calendars('training_clocks')
    sets={'first12':clocks[:12],'last4':clocks[12:]}
    dump(dest/'coast-protocol.json',dict(source=sha(dest/'forks.jsonl.gz'),
        panels={k:[p.name for p in v] for k,v in sets.items()},
        plan='After the eight exposed calendars yielded opposite signs for the single input W release against the two PPO opponents, repeat the exact unmodified, primed W-tap and one-input coast branches on all sixteen original training clocks. These clocks were already used to fit or select the parent. Report both preexisting splits separately, with both seats, both RTTs, the W-tap control from spawn, all untriggered fixtures, and the signed change for each same-state pair. No new policy or title is selected.'))
    compile_model()
    with tempfile.TemporaryDirectory(dir=WORK) as folder:
        d=Path(folder);roster(d/'arms',{arm:SPLIT/(arm+'.net'),ACTOR:field[ACTOR]});roster(d/'foes',foes)
        with (d/'rows').open('w') as stream:
            subprocess.run(java('ContactTimingStudy',d/'arms',d/'foes','--single-coast',*clocks),stdout=stream,check=True)
        raw=(d/'rows').read_bytes()
    rows=[json.loads(l) for l in raw.splitlines()]
    old={}
    for name in ('fit','filter'):
        with gzip.open(SPLIT/(name+'.jsonl.gz'),'rt') as f:
            for r in map(json.loads,f):
                if r['arm'] not in (arm,'wtap') or r['opponent'] not in foes:continue
                k=r['arm'],key(r)
                if k in old:raise ValueError('Overlapping donor splits')
                old[k]=r
    if len(rows)!=len(foes)*len(clocks)*4 or len(old)!=len(rows)*2 or len({key(r) for r in rows})!=len(rows):
        raise ValueError('Incomplete donor panel')
    grouped=defaultdict(list);panels={p.name.split('.')[0]:s for s,paths in sets.items() for p in paths}
    for r in rows:
        ref=old[arm,key(r)];base=r['original'];branches=r.get('branches',{})
        if [ref[k] for k in ('score','end_ms','fresh','incoming')]!=[base[k] for k in ('score','end','fresh','incoming')] or r['plain_wtap']!=old['wtap',key(r)]['score']:
            raise ValueError('Unchanged fight differs from the registered outcome')
        if branches and any(branches['unchanged'][k]!=v for k,v in base.items()):raise ValueError('No-op changed early hits')
        for cut in ('all',f"{r['rtt']}/{r['side']}",r['clock']):
            grouped[panels[r['clock']],r['opponent'],cut].append(r)
    result={}
    for cell,rr in grouped.items():
        forks=[r['branches'] for r in rr if 'branches' in r]
        changes=[b['coast-1']['score']-b['unchanged']['score'] for b in forks]
        result['/'.join(cell)]=dict(starts=len(rr),triggered=len(forks),plain_wtap_spawn=record([r['plain_wtap'] for r in rr]),
            from_fork={mode:record([b[mode]['score'] for b in forks]) for mode in ('unchanged','wtap','coast-1')},
            improved=sum(x>0 for x in changes),worse=sum(x<0 for x in changes),ties=changes.count(0))
    freeze(dest/'coast-training-forks.jsonl.gz',gzip.compress(raw,mtime=0))
    dump(dest/'coast-training-summary.json',result)


if __name__=='__main__':
    run()
