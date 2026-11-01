#!/usr/bin/env python3
"""Read-only historical replay screen; writes only the named research output."""
import collections
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'research/human-combo-inventory-20261101.json'


def main():
    results = [json.loads(p.read_text()) for p in (ROOT / 'runtime/results').glob('*.json')]
    results = sorted((r for r in results if r.get('a') == 'Nomophobia'
                      and 'wtap' in r.get('policy_b', '')), key=lambda r: r['start'])
    audit_path = ROOT / 'experiments/human-duel-audit.json'
    audit = {d['match']: d for d in json.loads(audit_path.read_text()).get('duels', [])}
    games, chains, episodes = [], [], []
    for r in results:
        mid = r['id']
        path = ROOT / 'runtime/replays' / (mid + '.jsonl')
        if not path.exists():
            continue
        hits = []
        with path.open() as f:
            for line in f:
                if '"type":"hit"' in line or '"type": "hit"' in line:
                    e = json.loads(line)
                    if r['start'] <= e['time'] <= r['finish']:
                        hits.append(e)
        game = {'match': mid, 'policy': r['policy_b'], 'mode': r.get('mode'),
                'rtt': [r.get('ping_a'), r.get('ping_b')],
                'reach': [r.get('attack_reach_a'), r.get('attack_reach_b')],
                'modified_reach': r.get('modified_reach', False),
                'rod_knockback': r.get('rod_knockback'),
                'score': [r['hits_a'], r['hits_b']],
                'damage_score': [r.get('damage_hits_a'), r.get('damage_hits_b')],
                'opponent_parameters': r.get('parameters_b'),
                'archive_server': (ROOT / 'research/traces' / (mid + '-server.jsonl.gz')).exists(),
                'archive_client_a': (ROOT / 'research/traces' / (mid + '-a.jsonl.gz')).exists(),
                'archive_client_b': (ROOT / 'research/traces' / (mid + '-b.jsonl.gz')).exists(),
                'pov_files': [p.name for p in (ROOT / 'runtime/pov' / mid).glob('*')],
                'prior_integrity_audit': audit.get(mid),
                'fresh_classification_explicit': sum('damage_upgrade' in e for e in hits),
                'hit_events': len(hits)}
        games.append(game)
        return_ticks = {e['server_tick'] for e in hits
                        if e['attacker'] != 'Nomophobia' and not e.get('rod', False)
                        and 'server_tick' in e}
        return_times = {e['time'] for e in hits
                       if e['attacker'] != 'Nomophobia' and not e.get('rod', False)}
        episode = None
        for e in hits + [{'attacker': None, 'time': r['finish']}]:
            t = e['time']
            if e['attacker'] != 'Nomophobia':
                if episode:
                    episode['end_reason'] = 'match_end_censored' if e['attacker'] is None else 'opponent_contact'
                    episode['return_or_end_ms'] = t - r['start']
                    episodes.append(episode)
                episode = None
                continue
            if episode is None:
                episode = {'match': mid, 'mode': r.get('mode'), 'first_contact_ms': t-r['start'],
                           'first_sword_ms': None, 'last_sword_ms': None,
                           'fresh_sword_hits': 0, 'unknown_sword_hits': 0, 'upgrades': 0,
                           'rods': 0, 'max_fresh_sword_gap_ms': 0}
            if e.get('rod', False):
                episode['rods'] += 1
            elif e.get('damage_upgrade') is True:
                episode['upgrades'] += 1
            else:
                episode['fresh_sword_hits' if 'damage_upgrade' in e else 'unknown_sword_hits'] += 1
                rel = t-r['start']
                if episode['last_sword_ms'] is not None:
                    episode['max_fresh_sword_gap_ms'] = max(episode['max_fresh_sword_gap_ms'], rel-episode['last_sword_ms'])
                if episode['first_sword_ms'] is None:
                    episode['first_sword_ms'] = rel
                episode['last_sword_ms'] = rel
        current = None
        recent_rod = None

        def finish(reason, t):
            nonlocal current
            if current:
                current['end_reason'] = reason
                current['next_return_or_end_ms'] = t - r['start']
                current['duration_first_to_last_ms'] = current['last_ms'] - current['first_ms']
                current['fresh_hits_excluding_shared_return_ticks'] = sum(
                    e['fresh_explicit'] and not e['shared_return_tick_or_timestamp']
                    for e in current['events'])
                chains.append(current)
            current = None

        for e in hits:
            t = e['time']
            if e.get('rod', False):
                if e['attacker'] == 'Nomophobia':
                    recent_rod = t
                    if current:
                        current['rod_contacts_during'] += 1
                else:
                    finish('opponent_rod', t)
                continue
            if e['attacker'] != 'Nomophobia':
                finish('opponent_accepted_sword', t)
                continue
            if e.get('damage_upgrade') is True:
                if current:
                    current['outgoing_upgrades'] += 1
                continue
            if current and t - r['start'] - current['last_ms'] > 1000:
                finish('fresh_contact_gap_over_1000ms', t)
            if current is None:
                current = {'match': mid, 'mode': r.get('mode'), 'policy': r['policy_b'],
                           'first_ms': t - r['start'], 'last_ms': t - r['start'],
                           'fresh_hits': 0, 'unclassified_accepted_hits': 0,
                           'outgoing_upgrades': 0, 'rod_contacts_during': 0,
                           'rod_in_1000ms_before': recent_rod is not None and t - recent_rod <= 1000,
                           'events': []}
            current['last_ms'] = t - r['start']
            current['fresh_hits' if 'damage_upgrade' in e else 'unclassified_accepted_hits'] += 1
            a, b = e.get('attacker_state', {}), e.get('target_state', {})
            current['events'].append({'t_ms': t-r['start'], 'fresh_explicit': 'damage_upgrade' in e,
                                      'server_tick': e.get('server_tick'),
                                      'shared_return_tick_or_timestamp': e.get('server_tick') in return_ticks
                                          or t in return_times,
                                      'sprint': e.get('attacker_sprint'),
                                      'own_y': a.get('posY'), 'target_y': b.get('posY'),
                                      'own_x': a.get('posX'), 'own_z': a.get('posZ'),
                                      'target_x': b.get('posX'), 'target_z': b.get('posZ')})
        finish('match_end_censored', r['finish'])
    chains.sort(key=lambda c: (c['fresh_hits'], c['duration_first_to_last_ms']), reverse=True)
    report = {'definition': 'Consecutive explicit fresh human sword hits, no intervening accepted opponent sword hit (including upgrades), maximum1000ms gap between fresh human sword hits. Rods are counted separately and do not increment sword streaks. Unknown older fresh classification is never inferred from scoreboard.',
              'limitations': 'Finite chains are not continuous reach-denial certificates. Historical integrity varies; check prior audit, configured reach, build, three POV availability, rod contacts, jumping/strafe and timing before transfer to W-only sword bot.',
              'matches': games, 'chains': chains,
              'unbounded_no_return_episodes': sorted(episodes, key=lambda e: e['fresh_sword_hits'], reverse=True)}
    OUT.write_text(json.dumps(report, indent=2) + '\n')
    print('matches', len(games), 'chains', len(chains), 'output', OUT)
    for c in chains[:25]:
        print(c['match'], c['mode'], c['fresh_hits'], c['first_ms'], c['last_ms'],
              'rods', c['rod_contacts_during'], c['rod_in_1000ms_before'], c['end_reason'])


if __name__ == '__main__':
    main()
