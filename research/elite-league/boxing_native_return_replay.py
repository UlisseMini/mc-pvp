"""Connect strict replay witnesses to consumed clicks and native press timing."""

import argparse
from collections import Counter, defaultdict
import json
import math
from pathlib import Path

from boxing_task_stage_audit import capture
from boxing_upgrade_audit import ROOT, events
from tools.trade_return_windows import link_clicks


def press_check(polls):
    """Recompute the native cap, then let an instantaneous cap drift independently."""
    completion = instant = polls[0]['previous_completed_ns']
    counts = Counter()
    first = None
    for p in polls:
        if p['minimum_interval_ns'] != 50_000_000 or p['previous_completed_ns'] != completion:
            raise ValueError('Unexpected native cap or interrupted polling sequence')
        t = p['poll_ns']
        allowed = p['gate_at_poll'] and t - completion >= p['minimum_interval_ns']
        if allowed != p['admitted']:
            raise ValueError('Native admission disagrees with recorded gate and completion time')
        now_allowed = p['gate_at_poll'] and t - instant >= p['minimum_interval_ns']
        counts['polls'] += 1
        counts['native'] += allowed
        counts['instant'] += now_allowed
        if allowed != now_allowed:
            counts['different'] += 1
            if first is None:
                first = dict(ms=(t - polls[0]['poll_ns']) / 1e6, source=p['source'], native=allowed,
                             instant=now_allowed, actual_age_ns=t-completion, instant_age_ns=t-instant)
        if allowed:
            completion = p['completion_ns']
        if now_allowed:
            instant = t
    return dict(counts=counts, first=first)


def windows(row):
    impulse = {(t, side): (math.hypot(x, z), y, ground) for t, side, x, y, z, _, ground in row['impulses']}
    for trade in row['variants']['10.35']:
        a, b = trade['trade']
        full = all(horizontal > .7 and vertical > 0 and ground for horizontal, vertical, ground
                   in [impulse[a, 0], impulse[b, 1]])
        if a < b and full and not trade['sides'][0]['next_fresh_on_time'] and trade['sides'][1]['next_fresh_on_time']:
            yield a, b


def analyze(row, model):
    mid = row['id']
    game = json.loads((ROOT / 'runtime/results' / f'{mid}.json').read_text())
    start, finish = game['start'], game['finish']
    shift = game['arena'] * 4096
    threshold = game['combat_profile']['fresh_hit_ticks']
    designated = row['seat']
    folder = ROOT / 'runtime/pov' / mid
    server = list(events(ROOT / 'runtime/replays' / f'{mid}.jsonl',
                         {'replication_server_input', 'science_server_attack', 'replication_server_clock'},
                         start, finish + 100))
    drains = {e['server_tick']: e['monotonic_ns'] for e in server
              if e['type'] == 'replication_server_clock' and e['phase'] == 'drain_start'}
    inputs, prefix = [], []
    for i, side in enumerate((designated, 'b' if designated == 'a' else 'a')):
        path, = folder.glob(f'{side}-client-*-events.jsonl.gz')
        trace = capture(path, {'native_input_head', 'native_cap_poll', 'click', 'science_click', 'engine_attack'})
        trace = [e for e in trace if e.get('match') == mid and start <= e['time'] <= finish + 100]
        injection_events = [e for e in trace if e['type'] == 'native_cap_poll' and e['time'] < finish]
        presses = press_check(injection_events)
        first_head = next(e for e in trace if e['type'] == 'native_input_head')
        tick0 = first_head['native_tick']
        linked, _, audit = link_clicks(server, trace, game, side)
        if audit['errors'] or audit['unreceived_tail'] > 2:
            raise ValueError((mid, side, audit))
        inputs.append(linked)
        poses = {e['sequence']: e for e in trace if e['type'] == 'science_click'}
        native_by_tick, model_by_tick = defaultdict(list), defaultdict(list)
        for c in linked:
            k = c['native_tick'] - tick0
            if k < 40:
                native_by_tick[k].append(c)
        for c in model['clicks']:
            if c['side'] == i and c['tick'] < 40:
                model_by_tick[c['tick']].append(c)
        earliest, nearest = None, None
        for k in range(40):
            n, g = native_by_tick[k], model_by_tick[k]
            if earliest is None and len(n) != len(g):
                earliest = dict(tick=k, native=len(n), generated=len(g))
            if nearest is None and len(n) == len(g) == 1:
                a, b = n[0], g[0]
                delta = b['nearest'] - a['current_box_min_distance']
                if abs(delta) >= .005:
                    factual = poses[a['sequence']]
                    x_delta = {name: b[which][0] + shift - factual[name]['posX']
                               for name, which in [('own', 'own'), ('target', 'view')]}
                    nearest = dict(tick=k, native=a['current_box_min_distance'], generated=b['nearest'],
                                   delta=delta, x_delta=x_delta)
        prefix.append(dict(side=side, cap=presses, first_consumption_count_difference=earliest,
                           first_common_click_box_difference=nearest, arena_x_shift=shift))

    witnesses = []
    for a, b in windows(row):
        entry = dict(trade=[a, b], trade_ms=(drains[a] - min(drains.values())) / 1e6, sides=[])
        for i, tick in enumerate((a, b)):
            first = tick + threshold
            anchor = drains[first]
            selected = []
            for c in inputs[i]:
                offset = (c['consumption_monotonic_ns'] - anchor) / 1e6
                server_tick = c['server_attack_tick']
                if -80 <= offset <= 180 or server_tick is not None and first <= server_tick <= first+2:
                    selected.append(dict(click_relative_ms=offset, nearest=c['current_box_min_distance'],
                                         selected_ray=c.get('native_cached_ray_distance'), outcome=c['outcome'],
                                         own_y=c['own_y'], remote_y=c['remote_y'],
                                         drain_offset=None if server_tick is None else server_tick-first))
            entry['sides'].append(dict(first_fresh_tick=first, clicks=selected))
        witnesses.append(entry)
    return dict(id=mid, arm=row['arm'], seat=designated, prefix=prefix, witnesses=witnesses)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('native', type=Path)
    parser.add_argument('model', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    with args.model.open() as model:
        branches = {(r['clock'], r['seat']): r for r in map(json.loads, model)}
    with args.native.open() as native, args.output.open('w') as out:
        for row in map(json.loads, native):
            arm = 'jump' if row['arm'] == 'boxing-study-jump' else 'wtap'
            result = analyze(row, branches[row['id'], row['seat']][arm])
            out.write(json.dumps(result) + '\n')
            out.flush()


if __name__ == '__main__':
    main()
