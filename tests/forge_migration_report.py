#!/usr/bin/env python3
"""Read-only compact evidence for isolated Forge migration native runs."""
import argparse
import collections
import gzip
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def percentile(values, quantile):
    return sorted(values)[min(len(values)-1, int((len(values)-1)*quantile))] if values else None


def archive(path):
    rows = [json.loads(line) for line in gzip.open(path, 'rt')]
    kinds = collections.Counter(row.get('type') for row in rows)
    headers = [row for row in rows if row.get('type') == 'pov_archive_start']
    packets = collections.Counter(row.get('data', {}).get('packet', {}).get('kind', '?')
                                  for row in rows if row.get('type') == 'packet_wire')
    clicks = [row for row in rows if row.get('type') == 'click']
    attacks = [row for row in rows if row.get('type') == 'engine_attack']
    frames = [row for row in rows if row.get('type') == 'pov_frame']
    times = [row['time_ms'] for row in frames]
    deltas = [b-a for a, b in zip(times, times[1:])]
    intervals = [(b['monotonic_ns']-a['monotonic_ns'])/1e6 for a, b in zip(clicks, clicks[1:])]
    bad_attacks = [a for a in attacks if not all(a.get(k) for k in
                   ('target_ready', 'target_loaded_identity', 'ray_selected_target', 'target_is_canonical'))
                   or a.get('native_raycast_distance', 100) > a.get('attack_reach', 3)+1e-5]
    observed_errors = [r['data'] for r in rows if r.get('type') == 'capture_health'
                       and (r['data'].get('observer_errors', 0) or r['data'].get('writer_drops', 0))]
    metadata = headers[0].get('metadata', {}) if headers else {}
    return {
        'file': str(path.relative_to(ROOT)), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
        'rows': len(rows), 'types': dict(kinds), 'packet_types': dict(packets),
        'header_first': bool(rows) and rows[0].get('type') == 'pov_archive_start',
        'identity': {k: metadata.get(k) for k in ('player', 'role', 'backend', 'bridge_sha256', 'module_sha256')},
        'footer': rows[-1] if rows and rows[-1].get('type') == 'capture_end' else None,
        'observer_errors': observed_errors, 'invalid_native_attacks': len(bad_attacks),
        'click_count': len(clicks), 'minimum_click_interval_ms': min(intervals) if intervals else None,
        'native_attack_count': len(attacks), 'native_max_raycast_distance': max((a['native_raycast_distance'] for a in attacks), default=None),
        'w_tap_cues': kinds['wtap'], 'strafe_observations': sum(bool(r.get('strafe')) for r in rows if r.get('type') == 'observation'),
        'pose_gaps_ms': {'count': len(deltas), 'p50': percentile(deltas, .5), 'p99': percentile(deltas, .99), 'max': max(deltas, default=None)},
    }


def match(identifier):
    result = json.loads((ROOT/'runtime/results'/f'{identifier}.json').read_text())
    keys = ('id', 'a', 'b', 'policy_a', 'policy_b', 'client_a', 'client_b', 'mode', 'hits_a', 'hits_b',
            'ping_a', 'ping_b', 'click_cps_a', 'click_cps_b', 'attack_reach_a', 'attack_reach_b',
            'reaction_ms_a', 'reaction_ms_b', 'health_a', 'health_b', 'blocks_a', 'blocks_b', 'reason', 'duration_seconds')
    return {'result': {k: result.get(k) for k in keys},
            'archives': [archive(p) for p in sorted((ROOT/'runtime/pov'/identifier).glob('*.gz'))]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('ids', nargs='+')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    data = {'purpose': 'Functional migration evidence; independent native phases do not imply equal scores.',
            'matches': [match(i) for i in args.ids]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(data, indent=2)+'\n')
    print('Recorded', len(data['matches']), 'matches in', args.output)


if __name__ == '__main__':
    main()
