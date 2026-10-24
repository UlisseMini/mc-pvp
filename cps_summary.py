#!/usr/bin/env python3
"""Separate fresh-hit outcomes from accepted-damage scores in a frozen CPS cohort."""
import argparse
import collections
import hashlib
import json
import statistics
from pathlib import Path


def summarize(path):
    plan=json.loads(path.read_text())
    if plan['stage']!='complete' or len(plan['results'])!=len(plan['fixtures']):
        raise ValueError('The frozen comparison must finish first')
    groups=collections.defaultdict(list);min_cap_fraction=1;worst_p95=0;worst_gap=0
    for row in plan['results']:
        f=row['fixture'];game=row['game'];diag=row['diagnostic']
        assert game['reason']=='100_hits' and game['validation']['passed'] and diag['passed'],game['id']
        high,normal=('b','a') if f['reverse'] else ('a','b');sides=(high,normal)
        duration=game['duration_seconds'];metrics=[]
        for side in sides:
            count=row['exchanges']['sides'][side]['counts'];d=diag['sides'][side];inputs=row['delivery'][side]
            assert count['fresh']+count['upgrades']==game['hits_'+side]
            min_cap_fraction=min(min_cap_fraction,inputs['physical_input_cps_during_click_span']/inputs['declared_cps'])
            worst_p95=max(worst_p95,d['travel_cadence']['p95_ms']);worst_gap=max(worst_gap,d['travel_cadence']['max_ms'])
            metrics.append({'fresh':count['fresh'],'upgrades':count['upgrades'],'fresh_sprint':count['fresh_sprint'],
                'accepted':game['hits_'+side],'physical_cps':inputs['physical_input_cps_during_click_span'],
                'native_consumptions_per_second':inputs['native_consumptions']/duration,
                'native_ticks_with_clicks_per_second':sum(inputs['native_clicks_per_active_tick_histogram'].values())/duration,
                'native_ticks_with_multiple_clicks':inputs['ticks_with_multiple_clicks'],
                'entity_contacts_per_second':d['entity_contacts']/duration,
                'forward_drag_contacts_per_second':d['contact_drag_counts'].get('forward_drag',0)/duration,
                'backward_drag_contacts_per_second':d['contact_drag_counts'].get('backward_drag',0)/duration,
                'longest_sustained_native_combo_ms':game['combat_metrics'][side]['sustained_native_combo']['duration_ms']})
        groups[f['cps']].append({'id':game['id'],'repeat':f['repeat'],'higher_rate_side':high,
            'duration_seconds':duration,'fresh':[m['fresh']for m in metrics],
            'accepted':[m['accepted']for m in metrics],'upgrades':[m['upgrades']for m in metrics],
            'sides_higher_then_10':metrics})
    result=[]
    for cps,games in sorted(groups.items()):
        games.sort(key=lambda g:g['repeat']);duration=sum(g['duration_seconds']for g in games)
        fresh=[sum(g['fresh'][s]for g in games)for s in (0,1)]
        result.append({'declared_cps':cps,'games':games,'n':len(games),
            'fresh_leads_ties_trails':[sum((g['fresh'][0]>g['fresh'][1]) if k==0 else (g['fresh'][0]==g['fresh'][1]) if k==1 else (g['fresh'][0]<g['fresh'][1]) for g in games)for k in range(3)],
            'accepted_score_wins':sum(g['accepted'][0]>g['accepted'][1]for g in games),
            'pooled_fresh':fresh,'pooled_fresh_ratio':fresh[0]/fresh[1]if fresh[1]else None,
            'fresh_hits_per_second':[v/duration for v in fresh],
            'mean_physical_cps':[statistics.mean(g['sides_higher_then_10'][s]['physical_cps']for g in games)for s in (0,1)],
            'physical_cps_range':[min(g['sides_higher_then_10'][0]['physical_cps']for g in games),max(g['sides_higher_then_10'][0]['physical_cps']for g in games)],
            'mean_native_ticks_with_clicks_per_second':[statistics.mean(g['sides_higher_then_10'][s]['native_ticks_with_clicks_per_second']for g in games)for s in (0,1)],
            'mean_forward_drag_contacts_per_second':[statistics.mean(g['sides_higher_then_10'][s]['forward_drag_contacts_per_second']for g in games)for s in (0,1)],
            'mean_backward_drag_contacts_per_second':[statistics.mean(g['sides_higher_then_10'][s]['backward_drag_contacts_per_second']for g in games)for s in (0,1)]})
    return {'source':str(path),'source_sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
        'frame_sleep_ms':plan['frame_sleep_ms'],'groups':result,
        'quality':{'qualified_full_games':len(plan['results']),'minimum_delivered_fraction_of_ceiling':min_cap_fraction,
                   'worst_client_movement_p95_ms':worst_p95,'worst_client_movement_gap_ms':worst_gap},
        'interpretation':'Fresh-hit counts are measured at the existing accepted-score stopping point, not first-to-100-fresh wins. Each pair shares its duration. Small counterbalanced samples; no claim of an optimal CPS or transfer to other pings. Contact-drag counts are not net displacement or a causal decomposition.'}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('input',type=Path);parser.add_argument('output',type=Path)
    args=parser.parse_args();result=summarize(args.input)
    args.output.write_text(json.dumps(result,indent=2)+'\n')
    for group in result['groups']:
        print(group['declared_cps'],'CPS; measured',group['mean_physical_cps'],
              'fresh',group['pooled_fresh'],'leads/ties/trails',group['fresh_leads_ties_trails'],
              'accepted scores',[g['accepted']for g in group['games']])
