#!/usr/bin/env python3
"""Read-only first-return comparison of completed, matched native fixtures."""
import argparse
import gzip
import json
import math
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]

def read(mid, side):
    with gzip.open(ROOT / 'research/traces' / (mid + '-' + side + '.jsonl.gz'), 'rt') as stream:
        return [json.loads(line) for line in stream]

def nearest(e):
    eye, box = e['native_eye'], e['expanded_box']
    return math.sqrt(sum(max(box[i]-eye[i], 0, eye[i]-box[i+3])**2 for i in range(3)))

def diagnose(row):
    mid = row['match']; game = json.loads((ROOT / 'runtime/results' / (mid+'.json')).read_text())
    start = game['start']; stop = row['first_return_ms']
    es = {side: read(mid, side) for side in ('a', 'b', 'server')}
    attacks = [e for e in es['server'] if e.get('type') == 'science_server_attack' and e['time'] <= start+stop]
    launches = []
    for e in attacks:
        if e['score_before'] == e['score_after']: continue
        a, b = e['attacker_before'], e['target_before']
        launches.append(dict(ms=e['time']-start, side='a' if e['attacker']==game['a'] else 'b',
            fresh=b['hurtResistantTime']<=game['combat_profile']['fresh_hit_ticks'],
            server_tick=e['server_tick'], sprint=a['sprint'], target_y=b['posY']-4,
            center_gap=math.hypot(a['posX']-b['posX'], a['posZ']-b['posZ']),
            target_hurt=b['hurtResistantTime'], velocity=e.get('velocity_packet')))
    out = dict(match=mid, policy=game['policy_a'], repeat=row['repeat'],
        fresh_before_return=row['fresh_hits_before_first_return'], first_return_hit_ms=stop,
        after_wake=row['after_opponent_wake'], accepted_attacks=launches,
        quality_passed=row['validation']['passed'])
    for side in ('a','b'):
        out[side+'_contacts'] = [dict(ms=e['time']-start, range=e['native_raycast_distance'],
            sprint=e['sprint_before'], vx_before=e['vx_before'], vz_before=e['vz_before'],
            vx_after=e['vx_after'], vz_after=e['vz_after']) for e in es[side]
            if e.get('type')=='engine_attack' and start<=e['time']<=start+stop]
        out[side+'_wake_probes'] = [dict(ms=e['time']-start, nearest=nearest(e),
            own_y=e['own']['posY']-4, target_y=e['target']['posY']-4) for e in es[side]
            if e.get('type')=='reach_probe' and start+1900<=e['time']<=start+stop]
    out['a_cycle_plans'] = [e for e in es['a'] if e.get('type') == 'cycle_plan' and start <= e['time'] <= start+stop]
    out['a_issued_clicks'] = [e for e in es['a'] if e.get('type') == 'click' and start <= e['time'] <= start+stop]
    out['server_attack_attempts'] = [e for e in attacks if e['attacker'] == game['a']]
    out['a_wake_movement'] = [dict(ms=e['time']-start, forward=e['forward'], strafe=e['strafe'],
        sprint=e['before']['sprint'], vx=e['before']['motionX'], vz=e['before']['motionZ'])
        for e in es['a'] if e.get('type')=='science_move' and start+1900<=e['time']<=start+stop]
    return out

if __name__ == '__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--repeat', type=int, default=1)
    parser.add_argument('--matches', nargs='+')
    parser.add_argument('--output', default='research/combo-feasibility-native-first-repeat.json')
    args=parser.parse_args(); suite=json.loads((ROOT/'research/matched-native-20261101.json').read_text())
    rows=[diagnose(row) for row in suite['rows'] if (row['match'] in args.matches if args.matches else row['repeat']==args.repeat)]
    report=dict(scope=suite['scope'], wtap_baseline=suite['wtap_baseline'],
        caution='Observed causal ordering, not a same-clock counterfactual. Wall-clock event ms may differ slightly between science attack and hit callbacks. Probes are sampled; contacts are native engine events.', rows=rows)
    (ROOT/args.output).write_text(json.dumps(report, indent=2)+'\n')
    print('Recorded', len(rows), 'completed matches to', args.output)
