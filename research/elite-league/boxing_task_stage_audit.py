"""Check which server drain or client movement a native packet could affect."""

import argparse
from bisect import bisect_left
from collections import Counter
import gzip
import json
from pathlib import Path
import sys

from boxing_upgrade_audit import ROOT, events

sys.path.insert(0, str(ROOT / 'research'))
from network_pipeline_audit import pair_payloads, wire


def capture(path, kinds):
    patterns = tuple(f'"{kind}"' for kind in kinds)
    with gzip.open(path, 'rt') as stream:
        rows = [json.loads(line) for line in stream if any(pattern in line for pattern in patterns)]
    return sorted((row for row in rows if row['type'] in kinds), key=lambda row: row.get('monotonic_ns', 0))


def at_or_after(samples, instant):
    at = bisect_left(samples, instant)
    return at if at < len(samples) else None


def audit(mid, setup):
    game = json.loads((ROOT / 'runtime/results' / f'{mid}.json').read_text())
    begin, end = game['start'] + 250, game['finish'] - 250
    folder = ROOT / 'runtime/pov' / mid
    server_trace = list(events(ROOT / 'runtime/replays' / f'{mid}.jsonl',
                               {'replication_server_input', 'replication_server_clock', 'replication_tracker_before'},
                               game['start'], game['finish']))
    starts = sorted((e['monotonic_ns'], e['server_tick']) for e in server_trace
                    if e['type'] == 'replication_server_clock' and e['phase'] == 'drain_start')
    times = [ns for ns, _ in starts]
    ids = {e['tracker']['player']: e['tracker']['entity_id'] for e in server_trace
           if e['type'] == 'replication_tracker_before'}
    handlers = {e['packet_id']: e['server_tick'] for e in server_trace if e['type'] == 'replication_server_input'
                and e['packet_kind'] == 'in' and e['action'] == 'ATTACK'}
    server = capture(next(folder.glob('server-*-events.jsonl.gz')), {'packet_wire'})
    report = []
    for side in (setup['side'], 'b' if setup['side'] == 'a' else 'a'):
        name = game[side]
        client = capture(next(folder.glob(f'{side}-client-*-events.jsonl.gz')),
                         {'packet_wire', 'packet_handler_enter', 'client_task_drain', 'science_move'})
        up, _, _ = pair_payloads(wire(client, 'out'), wire(server, 'in', name))
        down, _, _ = pair_payloads(wire(server, 'out', name), wire(client, 'in'))
        drains = [e['monotonic_ns'] for e in client if e['type'] == 'client_task_drain' and e['data']['phase'] == 'start']
        moves = [e['monotonic_ns'] for e in client if e['type'] == 'science_move' and e.get('match') == mid]
        actual_apps = {e['data']['packet_id']: e['monotonic_ns'] for e in client
                       if e['type'] == 'packet_handler_enter' and e['data']['main_thread']}
        up_result = {key: Counter() for key in ('wire_receipt', 'fixed_leg')}
        down_result = {key: Counter() for key in ('wire_receipt', 'fixed_leg')}
        # The previous timing study used 10 ms nominal RTT/2 plus 0.35 ms overhead.
        fixed_ns = 10_350_000
        for sent, received in up:
            packet = sent['packet']
            if packet['id'] != 2 or packet['action'] != 1 or not begin <= sent['event']['time_ms'] <= end:
                continue
            handled = handlers[received['packet_id']]
            for key, receipt in (('wire_receipt', received['ns']), ('fixed_leg', sent['ns'] + fixed_ns)):
                predicted = at_or_after(times, receipt)
                if predicted is None:
                    raise ValueError('Missing server drain in covered capture')
                up_result[key][starts[predicted][1] - handled] += 1
        for sent, received in down:
            packet = sent['packet']
            if packet['id'] != 0x12 or packet['entity_id'] != ids[name] or not begin <= sent['event']['time_ms'] <= end:
                continue
            handled = actual_apps[received['packet_id']]
            actual = at_or_after(moves, handled)
            for key, receipt in (('wire_receipt', received['ns']), ('fixed_leg', sent['ns'] + fixed_ns)):
                drain = at_or_after(drains, receipt)
                predicted = None if drain is None else at_or_after(moves, drains[drain])
                if predicted is None or actual is None:
                    raise ValueError('Missing movement or client drain in covered capture')
                down_result[key][predicted - actual] += 1
        report.append(dict(side=side, c02=up_result, own_s12=down_result))
    return dict(id=mid, arm=setup['arm'], players=report)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest', type=Path)
    parser.add_argument('destination', type=Path)
    args = parser.parse_args()
    batch = json.loads(args.manifest.read_text())
    with args.destination.open('w') as out:
        for request, mid in batch['matches'].items():
            row = audit(mid, batch['plan'][request])
            out.write(json.dumps(row) + '\n')
            out.flush()


if __name__ == '__main__':
    main()
