#!/usr/bin/env python3
"""Post-match audit of native block windows and the key/server/cue pipeline.

No recording data is supplied to the live policy. Pair consecutive unused input
edges with native starts, and fresh server hits with later client hurt messages.
Unmatched events and long native windows are reported, never silently discarded.
"""
import argparse
import json
import statistics
from pathlib import Path

from blockhit_prediction_check import describe, game


def summary(xs):
    return {'n': len(xs), 'mean': statistics.mean(xs), 'median': statistics.median(xs),
            'min': min(xs), 'max': max(xs)} if xs else {'n': 0}


def pair_delay(origins, arrivals, limit):
    result = []
    used = set()
    for arrived in arrivals:
        choices = [(i, start) for i, start in enumerate(origins) if i not in used and 0 <= arrived-start <= limit]
        if choices:
            i, start = choices[0]
            used.add(i)
            result.append(arrived-start)
    return result


def audit(directory, result):
    side = result['blockhit_summary']['bot_side']
    who = result[side]
    client = [json.loads(l) for l in (directory/(result['id']+'-client.jsonl')).read_text().splitlines()]
    server = [json.loads(l) for l in (directory/(result['id']+'.jsonl')).read_text().splitlines()]
    starts, keys, key_lengths, windows = [], [], [], []
    start = None
    for e in client:
        if e['type'] != 'blockhit_plan': continue
        if e['block_key'] and start is None:
            start = e['time']; keys.append(start)
        elif not e['block_key'] and start is not None:
            key_lengths.append(e['time']-start); start = None
    start = None
    for e in server:
        if e['type'] != 'block_attempt' or e['player'] != who: continue
        if e['blocking']:
            start = e['time']; starts.append(start)
        elif start is not None:
            windows.append(e['time']-start); start = None
    if start is not None: windows.append(result['finish']-start)
    hits = [e['time'] for e in server if e['type']=='hit' and e['target']==who and not e.get('damage_upgrade') and not e.get('rod')]
    cues = [e['time'] for e in client if e['type']=='fresh_hurt_status']
    up = pair_delay(keys, starts, 300)
    down = pair_delay(hits, cues, 300)
    rtt = result['ping_'+side]
    calibrated = [e['error_ms'] for e in client if e['type']=='prediction_calibration' and e['count']>5]
    forecast = game(directory, result, min(250, rtt+50))
    ticks = [e['time'] for e in server if e['type']=='tick']
    return {'match': result['id'], 'policy': result['policy_'+side], 'rtt': rtt,
            'key_holds_ms': summary(key_lengths), 'server_windows_ms': summary(windows),
            'server_windows_over_200ms': [x for x in windows if x>200],
            'key_starts': len(keys), 'server_starts': len(starts),
            'key_to_server_ms': summary(up), 'server_to_cue_ms': summary(down),
            'unmatched_server_starts': len(starts)-len(up), 'unmatched_cues': len(cues)-len(down),
            'adaptive_forecast_errors_after_five_updates_ms': describe(calibrated),
            'fixed_horizon_forecast_errors_ms': describe(forecast['errors']),
            'fixed_horizon_coverage': [len(forecast['errors']), forecast['cues']],
            'forecast_alarms': len(forecast['alarms']),
            'forecast_alarms_without_hit_in_250ms': sum(x is None for x in forecast['alarms']),
            'server_tick_intervals_ms': summary([b-a for a,b in zip(ticks,ticks[1:])]),
            'pipeline_beyond_rtt_ms': statistics.mean(up)+statistics.mean(down)-rtt if up and down else None}


if __name__ == '__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('directories', nargs='+', type=Path)
    p.add_argument('--output', required=True, type=Path)
    a=p.parse_args()
    games=[audit(d,r) for d in a.directories for r in json.loads((d/'report.json').read_text())['results']]
    a.output.write_text(json.dumps({'definition': 'Native server block duration, and key-to-server + server-to-cue minus configured own RTT. Wall-clock timestamps on same laptop.', 'games':games},indent=2)+'\n')
    for g in games:
        print(g['match'],g['policy'],'native',g['server_windows_ms'],'extra pipeline',g['pipeline_beyond_rtt_ms'])
