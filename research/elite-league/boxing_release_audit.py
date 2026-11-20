"""Replay close-release decisions and measure the contacts following each native opportunity."""

import argparse
from bisect import bisect_left
from collections import Counter, defaultdict
import json
from math import hypot
from pathlib import Path

from audit import captures
from run import ROOT


KINDS = {'legacy_head', 'wtap', 'fresh_hurt_status', 'fresh_target_hurt_status',
         'boxing_release_hold', 'boxing_drill_handoff', 'engine_attack', 'science_click', 'science_move'}


def opportunities(events, config, candidate, drill):
    gap = 1100
    reach = float(config.get('boxing_release_hold_reach', 1.6))
    last_own = last_target = own = target = float('-inf')
    release_until = cue = previous_release = float('-inf')
    enabled = not drill
    strikes = 0
    actions = {}
    expected = set()
    heads = []
    for event in events:
        kind = event['type']
        if kind == 'wtap':
            cue = event['cue_time']
            release_until = event['release_until']
        elif kind == 'fresh_hurt_status':
            own = event['time']
        elif kind == 'fresh_target_hurt_status':
            target = event['time']
        elif kind == 'boxing_drill_handoff':
            enabled = True
        elif kind == 'boxing_release_hold':
            actions[event['tick']] = event
        elif kind == 'legacy_head':
            now = event['time']
            if own > last_own:
                last_own = own
                strikes = 0
            if target > last_target:
                if target - last_target > gap:
                    strikes = 0
                last_target = target
                if last_target > last_own:
                    strikes += 1
            continuing = strikes >= 2 and now - last_target <= gap
            stock_forward = now >= release_until
            holds = False
            if not stock_forward and cue > previous_release:
                previous_release = cue
                ground = event['own'][6]
                holds = enabled and not continuing and ground and event['range'] < reach
                heads.append(dict(tick=event['tick'], time=now, sampled_cue=cue,
                                  fresh_target_cue=target if target != float('-inf') else None,
                                  ground=ground, nearest=event['range'], enabled=enabled,
                                  continuing=continuing, eligible=holds, local_sprint=event['own'][7]))
            if candidate and holds:
                expected.add(event['tick'])
            forward = stock_forward or candidate and holds
            if event['keys'][:3] != [forward, forward, 0]:
                raise ValueError(f"Recorded keys disagree with release policy at tick {event['tick']}")
    if expected != actions.keys():
        raise ValueError(f'Logged opportunities differ from the reconstructed rule: {expected ^ actions.keys()}')
    for head in heads:
        if (action := actions.get(head['tick'])) and (action['cue_time'] != head['sampled_cue']
                or action['nearest'] != head['nearest'] or action['time'] != head['time']):
            raise ValueError(f"Hold log disagrees with its movement head: {head['tick']}")
    return heads


def contacts(events):
    click_ticks = {e['sequence']: e['tick'] for e in events if e['type'] == 'science_click'}
    moves = {e['tick']: e for e in events if e['type'] == 'science_move'}
    rows = []
    for event in events:
        if event['type'] != 'engine_attack':
            continue
        tick = click_ticks.get(event.get('click_attempt'))
        if tick is None:
            continue
        dx = event['target_x'] - event['own_x']
        dz = event['target_z'] - event['own_z']
        distance = hypot(dx, dz)
        x0, z0 = event['vx_before'], event['vz_before']
        x1, z1 = event['vx_after'], event['vz_after']
        toward = (x0 * dx + z0 * dz) / distance if distance else 0
        factor = .6 if event['sprint_before'] else 1
        if (x1, z1) != (factor * x0, factor * z0):
            raise ValueError(f'Unexpected motion change on ordinary contact at tick {tick}')
        rows.append(dict(tick=tick, time=event['time'], toward=toward, sprint=event['sprint_before'],
                         recoil_brake=event['sprint_before'] and toward < -.025,
                         before_travel=tick in moves and event['monotonic_ns'] < moves[tick]['monotonic_ns'],
                         ray=event.get('native_raycast_distance')))
    return rows


def audit_match(mid, fixture):
    meta = json.loads((ROOT / 'runtime/results' / f'{mid}.json').read_text())
    if meta['reason'] not in ('100_hits', 'timeout_draw', 'scenario_completed'):
        return dict(mid=mid, reason=meta['reason'])
    side = fixture['side']
    t0, finish = meta['start'], meta['finish']
    candidate = 'release_hold_reach' in fixture
    drill = fixture['match_format'] == 'maintenance'
    parameters = meta[f'parameters_{side}']
    assert (float(parameters['boxing_release_hold_reach']) if 'boxing_release_hold_reach' in parameters else None) == fixture.get('release_hold_reach')
    path, = (ROOT / 'runtime/pov' / mid).glob(f'{side}-client-*-events.jsonl.gz')
    events = [e for e in captures.events(path, KINDS) if e.get('match') == mid]
    heads = opportunities(events, parameters, candidate, drill)
    observed = contacts(events)
    accepted = []
    with (ROOT / 'runtime/replays' / f'{mid}.jsonl').open() as stream:
        for line in stream:
            if '"type":"hit"' in line:
                e = json.loads(line)
                accepted.append((e['time'], e['attacker'] == meta[side], not e['damage_upgrade']))
    times = [at for at, _, _ in accepted]
    paired = []
    by_tick = defaultdict(list)
    for contact in observed:
        by_tick[contact['tick']].append(contact)
    for head in heads:
        now, tick = head['time'], head['tick']
        if not t0 <= now <= finish or not head['eligible'] or now + 850 > finish:
            continue
        nearby = [e for n in range(tick, tick + 4) for e in by_tick[n] if e['time'] - now <= 160]
        short = [e for e in accepted[bisect_left(times, now):] if e[0] <= now + 850]
        unanswered = 0
        for _, own, fresh in short:
            if not own:
                break
            unanswered += fresh
        paired.append(head | dict(at=now-t0,
                                  cue_age=None if head['fresh_target_cue'] is None else now-head['fresh_target_cue'],
                                  contacts=nearby, unanswered=unanswered,
                                  return_ms=next((t-now for t, own, _ in short if not own), None),
                                  margin=sum(1 if own else -1 for _, own, _ in short)))
    counts = Counter('disabled' if not h['enabled'] else 'continuing' if h['continuing'] else
                     'close_grounded' if h['eligible'] else 'other_geometry'
                     for h in heads if t0 <= h['time'] <= finish)
    return dict(mid=mid, reason=meta['reason'], rtt=fixture['rtt'], side=side, candidate=candidate,
                format=fixture['match_format'], releases=counts, eligible=paired,
                last_capture_ms=max(e['time'] for e in events)-t0)


def main():
    parse = argparse.ArgumentParser(description=__doc__)
    parse.add_argument('manifest', type=Path)
    parse.add_argument('destination', type=Path)
    args = parse.parse_args()
    manifest = json.loads(args.manifest.read_text())
    previous = json.loads(args.destination.read_text()) if args.destination.exists() else {}
    result = {}
    for request, mid in manifest['matches'].items():
        if mid in previous:
            result[mid] = previous[mid]
            continue
        result[mid] = audit_match(mid, manifest['plan'][request])
        print(mid, result[mid].get('releases'), flush=True)
    temporary = args.destination.with_suffix('.tmp')
    temporary.write_text(json.dumps(result, indent=2) + '\n')
    temporary.replace(args.destination)


if __name__ == '__main__':
    main()
