"""Resolve the weakest distinct opponents on each surface and show W-tap in those exact cells."""
import argparse
from collections import Counter, defaultdict
import gzip
import json
from statistics import median
import time

from adaptation import dump, sha
from mmc_league import EVIDENCE, WORK, WT, native_name, registered_control_stages


def record(scores):
    c=Counter(scores)
    if set(c)-{0,.5,1}:raise ValueError('Unknown outcome')
    return dict(wins=c[1],draws=c[.5],losses=c[0],games=len(scores))


def points(count):
    return (count['wins']+.5*count['draws'])/count['games']


def summarize(rows, expected, model):
    grouped=defaultdict(list)
    for r in rows:
        grouped[r['arm'],r['opponent']].append(r)
    if set(grouped)!=set(expected):raise ValueError('Different planned matchups')
    result={}
    for names,items in grouped.items():
        slices=defaultdict(list);replicates=defaultdict(list);seen=set()
        for r in items:
            sample=r['clock'] if model else r['rep']
            k=r['rtt'],r['side'],sample
            if k in seen:raise ValueError('Repeated fixture')
            seen.add(k)
            s=r['score'] if model else 1 if r['win'] else .5 if r['draw'] else 0
            slices['all'].append(s);slices[f"{r['rtt']}/{r['side']}"].append(s);replicates[sample].append(s)
        n=expected[names]
        if len(slices)!=5 or any(len(slices[f'{t}/{s}'])!=n for t in (0,80) for s in 'ab') or any(len(v)!=4 for v in replicates.values()):
            raise ValueError('Unbalanced seat and RTT cells')
        cells={k:record(v) for k,v in slices.items()}
        across={str(k):record(v) for k,v in replicates.items()}
        p=[points(v) for v in across.values()]
        result[names]=dict(cells=cells,matched_seat_rtt_blocks=across,block_point_rate_min_median_max=[min(p),median(p),max(p)])
    return result


def build(tag):
    dest=EVIDENCE/tag
    receipt=dest/'matchup-scorecard.json'
    if receipt.exists():return
    sources={n:dest/n for n in ('schedule.json','model.jsonl.gz','native.json.gz','outcomes.json')}
    spec=json.loads(sources['schedule.json'].read_text())
    result=json.loads(sources['outcomes.json'].read_text())
    arms={native_name(n):v for n,v in spec['arms'].items()}
    foes={native_name(n):v for n,v in spec['opponents'].items()}
    repeats={native_name(n):v for n,v in spec['repeats'].items()}
    with gzip.open(sources['native.json.gz'],'rt') as f:audit=json.load(f)
    records=result['records']
    if audit['summary']['binding_disagreements'] or {r['mid'] for r in records}!={g['mid'] for g in audit['matches']}:
        raise ValueError('Physical audit does not cover all scored games')
    with gzip.open(sources['model.jsonl.gz'],'rt') as f:sim=list(map(json.loads,f))
    for row in sim:
        for field in ('arm','opponent'):row[field]=native_name(row[field])
    by_source={}
    for name,rows in [('model',sim),('native',records)]:
        expectation={(a,o):8 if name=='model' else repeats[o] for a in arms for o in foes}
        by_source[name]=summarize(rows,expectation,name=='model')
    card={}
    for arm,identity in arms.items():
        distinct=[o for o in foes if foes[o]!=identity]
        weak={}
        for mode,values in by_source.items():
            rates={o:points(values[arm,o]['cells']['all']) for o in distinct}
            weak[mode]=sorted(o for o,v in rates.items() if v==min(rates.values())) if rates else []
        chosen=list(dict.fromkeys([WT,*weak['native'],*weak['model']]))
        card[arm]=dict(weakest_distinct_including_ties=weak,compared={o:{m:dict(
            candidate=values[arm,o],plain_wtap=values[WT,o]) for m,values in by_source.items()} for o in chosen})
    dump(receipt,dict(native_winner=result['winner'],preset=spec['profile'],sources={n:sha(p) for n,p in sources.items()},
         grading='Weakest is the minimum wins-plus-half-draws fraction among different controller fingerprints. Include all ties. Both arms have equal exposure in each displayed matchup. Each model block uses one clock in both seats and RTTs. Each native repeat block consists of independent starts in those four cells.',
         arms=card))


def wait():
    done=set()
    deadline=json.loads((WORK/'admission-deadline.json').read_text())['unix_time']
    while time.time()<deadline+600:
        for tag in ('opening','final',*registered_control_stages()):
            if tag not in done and (EVIDENCE/tag/'outcomes.json').is_file():
                build(tag);done.add(tag)
        time.sleep(15)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('stage');stage=p.parse_args().stage
    if stage=='wait':wait()
    else:build(stage)
