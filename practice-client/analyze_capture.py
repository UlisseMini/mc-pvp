#!/usr/bin/env python3
"""Validate and summarize a capture without exposing chat or packet payloads.

Uses monotonic time only within the capturing JVM. Cross-process joins use the
shared host's UNIX clock, match ID and participant identity, never nanoTime.
"""
import argparse
import base64
from collections import Counter
import gzip
import json
from pathlib import Path
import statistics


def rows(path):
    opener = gzip.open if str(path).endswith('.gz') else open
    with opener(path, 'rt', encoding='utf-8') as stream:
        for line in stream:
            yield json.loads(line)


def distribution(values):
    ordered = sorted(values)
    if not ordered:
        return {'n': 0}
    return {'n': len(ordered), 'median': round(statistics.median(ordered), 3),
            'p95': round(ordered[min(len(ordered)-1, int(len(ordered)*.95))], 3),
            'max': round(ordered[-1], 3)}


def capture_rows(path):
    """Follow only sibling segment names written in our integrity footers."""
    path = Path(path); seen = set()
    while path:
        if path in seen:
            raise ValueError('Capture segment cycle')
        seen.add(path); next_part = None; footer = None
        for row in rows(path):
            if row['type'] == 'capture_end':
                footer = row
                name = row.get('continues')
                if name:
                    if Path(name).name != name or not name.endswith('.jsonl.gz'):
                        raise ValueError('Invalid segment filename')
                    next_part = path.parent/name
            yield row
        if not footer:
            raise ValueError('Unfinished capture segment: '+str(path))
        path = next_part


def summarize(path, replay=None):
    counts, kinds, directions = Counter(), Counter(), Counter()
    arrival, applied = {}, {}
    start, footer = {}, {}
    warnings, matches = set(), set()
    linkage, clock, samples = [], [], []
    direct_delivery, direct_draw, direct_clock = [], [], []
    openings, drill_gaps = {}, set()
    direct_drops = 0
    three_views = 0
    observer_errors = module_drops = 0
    first_inbound = None
    for row in capture_rows(path):
        kind = row['type']; counts[kind] += 1
        data = row.get('data', {})
        if row.get('match'):
            matches.add(row['match'])
        if kind == 'capture_start':
            start = data
        elif kind == 'capture_end':
            footer = row
            if not row.get('writer_complete'):
                warnings.add('one or more segments have dropped records or reached a size cap')
        elif kind == 'packet_wire':
            p = data['packet']; directions[p['direction']] += 1; kinds[p['kind']] += 1
            payload = base64.b64decode(data['base64'], validate=True)
            if len(payload) != p['bytes']:
                warnings.add('packet byte count mismatch')
            if p['direction'] == 'in':
                arrival[p['packet_id']] = row['monotonic_ns']
                if first_inbound is None:
                    first_inbound = {'kind': p['kind'], 'packet_id_byte': payload[0] if payload else None}
        elif kind == 'packet_handler_enter' and data.get('main_thread'):
            applied.setdefault(data['packet_id'], row['monotonic_ns'])
        elif kind == 'capture_health':
            observer_errors = max(observer_errors, data.get('observer_errors', 0))
        elif kind == 'module_event_drop':
            module_drops += data['count']
        elif kind in ('packet_oversize', 'link_rejected'):
            warnings.add(kind)
        elif kind == 'link_state':
            linkage.append(row['time_ms']-data['server_ms'])
            if len(data.get('clients', {})) == 2 and len(data.get('server', {})) == 2:
                three_views += 1
            if data.get('drill'):
                drill_gaps.add(data['drill']['gap'])
        elif kind == 'practice_views':
            timing = data.get('timing', {})
            if data.get('available') and timing:
                direct_delivery.append(timing['delivery_ms'])
                direct_draw.append(timing['draw_ms'])
                direct_clock.append(timing['clock_rtt_ms'])
                direct_drops = max(direct_drops, timing['dropped'])
            last = data.get('opening', {}).get('last', {})
            if last:
                openings[(data.get('match'), last['index'])] = last
        elif kind in ('link_welcome', 'link_pong'):
            clock.append(row['time_ms']-data['client_send_ms'])
        if 'sample_begin_ns' in data:
            samples.append((data['sample_end_ns']-data['sample_begin_ns'])/1e6)
    delays = [(applied[k]-arrival[k])/1e6 for k in applied if k in arrival]
    if not footer.get('writer_complete'):
        warnings.add('writer did not report a complete, uncapped segment')
    if start.get('started_mid_connection'):
        warnings.add('segment starts mid-connection: initial world packets may be missing')
    if not first_inbound or first_inbound['packet_id_byte'] != 1:
        warnings.add('first inbound PLAY packet is not Join Game (0x01)')
    if observer_errors:
        warnings.add('native observer errors')
    if module_drops:
        warnings.add('live module events dropped; disk capture is independently checked')
    result = {'capture': str(Path(path).resolve()), 'schema': start.get('schema'),
              'counts': dict(counts), 'packets': {'directions': dict(directions), 'kinds': dict(kinds), 'first_inbound': first_inbound},
              'matches': sorted(matches), 'writer': footer, 'observer_errors': observer_errors,
              'module_events_dropped': module_drops, 'three_pov_snapshots': three_views,
              'arrival_to_main_handler_ms': distribution(delays),
              'server_state_delivery_age_ms': distribution(linkage),
              'clock_probe_round_trip_ms': distribution(clock),
              'snapshot_construction_ms': distribution(samples),
              'direct_view': {'sample_to_receive_ms': distribution(direct_delivery),
                              'sample_to_draw_ms': distribution(direct_draw),
                              'clock_probe_rtt_ms': distribution(direct_clock), 'max_queue_drops': direct_drops,
                              'scope': '20 Hz capture sample of render-frame measurements; excludes GPU/display latency'},
              'opening_intervals': [{'match': key[0], **value} for key, value in openings.items()],
              'drill_start_gaps': sorted(drill_gaps),
              'warnings': sorted(warnings),
              'scope': 'Capture integrity and timing report; not proof of pixel-perfect replay or zero observation overhead.'}
    if replay:
        server_counts, clients = Counter(), Counter()
        for row in rows(replay):
            server_counts[row.get('type', '')] += 1
            if row.get('type') == 'pov_client':
                clients[row.get('player', '?')] += 1
        result['server_recording'] = {'file': str(Path(replay).resolve()),
                                     'pov_clients': dict(clients),
                                     'pov_server_rows': server_counts['pov_server'],
                                     'markers': server_counts['practice_marker']}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('capture', type=Path)
    parser.add_argument('--server-replay', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    report = json.dumps(summarize(args.capture, args.server_replay), indent=2)+'\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(report)
    else:
        print(report, end='')


if __name__ == '__main__':
    main()
