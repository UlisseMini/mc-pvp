#!/usr/bin/env python3
"""Offline Orbit mechanism audit; never supplies state to a competing client."""
import argparse
import bisect
import collections
import hashlib
import json
import math
import statistics
from pathlib import Path

from science import client_events, server_events
from combo_survival import geometric_distance
from trade_return_windows import link_clicks, attack_kind

ROOT = Path(__file__).resolve().parent


def error_summary(errors):
    if not errors:
        return {'n': 0}
    absolute = sorted(map(abs, errors))
    return {'n': len(errors), 'bias_blocks': statistics.mean(errors),
            'mae_blocks': statistics.mean(absolute),
            'p95_absolute_blocks': absolute[min(len(absolute)-1, int(.95*len(absolute)))]}


def analyze(row):
    game = row['game']
    side = 'b' if row['fixture']['reverse'] else 'a'
    other = 'a' if side == 'b' else 'b'
    raw = server_events(game['id'])
    native = client_events(game, side)
    opposite = client_events(game, other)
    _, linked, audit = link_clicks(raw, native, game, side)
    plans = [e for e in native if e['type'] == 'orbit_plan']
    plan_times = [p['time'] for p in plans]
    probes = [(p['time'], geometric_distance(p)) for p in opposite if p['type'] == 'reach_probe']
    probes = [(t, d) for t, d in probes if d is not None]
    probe_times = [p[0] for p in probes]
    outcomes = collections.Counter()
    fresh_hits = []
    for index, event in enumerate(raw):
        if event['type'] != 'science_server_attack' or event['attacker'] != game[side]:
            continue
        link = linked.get(index)
        kind = attack_kind(event)
        if not link:
            continue
        when = game['start'] + link['consumption_ms']
        i = bisect.bisect_right(plan_times, when)-1
        plan = plans[i] if i >= 0 else {}
        mode = plan.get('mode', 'unavailable')
        outcomes[mode+':'+kind] += 1
        if kind != 'fresh':
            continue
        actual_sprint = event['attacker_before']['sprint']
        outcomes[mode+(':fresh_sprint' if actual_sprint else ':fresh_normal')] += 1
        j = bisect.bisect_right(probe_times, event['time'])-1
        probe = probes[j] if j >= 0 else None
        attacker, victim = event['attacker_before'], event['target_before']
        fresh_hits.append({'t_ms': event['time']-game['start'], 'mode': mode,
                           'server_sprint': actual_sprint, 'local_sprint_at_click': link['local_sprint'],
                           'own_nearest_range_at_click': link['current_box_min_distance'],
                           'true_horizontal_gap': math.hypot(attacker['posX']-victim['posX'], attacker['posZ']-victim['posZ']),
                           'target_relative_height': victim['posY']-attacker['posY'],
                           'velocity_packet': event.get('velocity_packet'),
                           'opponent_nearest_range_last_sample': probe[1] if probe else None,
                           'opponent_sample_age_ms': event['time']-probe[0] if probe else None})
    cycles = []
    for first, second in zip(fresh_hits, fresh_hits[1:]):
        start, end = game['start']+first['t_ms'], game['start']+second['t_ms']
        samples = [d for t, d in probes if start <= t <= end]
        cycles.append({'start_ms': first['t_ms'], 'end_ms': second['t_ms'],
                       'interval_ms': end-start,
                       'from_sprint': first['server_sprint'], 'to_sprint': second['server_sprint'],
                       'horizontal_gap_drift': second['true_horizontal_gap']-first['true_horizontal_gap'],
                       'height_drift': second['target_relative_height']-first['target_relative_height'],
                       'opponent_sample_count': len(samples),
                       'opponent_sampled_min_range': min(samples) if samples else None})
    errors = collections.defaultdict(list)
    for first, second in zip(plans, plans[1:]):
        if first.get('projected_range') is None or not 40 <= second['time']-first['time'] <= 65:
            continue
        transition = any(first[k] != second[k] for k in ('landed_cue', 'incoming_cue'))
        errors['cue_transition' if transition else 'no_cue_transition'].append(second['local_range']-first['projected_range'])
    return {'match': game['id'], 'candidate': row['fixture']['candidate'], 'rtt': row['fixture']['rtt'],
            'score': row['score'], 'quality_passed': row['quality']['passed'],
            'linkage': audit, 'outcomes_by_requested_mode': dict(outcomes),
            'post_wake': row['combo_analysis']['sides'][side]['after_opponent_wake'],
            'geometric_lock': row['combo_analysis']['sides'][side]['geometric_lock'],
            'first_six_seconds_fresh_hits': [h for h in fresh_hits if h['t_ms'] <= 6000],
            'cycles': cycles, 'one_step_range_forecast': {k: error_summary(v) for k, v in errors.items()}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('cohort')
    args = parser.parse_args()
    source = ROOT/'experiments'/(args.cohort+'.json')
    document = json.loads(source.read_text())
    result = {'source': str(source.relative_to(ROOT)), 'cohort_state': document['state'],
              'analyzer_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'scope': 'Offline native input-to-server associations and sampled geometry; no cross-client control. '
                       'Range errors are next local observation minus a one-step prediction; no-cue samples can still contain unobserved packet transitions. '
                       'Sampled minimum range is not a proof of continuous denial. Only quality-passed matches support comparisons.',
              'games': [analyze(r) for r in document['results']]}
    target = ROOT/'research'/(args.cohort+'-cycles.json')
    target.write_text(json.dumps(result, indent=2)+'\n')
    print(target)
    for game in result['games']:
        wake = game['post_wake']
        print(game['match'], game['candidate'], game['rtt'], game['score'],
              'post-wake fresh', wake['fresh_hits'], 'run', wake['longest_fresh_run'],
              'first reply', wake['first_return_ms_after_wake'], 'quality', game['quality_passed'])


if __name__ == '__main__':
    main()
