#!/usr/bin/env python3
"""Read-only native trade/S12/ground-state attribution; never predicts a duel.

Only a server-send -> client-apply FIFO with identical integer S12 payloads is
accepted. Within each client, monotonic event-entry times select the first travel
following application. Wall times only pair near-simultaneous server attacks and
measure broad outcomes. No nearest-position, velocity-residual or timing fit.
"""
import argparse
import collections
import gzip
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def read(match, side):
    path = ROOT / 'research' / 'traces' / f'{match}-{side}.jsonl.gz'
    data = path.read_bytes()
    return [json.loads(line) for line in gzip.decompress(data).decode().splitlines()], hashlib.sha256(data).hexdigest()


def payload(packet):
    return (int(packet['entity_id']), *(int(packet[k]) for k in ('motionX', 'motionY', 'motionZ')))


def fresh(event):
    return (event['type'] == 'science_server_attack'
            and event['score_before'] != event['score_after']
            and event['target_before']['hurtResistantTime'] <= 10)


def compact_state(state):
    return {k: state[k] for k in ('posX', 'posY', 'posZ', 'motionX', 'motionY', 'motionZ', 'ground', 'sprint')}


def link_side(server, native, game, side):
    """Map exact server send log indices to client apply/travel; fail closed."""
    player = game[side]
    sends = [(i, e) for i, e in enumerate(server)
             if e['type'] == 'replication_send' and e['time'] >= game['start']
             and e['recipient'] == player and e.get('target') == player
             and e['packet']['kind'] == 'hm']
    ids = {e['packet']['entity_id'] for _, e in sends}
    applies = [e for e in native if e['type'] == 'replication_apply_after'
               and e['packet']['kind'] == 'hm' and e['packet']['entity_id'] in ids]
    before = {e['packet_id']: e for e in native if e['type'] == 'replication_apply_before'
              and e['packet']['kind'] == 'hm' and e['packet']['entity_id'] in ids}
    movements = sorted((e for e in native if e['type'] == 'science_move'), key=lambda e: e['monotonic_ns'])
    clicks = [e for e in native if e['type'] == 'science_click']
    contacts = [e for e in native if e['type'] == 'engine_attack' and 'monotonic_ns' in e]
    metadata = [e for e in native if e['type'] == 'replication_apply_after'
                and e['packet']['kind'] == 'hk' and e['packet']['entity_id'] in ids]
    metadata_before = {e['packet_id']: e for e in native if e['type'] == 'replication_apply_before'
                       and e['packet']['kind'] == 'hk' and e['packet']['entity_id'] in ids}
    issues = []
    if len(ids) != 1:
        issues.append('own_entity_id_not_unique')
    if len(applies) > len(sends):
        issues.append('more_client_applies_than_server_sends')
    mismatch = next((i for i, ((_, s), a) in enumerate(zip(sends, applies))
                     if payload(s['packet']) != payload(a['packet'])), None)
    if mismatch is not None:
        issues.append(f'fifo_integer_payload_mismatch_at_{mismatch}')
    # Unapplied terminal packets are explicit; they do not invalidate earlier FIFO.
    diagnostics = {'sends': len(sends), 'applies': len(applies),
                   'unapplied_tail': max(0, len(sends)-len(applies)), 'issues': issues}
    if issues:
        return {}, diagnostics
    linked = {}
    for (idx, send), app in zip(sends, applies):
        flags = []
        pre = before.get(app['packet_id'])
        if pre is None or payload(pre['packet']) != payload(app['packet']):
            flags.append('missing_or_mismatched_apply_before')
        ns = app['monotonic_ns']
        moves = [m for m in movements if m['monotonic_ns'] > ns]
        move = moves[0] if moves else None
        if any(m['monotonic_ns'] == ns for m in movements):
            flags.append('apply_travel_timestamp_tie')
        if move is None:
            flags.append('no_following_travel')
        elif any(ns < a['monotonic_ns'] < move['monotonic_ns'] for a in applies):
            flags.append('overwritten_by_another_S12_before_travel')
        motion = [app['state'][k] for k in ('motionX', 'motionY', 'motionZ')]
        expected = [v/8000 for v in payload(app['packet'])[1:]]
        if motion != expected:
            flags.append('apply_motion_not_exact_payload')
        if not -5 <= app['time'] - send['time'] <= 1000:
            flags.append('implausible_wall_send_apply_delay')
        event = {'flags': flags, 'server_send_index': idx,
                 'server_send_ms': send['time']-game['start'],
                 'apply_ms': app['time']-game['start'], 'packet_id': app['packet_id'],
                 'integer_payload': list(payload(app['packet'])),
                 'apply_before': None if pre is None else compact_state(pre['state']),
                 'apply_after': compact_state(app['state'])}
        if move:
            event.update(first_travel_ms=move['time']-game['start'],
                         first_travel_tick=move['tick'], first_travel=compact_state(move['before']),
                         first_travel_after=compact_state(move['after']),
                         forward=move['forward'], ground_acceleration=move['ground_acceleration'],
                         air_acceleration=move['air_acceleration'], floor_slipperiness=move.get('floor_slipperiness'),
                         local_clicks_between_apply_and_travel=[
                             {'time_ms': c['time']-game['start'], 'selected_target': c['selected_target'],
                              'cooldown': c['miss_cooldown_before'], 'local_sprint': c['own']['sprint']}
                             for c in clicks if ns < c['monotonic_ns'] < move['monotonic_ns']])
            event['local_contacts_between_apply_and_travel'] = [
                {k: c[k] for k in ('time', 'vx_before', 'vz_before', 'vx_after', 'vz_after', 'sprint_before', 'sprint_after')}
                for c in contacts if ns < c['monotonic_ns'] < move['monotonic_ns']]
            event['own_metadata_between_apply_and_travel'] = [
                {'time_ms': e['time']-game['start'], 'packet_id': e['packet_id'],
                 'sprint_before': metadata_before[e['packet_id']]['state']['sprint'] if e['packet_id'] in metadata_before else None,
                 'sprint_after': e['state']['sprint']}
                for e in metadata if ns < e['monotonic_ns'] < move['monotonic_ns']]
            if any(move.get(k) for k in ('water', 'lava', 'ladder')):
                flags.append('nonordinary_travel')
            if move['before']['posY'] < 4-1e-8:
                flags.append('below_declared_arena_plane')
        linked[idx] = event
    return linked, diagnostics


def attach_hit(server, attack_index, hit, links):
    """The constructor/send are nested inside this attack's before/after interval."""
    velocity = hit.get('velocity_packet')
    if velocity is None:
        return {'flags': ['fresh_attack_has_no_recorded_S12']}
    created = [(i, e) for i, e in enumerate(server[:attack_index])
               if e['type'] == 'science_velocity_created' and e.get('attack_time') == hit['time']
               and e['target'] == hit['target'] and e['velocity'] == velocity]
    if len(created) != 1:
        return {'flags': ['ambiguous_attack_velocity_constructor'], 'candidates': len(created)}
    ci, creation = created[0]
    key = (int(creation['entity_id']), *(round(v*8000) for v in velocity))
    sends = [i for i in range(ci+1, attack_index)
             if server[i]['type'] == 'replication_send'
             and server[i]['recipient'] == hit['target'] and server[i].get('target') == hit['target']
             and server[i]['packet']['kind'] == 'hm' and payload(server[i]['packet']) == key]
    if len(sends) != 1:
        return {'flags': ['ambiguous_attack_velocity_send'], 'candidates': len(sends)}
    return links.get(sends[0], {'flags': ['send_has_no_valid_fifo_application']})


def outcome(next_a, next_b, end, start, margin, horizon):
    if next_a is None or next_a['time']-start > horizon:
        return 'right_censored' if end-start < horizon else 'no_A_repeat_within_horizon'
    if end < next_a['time']+margin:
        return 'right_censored'
    if next_b is None:
        return 'A_double_with_denial'
    if next_b.get('server_tick') == next_a.get('server_tick'):
        return 'prompt_return'
    delta = next_b['time']-next_a['time']
    if delta > margin:
        return 'A_double_with_denial'
    if delta >= -margin:
        return 'prompt_return'
    return 'B_returns_first'


def analyze(match, pair_ms, reply_ms, horizon_ms):
    native, hashes = {}, {}
    for side in ('a', 'b', 'server'):
        native[side], hashes[side] = read(match, side)
    server = native['server']
    game = next(e['match'] for e in server if e['type'] == 'start')
    finish = next((e['result']['finish'] for e in server if e['type'] == 'finish'), None)
    if finish is None:
        raise ValueError(f'{match} has no completed finish')
    links, diagnostics = {}, {}
    for side in ('a', 'b'):
        links[side], diagnostics[side] = link_side(server, native[side], game, side)
    hits = [(i, e) for i, e in enumerate(server) if fresh(e)]
    ah = [(i, e) for i, e in hits if e['attacker'] == game['a']]
    bh = [(i, e) for i, e in hits if e['attacker'] == game['b']]
    pairs = []
    for ai, a in ah:
        candidates = [(bi, b) for bi, b in bh if abs(b['time']-a['time']) <= pair_ms]
        if len(candidates) != 1:
            continue
        bi, b = candidates[0]
        if sum(abs(aa['time']-b['time']) <= pair_ms for _, aa in ah) != 1:
            continue
        aa = attach_hit(server, bi, b, links['a'])
        bb = attach_hit(server, ai, a, links['b'])
        after = max(ai, bi)
        na = next((e for i, e in ah if i > after), None)
        nb = next((e for i, e in bh if i > after), None)
        start = max(a['time'], b['time'])
        flags = ['A:'+x for x in aa['flags']] + ['B:'+x for x in bb['flags']]
        state = 'unresolved' if flags else ('ground' if aa['first_travel']['ground'] else 'air')+'/' + ('ground' if bb['first_travel']['ground'] else 'air')
        row = {'A_fresh_ms': a['time']-game['start'], 'B_fresh_ms': b['time']-game['start'],
               'server_tick_delta_B_minus_A': b['server_tick']-a['server_tick'],
               'A_sprint': a['attacker_before']['sprint'], 'B_sprint': b['attacker_before']['sprint'],
               'state_A_B': state, 'flags': flags, 'A_receives': aa, 'B_receives': bb,
               'next_A_ms': None if na is None else na['time']-game['start'],
               'next_B_ms': None if nb is None else nb['time']-game['start'],
               'outcome': outcome(na, nb, finish, start, reply_ms, horizon_ms),
               'reply_margin_sensitivity': {str(m): outcome(na, nb, finish, start, m, horizon_ms) for m in (0, 50, 100, 150)}}
        for side in ('a', 'b'):
            endpoint = min(finish, start+horizon_ms, na['time']+reply_ms if na else finish)
            clicks = [c for c in native[side] if c['type'] == 'science_click' and start <= c['time'] <= endpoint]
            row[side+'_clicks_after_trade'] = [
                {'time_ms': c['time']-game['start'], 'selected_target': c['selected_target'],
                 'cooldown': c['miss_cooldown_before'], 'own_y': c['own']['posY'],
                 'remote_y': c['target']['posY'],
                 'horizontal_gap': ((c['own']['posX']-c['target']['posX'])**2+(c['own']['posZ']-c['target']['posZ'])**2)**.5}
                for c in clicks]
        pairs.append(row)
    return {'match': match, 'policy_a': game['policy_a'], 'policy_b': game['policy_b'],
            'rtt': [game['ping_a'], game['ping_b']], 'arena_surface': game.get('arena_surface', 'legacy_unspecified'),
            'trace_sha256': hashes, 'fifo_diagnostics': diagnostics, 'pairs': pairs}


def aggregate(results):
    groups = collections.defaultdict(collections.Counter)
    for result in results:
        for pair in result['pairs']:
            # Old arena and repaired arena remain separate; invalid links never enter valid bins.
            for policy in ('ALL', result['policy_a']):
                key = '|'.join((result['arena_surface'], policy, pair['state_A_B']))
                groups[key][pair['outcome']] += 1
                groups[key]['total'] += 1
    return {k: dict(v) for k, v in sorted(groups.items())}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--matches', nargs='+')
    parser.add_argument('--pair-ms', type=int, default=75)
    parser.add_argument('--reply-ms', type=int, default=150)
    parser.add_argument('--horizon-ms', type=int, default=850)
    parser.add_argument('--output', type=Path, default=ROOT/'research/trade-grounding.json')
    args = parser.parse_args()
    if args.matches:
        matches = args.matches
    else:
        suite = json.loads((ROOT/'research/trade-reset-interventions-v2.json').read_text())
        matches = ['m0389', 'm0390', 'm0391'] + [r['game']['id'] for r in suite['results']]
    results = [analyze(m, args.pair_ms, args.reply_ms, args.horizon_ms) for m in matches]
    report = {'description': 'Observed association, not a causal estimate or a full-model validation.',
              'script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'definitions': {'trade_pair': f'Unique opposite fresh sword attacks within {args.pair_ms} wall ms; server order/ticks retained.',
                  'fresh': 'Score increments with victim hurtResistantTime<=10. Upgrades excluded.',
                  'A_double_with_denial': f'After the paired trade, A repeats within {args.horizon_ms}ms and B has no fresh reply through A-repeat+{args.reply_ms}ms. Same-server-tick replies never count as denial.',
                  'ground_state': 'onGround at entry of the first native travel after matched S12 application; all intervening S12 overwrites flagged.',
                  'ordering': 'Exact integer-payload server-send/client-apply FIFO; per-client monotonic timestamps. No cross-JVM nanoTime comparisons.',
                  'limitations': 'Posthoc observational association; phases, range, sprint impulse, initial geometry and local contact drag remain confounders. Old and repaired arenas are not pooled.'},
              'parameters': {'pair_ms': args.pair_ms, 'reply_ms': args.reply_ms, 'horizon_ms': args.horizon_ms},
              'aggregate': aggregate(results), 'results': results}
    args.output.write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps({'output': str(args.output), 'matches': len(results), 'aggregate': report['aggregate']}, indent=2))


if __name__ == '__main__':
    main()
