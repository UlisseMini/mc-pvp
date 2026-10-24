#!/usr/bin/env python3
"""Measure opening phases; never assume a nominal 50ms offset persists."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
from science import OUT,client_events
from trade_grounding import analyze


def run(name):
    suite=json.loads((OUT/(name+'.json')).read_text())
    if suite['stage']!='complete':raise ValueError('Wait for the preregistered suite to finish')
    rows=[];groups=collections.defaultdict(collections.Counter)
    for trial in suite['results']:
        g=trial['game'];native={s:client_events(g,s) for s in ('a','b')}
        first={}
        for s in ('a','b'):
            first[s]={k:next((e['time']-g['start'] for e in native[s] if e['type']==k),None)
                      for k in ('click','science_click','engine_attack')}
        ground=analyze(g['id'],75,150,850)
        opening=ground['pairs'][:3]
        fresh_events=[e for e in trial['exchanges']['fresh_events'] if e['t']<=6000]
        first_fresh={s:next((e['t'] for e in fresh_events if e['side']==s),None) for s in ('a','b')}
        offset={k:None if first['a'][k] is None or first['b'][k] is None else first['b'][k]-first['a'][k] for k in first['a']}
        group=groups[g['policy_b']];group['matches']+=1
        for p in opening:
            group['trade_pairs']+=1;group[p['state_A_B']]+=1;group[p['outcome']]+=1
        rows.append({'match':g['id'],'opponent':g['policy_b'],'scores':[g['hits_a'],g['hits_b']],
                     'first_event_ms':first,'first_B_minus_A_ms':offset,
                     'first_fresh_ms':first_fresh,'all_fresh_events_first_6_seconds':fresh_events,
                     'fifo_diagnostics':ground['fifo_diagnostics'],'first_three_paired_trades':opening})
    result={'source_suite':name,'script_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            'scope':'Native first input, consumption/contact phases and all fresh events through6s are retained. Grounding summary conditions on the first three unique +/-75ms trade pairs; changing phase can change which events qualify, so that summary is not a causal treatment estimate. Different runs have different real clock schedules. Six trials cannot establish an optimal phase or causality of landing state alone.',
            'groups':{k:dict(v) for k,v in groups.items()},'results':rows}
    (OUT/(name+'-opening-analysis.json')).write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({'groups':result['groups'],'first_offsets':[{k:r[k] for k in ('match','opponent','scores','first_B_minus_A_ms')} for r in rows]},indent=2))


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('suite');run(p.parse_args().suite)
