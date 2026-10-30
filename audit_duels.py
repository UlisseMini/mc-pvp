#!/usr/bin/env python3
"""Post-match human-duel integrity audit. Does not control clients or the server."""
import collections,json,math
from pathlib import Path
from lab import ROOT,RUNTIME


def audit():
    results=[json.loads(p.read_text())for p in (RUNTIME/'results').glob('*.json')]
    wanted={r['id'] for r in results if r['policy_a']=='human'}
    by_match=collections.defaultdict(list)
    for path in (RUNTIME/'bots').glob('[0-9]*/events.jsonl'):
        for line in path.read_text().splitlines():
            e=json.loads(line)
            if e.get('match') in wanted and e['type'] in ('observation','engine_attack','target_guard','click'):by_match[e['match']].append(e)
    report=[]
    for r in sorted((r for r in results if r['id']in wanted),key=lambda r:r['start']):
        es=by_match[r['id']];obs=[e for e in es if e['type']=='observation'];issues=[]
        ticking=[e['target_ticks'] for e in obs if 'target_ticks'in e]
        frozen=bool(ticking and r['finish']-r['start']>1500 and max(ticking)==min(ticking))
        if frozen:issues.append('Opponent native ticks did not advance')
        blocked=[e for e in es if e['type']=='target_guard' and e['time']>r['start']+1000 and
                 (not e['target_loaded_identity'] or not e['target_added_to_chunk'] or e['target_ticks']==0)]
        if blocked:issues.append('Live integrity guard blocked invalid target')
        overlap=[q['id'] for q in results if q['id']!=r['id'] and q['start']-3000<r['finish'] and q['finish']>r['start']-3000 and r['b']in(q['a'],q['b'])]
        if overlap:issues.append('Overlapping bot assignment')
        attacks=[e for e in es if e['type']=='engine_attack'];measured=[e for e in attacks if 'native_raycast_distance'in e]
        reach=r.get('attack_reach_b',3.0)
        legal=all(e['native_raycast_distance']<=reach+.00001 and e['ray_selected_target'] and e['target_ready'] and e['target_loaded_identity']for e in measured)
        if not legal:issues.append('Attack failed native ray/live-target evidence')
        report.append({'match':r['id'],'bot':r['policy_b'],'score_human_bot':[r['hits_a'],r['hits_b']],
            'configured_attack_reach':reach,'modified_reach':r.get('modified_reach',False),
            'issues':issues,'overlapping_assignments':overlap,'target_tick_min':min(ticking,default=None),'target_tick_max':max(ticking,default=None),
            'engine_attacks':len(attacks),'attacks_with_distance_evidence':len(measured),
            'max_native_raycast_distance':max((e['native_raycast_distance']for e in measured),default=None),
            'interpretation':'guarded_invalid' if blocked else 'invalid_frozen_target' if frozen else 'modified_reach_practice' if r.get('modified_reach') and legal else 'qualified_native_attack_evidence' if measured and legal and ticking else 'historical_unqualified'})
    return {'method':'Client native tick progress, runtime integrity guards, attack ray evidence and recorded match-assignment intervals. Historical missing evidence is not proof of validity.','duels':report}

if __name__=='__main__':
    data=audit();(ROOT/'experiments/human-duel-audit.json').write_text(json.dumps(data,indent=2)+'\n')
    print(json.dumps(data['duels'][-8:],indent=2))
