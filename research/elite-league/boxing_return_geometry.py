"""Trace reach, contact and attack processing around paired boxing trades."""

import argparse
from bisect import bisect_left, bisect_right
import json
import math
from pathlib import Path
import subprocess

from boxing_upgrade_audit import ROOT, events
from tools.trade_return_windows import F32, attack_kind, box_distance, link_clicks


def trade_windows(hits, drains, finish_tick, threshold):
    """Pair nearby opposing fresh hits once and score each player's next two eligible drains."""
    pending = None
    for tick, side, upgrade in hits:
        if upgrade:
            continue
        if pending is None or pending[1] == side or tick - pending[0] > 3:
            pending = tick, side
            continue
        first_ticks = [None, None]
        first_ticks[side], first_ticks[pending[1]] = tick, pending[0]
        pending = None
        last_drain = max(first_ticks) + threshold + 2
        if last_drain <= finish_tick and last_drain in drains:
            yield first_ticks


def geometry(clicks, selected, opening):
    in_reach = sum(c['nearest'] <= 3 for c in clicks)
    ready = [c for c in selected if opening <= c['drain'] < opening + 2]
    return dict(
        count=len(clicks), in_box_reach=in_reach,
        in_actual_box_reach=sum(c['actual_nearest'] is not None and c['actual_nearest'] <= 3 for c in clicks),
        local_contact=sum(c['contact'] for c in clicks), in_window_packet=len(ready),
        positive_nearest=sum(c['nearest'] <= 3 and not c['contact'] and c['eligible'] for c in clicks),
        nearest=min((c['nearest'] for c in clicks), default=None),
        actual_nearest=min((c['actual_nearest'] for c in clicks if c['actual_nearest'] is not None), default=None),
        samples=clicks)


def summarize(hits, drains, clicks, threshold, finish_tick, leg_ms=10.35):
    score = [{tick for tick, actor, upgrade in hits if actor == side and not upgrade} for side in (0, 1)]
    by_side = [[c for c in clicks if c['side'] == side] for side in (0, 1)]
    timestamps = [[c['ms'] for c in c_side] for c_side in by_side]
    selected = [[c for c in seq if c['drain'] is not None] for seq in by_side]
    windows = []
    for first_ticks in trade_windows(hits, drains, finish_tick, threshold):
        row = dict(trade=first_ticks, sides=[])
        for side, tick in enumerate(first_ticks):
            opening = tick + threshold
            # This is a local timing bracket. Exact arrival is used only for linked C02s.
            lo, hi = drains[opening] - leg_ms, drains[opening + 2] - leg_ms
            start = bisect_left(timestamps[side], lo)
            end = bisect_left(timestamps[side], hi)
            g = geometry(by_side[side][start:end], selected[side], opening)
            g['next_fresh_on_time'] = opening in score[side] or opening + 1 in score[side]
            row['sides'].append(g)
        windows.append(row)
    return windows


def relocated_distance(own, target_box, old_position, new_position):
    shift = [new - old for old, new in zip(old_position, new_position)]
    eye = [own['posX'], own['posY'] + F32(1.62), own['posZ']]
    border = F32(.1)
    return math.sqrt(sum(max(target_box[i] + shift[i] - border - eye[i],
                             eye[i] - (target_box[i + 3] + shift[i] + border), 0) ** 2 for i in range(3)))


def native_row(mid, fixture, ray_command):
    meta = json.loads((ROOT / 'runtime/results' / f'{mid}.json').read_text())
    start, finish = meta['start'], meta['finish']
    sides = [fixture['side'], 'b' if fixture['side'] == 'a' else 'a']
    players = [meta[side] for side in sides]
    server = list(events(ROOT / 'runtime/replays' / f'{mid}.jsonl',
                         {'replication_server_input', 'science_server_attack', 'replication_server_clock'},
                         start, finish + 100))
    drains = {e['server_tick']: e['monotonic_ns'] / 1e6 for e in server
              if e['type'] == 'replication_server_clock' and e['phase'] == 'drain_start'}
    hits, impulses = [], []
    for event in server:
        if event['type'] != 'science_server_attack' or event['attacker'] not in players:
            continue
        kind = attack_kind(event, meta)
        if kind not in ('fresh', 'upgrade'):
            continue
        tick = event['server_tick']
        side = players.index(event['attacker'])
        hits.append((tick, side, kind == 'upgrade'))
        if kind == 'fresh':
            before = event['target_before']
            impulses.append((tick, side, *event['velocity_packet'], before['posY'], before['ground']))
    assert len(hits) == meta['hits_a'] + meta['hits_b']
    captures = []
    for side in sides:
        path, = (ROOT / 'runtime/pov' / mid).glob(f'{side}-client-*-events.jsonl.gz')
        captures.append([e for e in events(path, {'click', 'science_click', 'engine_attack', 'science_move'},
                                            start, finish + 100) if e.get('match') == mid])
    move_times = [[e['monotonic_ns'] for e in c if e['type'] == 'science_move'] for c in captures]
    move_poses = [[e['after'] for e in c if e['type'] == 'science_move'] for c in captures]
    clicks, parity_input = [], []
    for side, cap in enumerate(captures):
        linked, _, check = link_clicks(server, cap, meta, sides[side])
        if check['errors'] or check['unreceived_tail'] > 2:
            raise ValueError((mid, side, check))
        for fact, e in zip(linked, (e for e in cap if e['type'] == 'science_click'), strict=True):
            own, view = e['own'], e['target']
            if own['sneaking'] or view['sneaking']:
                raise ValueError('Unexpected crouching client in W-tap ray audit')
            actual_index = bisect_right(move_times[1 - side], e['monotonic_ns']) - 1
            our_pos = [own[f'pos{axis}'] for axis in 'XYZ']
            target_pos = [view[f'pos{axis}'] for axis in 'XYZ']
            actual_pos = None if actual_index < 0 else [move_poses[1 - side][actual_index][f'pos{axis}'] for axis in 'XYZ']
            actual_nearest = None if actual_pos is None else relocated_distance(own, view['box'], target_pos, actual_pos)
            eligible = e['miss_cooldown_before'] <= 0
            parity_input.append((*our_pos, *target_pos, own['rotationPitch'], own['rotationYawHead'], own['rotationYaw']))
            clicks.append(dict(side=side, ms=e['monotonic_ns'] / 1e6, drain=fact['server_attack_tick'],
                               outcome=fact['outcome'], nearest=box_distance(e), actual_nearest=actual_nearest,
                               eligible=eligible, contact=e['selected_target'] and eligible,
                               selected=e['selected_target'], ray=fact.get('native_cached_ray_distance'),
                               own=our_pos, view=target_pos, actual=actual_pos))
    parity = subprocess.run(ray_command, text=True, capture_output=True, check=True,
                            input=''.join(' '.join(map(str, fields)) + '\n' for fields in parity_input))
    for click, output in zip(clicks, parity.stdout.splitlines(), strict=True):
        head, body = map(float, output.split())
        click['head_ray'] = head if math.isfinite(head) else None
        click['body_ray'] = body if math.isfinite(body) else None
    checks = []
    for side in (0, 1):
        cs = [click for click in clicks if click['side'] == side and click['eligible']]
        checks.append(dict(n=len(cs), selected=sum(c['selected'] for c in cs),
                           head_wrong=sum((c['head_ray'] is not None and c['head_ray'] <= 3) != c['selected'] for c in cs),
                           body_wrong=sum((c['body_ray'] is not None and c['body_ray'] <= 3) != c['selected'] for c in cs)))
    clicks.sort(key=lambda c: c['ms'])
    variants = {leg: summarize(hits, drains, clicks, meta['combat_profile']['fresh_hit_ticks'], hits[-1][0], leg)
                for leg in (5, 10.35, 15)}
    return dict(id=mid, arm=fixture['arm'], seat=sides[0], ray_parity=checks, variants=variants, impulses=impulses)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('classpath')
    args = parser.parse_args()
    batch = json.loads(args.manifest.read_text())
    with args.output.open('w') as out:
        for request, mid in batch['matches'].items():
            row = native_row(mid, batch['plan'][request], ['java', '-cp', args.classpath, 'BoxingRayParity'])
            out.write(json.dumps(row) + '\n')
            out.flush()


if __name__ == '__main__':
    main()
