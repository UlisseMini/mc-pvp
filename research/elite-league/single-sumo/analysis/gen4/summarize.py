"""Reduce the replayed fights to auditable counts. Expects the .gz and JSON outputs of the review jobs."""
from collections import Counter
import gzip
import json
from pathlib import Path
import statistics
import sys

work=Path(sys.argv[1])
out=Path(sys.argv[2])
read=lambda name:json.loads((work/name).read_text())
def rows(p):return [json.loads(l) for l in gzip.open(p,'rt')]
def wdl(rr):return [sum(x['outcome']==s for x in rr) for s in (1,.5,0)]
data={'tournament_replays':{},'controls':{},'phase_edits':{},'new_seeds':{},'memory':{},'trades':{}}
for f in sorted(work.glob('*--*.gz')):
    games=rows(f);base=[r for r in games if r['arm']=='intact'];by_arm={}
    for arm in sorted({r['arm'] for r in games}-{'catalog'}):
        armrows=[r for r in games if r['arm']==arm]
        by_arm[arm]=wdl(armrows)
    sides={}
    for i,key in enumerate(('actor','opponent')):
        fresh=[h for r in base for h in r['fresh'] if h[1]==(i==0)]
        first=[r for r in base if r['fresh'] and r['fresh'][0][1]==(i==0)]
        sides[key]={'fresh':len(fresh),'fresh_sprinting':sum(h[2] for h in fresh),
                    'first_fresh_actor_wdl':wdl(first),
                    'counts':list(map(sum,zip(*(r['counts'][i] for r in base)))),
                    'before_either_hurt_cue':list(map(sum,zip(*(r['opening'][i] for r in base))))}
    data['tournament_replays'][f.stem]=dict(arms=by_arm,sides=sides,verified=len(base),
            minimum_press_interval_ns=[min(r['min_ns'][i] for r in base) for i in range(2)])
for f in sorted(work.glob('trade-*.gz')):
    games=rows(f);group={}
    for arm in sorted({r['arm'] for r in games}):
        rr=[r for r in games if r['arm']==arm]
        group[arm]=dict(actor=list(map(sum,zip(*(r['counts'][0] for r in rr)))),
                        opponent=list(map(sum,zip(*(r['counts'][1] for r in rr)))),
                        summed_horizontal_impulses=[sum(r['impulses'][i][0] for r in rr) for i in range(2)],
                        seconds=sum(r['end'] for r in rr)/1000)
    data['trades'][f.stem]=group
for f in sorted(work.glob('control-*.json')):data['controls'][f.stem]=read(f.name)
for f in sorted(work.glob('phase-*.json')):data['phase_edits'][f.stem]=read(f.name)
for f in sorted(work.glob('check-*.json')):
    data['new_seeds'][f.stem]={k:[v.count(1),v.count(.5),v.count(0)] for k,v in read(f.name).items()}
for f in sorted(work.glob('memory-*.gz')):
    games=rows(f)
    for foe in sorted({r['foe'] for r in games}):
        grouped={a:[r for r in games if r['arm']==a and r['foe']==foe] for a in ['swap-sham','swap-same','swap-other']}
        triples=list(zip(*grouped.values(),strict=True))
        assert all(x['k']==y['k']==z['k'] for x,y,z in triples)
        data['memory'].setdefault(f.stem,{})[foe]={**{a:wdl(rs) for a,rs in grouped.items()},
            'same_wins_other_doesnt':sum(y['outcome']==1 and z['outcome']!=1 for x,y,z in triples),
            'other_wins_same_doesnt':sum(z['outcome']==1 and y['outcome']!=1 for x,y,z in triples)}
data['native_pre_fix']=[json.loads(s) for s in (work/'human-native.jsonl').read_text().splitlines()]
data['native_other_pre_fix']=[json.loads(s) for s in (work/'other-native.jsonl').read_text().splitlines()]
data['native_post_fix']=[json.loads(s) for s in (work/'native-fixed.jsonl').read_text().splitlines()]
out.write_text(json.dumps(data,indent=2,sort_keys=True)+'\n')
