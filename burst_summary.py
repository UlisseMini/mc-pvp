#!/usr/bin/env python3
"""Summarize the frozen burst comparison without treating clicks as hits."""
import argparse
import collections
import hashlib
import json
import statistics
from pathlib import Path

from science import client_events


def summarize(path):
    plan=json.loads(path.read_text())
    if plan['stage']!='complete':
        raise ValueError('The full comparison must finish before final reporting')
    groups=collections.defaultdict(list)
    minimum_gap=float('inf');max_p95=0;max_gap=0;release_counts={}
    for row in plan['results']:
        f=row['fixture'];g=row['game'];diag=row['diagnostic']
        assert g['validation']['passed'] and diag['passed'],g['id']
        assert g['reason']=='100_hits',g['id']
        a,b=('b','a') if f['reverse'] else ('a','b')
        for s in (a,b):
            assert row['exchanges']['sides'][s]['counts']['accepted']==g['hits_'+s]
            d=diag['sides'][s]
            minimum_gap=min(minimum_gap,d['minimum_click_gap_ms'])
            max_p95=max(max_p95,d['travel_cadence']['p95_ms'])
            max_gap=max(max_gap,d['travel_cadence']['max_ms'])
            # Count actual native travel updates with W released after movement
            # begins. Exclude only the declared startup observation warmup.
            moves=[e for e in client_events(g,s) if e['type']=='science_move']
            started=False;run=0;counts=collections.Counter()
            for e in moves:
                if e['forward']>0:
                    started=True
                    if run:counts[run]+=1;run=0
                elif started:run+=1
            if run:counts[run]+=1
            release_counts[g['id']+'-'+s]={'policy':g['policy_'+s],
                'release_runs_by_native_travel_updates':dict(counts)}
        key=(f['focus'],f['opponent'],f['focus_rtt'],f['opponent_rtt'])
        groups[key].append({'id':g['id'],'score':[g['hits_'+a],g['hits_'+b]],
            'fresh':[row['exchanges']['sides'][s]['counts']['fresh']for s in (a,b)],
            'upgrades':[row['exchanges']['sides'][s]['counts']['upgrades']for s in (a,b)],
            'cps':[diag['sides'][s]['average_cps']for s in (a,b)],
            'longest_unanswered_streak':[g['combat_metrics'][s]['longest_unanswered_streak']for s in (a,b)],
            'sustained_combo_ms':[g['combat_metrics'][s]['sustained_native_combo']['duration_ms']for s in (a,b)],
            'duration_seconds':g['duration_seconds'],
            'drag_contacts':[diag['sides'][s]['contact_drag_counts']for s in (a,b)]})
    out=[]
    for (focus,opponent,pa,pb),games in groups.items():
        out.append({'focus':focus,'opponent':opponent,'rtt':[pa,pb],'games':games,
            'wins':sum(g['score'][0]>g['score'][1]for g in games),
            'mean_score':[statistics.mean(g['score'][s]for g in games)for s in (0,1)],
            'pooled_fresh':[sum(g['fresh'][s]for g in games)for s in (0,1)],
            'mean_cps':[statistics.mean(g['cps'][s]for g in games)for s in (0,1)]})
    return {'source':str(path),'source_sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
            'games':len(plan['results']),'groups':out,'minimum_physical_click_gap_ms':minimum_gap,
            'maximum_client_travel_p95_ms':max_p95,'maximum_client_travel_gap_ms':max_gap,
            'native_release_runs':release_counts,
            'caution':'Small exploratory cohorts; inspect game counts per condition. No claim of a phase-independent advantage or combo invariant. Fresh attacks exclude within-immunity damage upgrades. Drag counts describe contacts, not a causal decomposition.'}


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('input',type=Path);p.add_argument('output',type=Path)
    a=p.parse_args();result=summarize(a.input)
    a.output.write_text(json.dumps(result,indent=2)+'\n')
    for r in result['groups']:
        print(r['focus'],'vs',r['opponent'],'RTT',r['rtt'],
              'scores',[g['score']for g in r['games']], 'wins',r['wins'])
