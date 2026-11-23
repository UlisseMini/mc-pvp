"""Compare scoring at the first eligible fresh-damage drain in native and modeled boxing."""

import argparse
from collections import Counter
from itertools import groupby
import json
from pathlib import Path

from boxing_upgrade_audit import ROOT, events
from tools.trade_return_windows import attack_kind, link_clicks


def measure(hits, threshold, finish_tick):
    """Keep scoring at the first drain separate from surviving several more drains."""
    previous = {}
    last_accepted = {}
    last_side = None
    buckets = [Counter(), Counter()]
    for tick, events_on_drain in groupby(hits, key=lambda hit: hit[0]):
        batch = list(events_on_drain)
        last_accepted.update({side: tick for _, side, _ in batch})
        for _, side, upgrade in batch:
            counts = buckets[side]
            if upgrade:
                counts['upgrade'] += 1
                continue
            counts['fresh'] += 1
            if side not in previous:
                counts['opening'] += 1
            else:
                delay = tick - previous[side] - threshold
                if delay < 0:
                    raise ValueError(f'Fresh hit inside the declared window on drain {tick}, delay {delay}')
                timing = 'first' if delay == 0 else 'second' if delay == 1 else 'later'
                counts[timing] += 1
                phase = 'after_opposing_fresh' if last_side != side else 'without_opposing_fresh'
                counts[f'{timing}_{phase}'] += 1
                if last_accepted.get(1 - side, -1) < previous[side]:
                    counts[f'{timing}_without_accepted_return'] += 1
            previous[side] = tick
            last_side = side

    for side, tick in previous.items():
        # The last scoring interval has no observed completion, even if the first drain was covered.
        buckets[side]['end_first_drain_covered' if finish_tick >= tick + threshold else 'end_before_first_drain'] += 1
    return [dict(b) for b in buckets]


def native_row(mid, fixture):
    meta = json.loads((ROOT / 'runtime/results' / f'{mid}.json').read_text())
    start, finish = meta['start'], meta['finish']
    subject = fixture['side']
    players = [meta[subject], meta['b' if subject == 'a' else 'a']]
    server = list(events(ROOT / 'runtime/replays' / f'{mid}.jsonl',
                         {'replication_server_input', 'science_server_attack', 'replication_server_clock'},
                         start, finish + 100))
    drains = {e['server_tick']: e['monotonic_ns'] for e in server
              if e['type'] == 'replication_server_clock' and e['phase'] == 'drain_start'}
    accepted = []
    consumed = []
    for event in server:
        if event['type'] != 'science_server_attack' or event['attacker'] not in players:
            continue
        kind = attack_kind(event, meta)
        if kind in ('fresh', 'upgrade'):
            accepted.append((event['server_tick'], players.index(event['attacker']), kind == 'upgrade'))
    if len(accepted) != meta['hits_a'] + meta['hits_b']:
        raise ValueError(f'Unreconciled accepted score in {mid}')

    threshold = meta['combat_profile']['fresh_hit_ticks']
    checks = []
    for side in (subject, 'b' if subject == 'a' else 'a'):
        path, = (ROOT / 'runtime/pov' / mid).glob(f'{side}-client-*-events.jsonl.gz')
        client = [e for e in events(path, {'click', 'science_click', 'engine_attack'}, start, finish + 100)
                  if e.get('match') == mid]
        clicks, _, check = link_clicks(server, client, meta, side)
        if check['errors'] or check['unreceived_tail'] > 2:
            raise ValueError((mid, side, check))
        checks.append(check)
        consumed.append(clicks)

    end_tick = accepted[-1][0]
    readiness = []
    for side, clicks in enumerate(consumed):
        counters = Counter()
        by_tick = {}
        for click in clicks:
            by_tick.setdefault(click['server_attack_tick'], []).append(click)
        # Complete opening drains only. A censored final fresh-to-fresh interval is still observable here.
        for tick, actor, upgrade in accepted:
            opening = tick + threshold
            if upgrade or side != actor or opening >= end_tick:
                continue
            counters['windows'] += 1
            if any(click['outcome'] == 'fresh' for click in by_tick.get(opening, ())):
                counters['fresh_at_opening'] += 1
                continue
            if any(click['outcome'] in ('immune', 'upgrade') for click in by_tick.get(opening, ())):
                counters['contact_at_opening_no_fresh'] += 1
                continue
            # This is a local observation interval ending at the server drain.
            # Arrival before the drain is known only for a linked attack packet.
            early = drains[opening - 1]
            late = drains[opening]
            band = [click for click in clicks if early < click['consumption_monotonic_ns'] < late]
            if any(click['server_attack_tick'] is not None and click['server_attack_tick'] > opening for click in band):
                counters['locally_clicked_in_band_late_contact'] += 1
            elif band and all(click['outcome'] in ('ray_miss', 'cooldown') for click in band):
                counters['locally_clicked_in_band_missed'] += 1
            else:
                counters['other_or_no_click_in_band'] += 1
        readiness.append(dict(counters))
    return dict(id=mid, seat=subject, arm=fixture['arm'], ready=readiness,
                summary=measure(accepted, threshold, end_tick), links=checks)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest', type=Path)
    parser.add_argument('destination', type=Path)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    with args.destination.open('w') as out:
        for request, mid in manifest['matches'].items():
            result = native_row(mid, manifest['plan'][request])
            out.write(json.dumps(result) + '\n')
            out.flush()


if __name__ == '__main__':
    main()
