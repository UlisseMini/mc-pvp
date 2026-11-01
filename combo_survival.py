"""Offline combo diagnostics. Actual aim misses are not geometric reach denial."""
import collections
import math

from native_windows import overlap, sustained_combo
from science import client_events, server_events


def geometric_distance(probe):
    eye, box = probe.get('native_eye'), probe.get('expanded_box')
    if eye is None or box is None or len(eye) != 3 or len(box) != 6:
        return None
    return math.sqrt(sum(max(box[i] - eye[i], 0, eye[i] - box[i + 3]) ** 2 for i in range(3)))


def episodes(hits, player, finish):
    """Runs of fresh hits; every accepted opponent sword hit ends a run.

    An own damage upgrade doesn't add a fresh contact. Terminal runs are right
    censored: winning a race to 100 is not evidence the opponent could escape.
    """
    result, chain = [], []
    for hit in hits:
        if hit.get('rod'):
            continue
        if hit['attacker'] != player:
            if chain:
                result.append({'fresh_hits': len(chain), 'first': chain[0]['time'],
                               'last': chain[-1]['time'], 'return': hit['time'],
                               'duration_ms': hit['time'] - chain[0]['time'], 'censored': False})
                chain = []
        elif not hit.get('damage_upgrade', hit.get('target_state', {}).get('hurtResistantTime', 0) != 20):
            chain.append(hit)
    if chain:
        result.append({'fresh_hits': len(chain), 'first': chain[0]['time'], 'last': chain[-1]['time'],
                       'return': None, 'duration_ms': finish - chain[0]['time'], 'censored': True})
    return result


def analyze(game):
    hits = [e for e in server_events(game['id']) if e['type'] == 'hit']
    probes = {s: [e for e in client_events(game, s) if e['type'] == 'reach_probe'] for s in ('a', 'b')}
    geometric, metrics = {}, {}
    for side in ('a', 'b'):
        rows, aim_misses, available = [], 0, 0
        for p in probes[side]:
            distance = geometric_distance(p)
            if distance is None:
                continue
            within = distance <= float(p.get('attack_reach', 3))
            rows.append({'time': p['time'], 'in_range': within})
            available += within
            aim_misses += within and not p['in_range']
        geometric[side] = rows
        runs = episodes(hits, game[side], game['finish'])
        metrics[side] = {'episodes': runs,
                         'fresh_run_histogram': dict(collections.Counter(e['fresh_hits'] for e in runs)),
                         'longest_fresh_run': max((e['fresh_hits'] for e in runs), default=0),
                         'longest_no_reply_ms': max((e['duration_ms'] for e in runs), default=0),
                         'censored_terminal_runs': sum(e['censored'] for e in runs),
                         'in_geometric_range_samples': available,
                         'aim_miss_samples_while_in_geometric_range': aim_misses,
                         'aim_miss_fraction_while_in_geometric_range': aim_misses / available if available else None}
    geometry = overlap(geometric['a'], geometric['b'], game['start'], game['finish'], include_windows=True)
    geometry['sampling'] = 'Shortest native eye-to-expanded target box distance, flat unobstructed arena; independent of aim. Step interpolation; sample age <=150ms.'
    for side, other in (('a', 'b'), ('b', 'a')):
        contacts = [h['time'] for h in hits if h['attacker'] == game[side] and not h.get('rod') and not h.get('damage_upgrade', False)]
        returns = [h['time'] for h in hits if h['attacker'] == game[other] and not h.get('rod')]
        windows = geometry[side].pop('defender_denial_windows')
        lock = sustained_combo(windows, contacts, returns)
        lock['definition'] = 'At least 3 fresh contacts during continuous geometric defender-reach denial, <=1000ms between contacts, no accepted sword return. Aim misses alone do not count.'
        metrics[side]['geometric_lock'] = lock
    return {'sides': metrics, 'geometric_overlap': geometry,
            'caution': 'Diagnostic associations, not causal explanations. Sampling may miss sub-tick opportunities. In-range aim-miss counts are sample-weighted, not a count of failed physical clicks. No infinite-combo claim from finite/censored runs.'}
