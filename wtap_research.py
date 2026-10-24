#!/usr/bin/env python3
"""Checkpointed native W-tap best-response experiments; no external dependencies."""
import argparse
import asyncio
import hashlib
import json
import random
import re
import statistics
import time

from experiments import OUT, ROOT, match, properties, record, save_policy


def oriented(result, bot):
    side = 'a' if result['policy_a'] == bot else 'b'
    other = 'b' if side == 'a' else 'a'
    metrics = result['combat_metrics'][side]
    return {'match': result['id'], 'side': side,
            'rtt': [result['ping_' + side], result['ping_' + other]],
            'score': [result['hits_' + side], result['hits_' + other]],
            'fresh': [metrics['fresh_hurt_contacts'], result['combat_metrics'][other]['fresh_hurt_contacts']],
            'upgrades': [metrics['damage_upgrades'], result['combat_metrics'][other]['damage_upgrades']],
            'strict_lock_ms': metrics['sustained_native_combo']['duration_ms'],
            'denial_sequence_ms': metrics.get('native_denial_contact_sequence', {}).get('duration_ms', 0)}


def summarize(results, bots):
    groups = []
    for bot in bots:
        games = [oriented(r, bot) for r in results if bot in (r['policy_a'], r['policy_b'])]
        if not games:
            continue
        for rtt in sorted({tuple(g['rtt']) for g in games}):
            subset = [g for g in games if tuple(g['rtt']) == rtt]
            groups.append({'bot': bot, 'rtt': rtt, 'games': len(subset),
                           'wins': sum(g['score'][0] > g['score'][1] for g in subset),
                           'mean_score': [statistics.mean(g['score'][i] for g in subset) for i in (0, 1)],
                           'mean_margin': statistics.mean(g['score'][0] - g['score'][1] for g in subset),
                           'mean_fresh_margin': statistics.mean(g['fresh'][0] - g['fresh'][1] for g in subset),
                           'max_strict_lock_ms': max(g['strict_lock_ms'] for g in subset),
                           'max_denial_sequence_ms': max(g['denial_sequence_ms'] for g in subset),
                           'details': subset})
    return groups


async def run(name, bots, rtts, repeats=1, notes=None):
    if not re.fullmatch('[a-z0-9-]{1,60}', name):
        raise ValueError('Use a short lowercase experiment name')
    path = OUT / f'{name}.json'
    if path.exists():
        raise ValueError(f'{path} exists; use a fresh name to preserve the experiment')
    policies = {bot: properties(ROOT / 'policies' / f'{bot}.properties') for bot in [*bots, 'wtap']}
    fixtures = []
    for repeat in range(repeats):
        for our_ping, their_ping in rtts:
            for bot in bots:
                fixtures += [(bot, 'wtap', our_ping, their_ping), ('wtap', bot, their_ping, our_ping)]
    random.Random(20261023).shuffle(fixtures)
    metadata = {'started_unix': time.time(), 'phase': 'running', 'opponent': 'wtap',
                'policies': policies, 'planned_games': len(fixtures), 'fixtures': fixtures,
                'rtts_candidate_opponent': rtts, 'repeats_per_starting_side': repeats,
                'scope': notes or 'Fixed-policy native tests against unchanged W-tap; starting sides balanced.',
                'agent_sha256': hashlib.sha256((ROOT / 'build/lab-agent.jar').read_bytes()).hexdigest()}
    completed = []
    record(name, completed, metadata)
    semaphore = asyncio.Semaphore(3)

    async def one(fixture):
        async with semaphore:
            if hashlib.sha256((ROOT / 'build/lab-agent.jar').read_bytes()).hexdigest() != metadata['agent_sha256']:
                raise RuntimeError('Build changed during fixed-build experiment')
            for bot in set(fixture[:2]):
                if properties(ROOT / 'policies' / f'{bot}.properties') != policies[bot]:
                    raise RuntimeError('Policy changed during evaluation: ' + bot)
            result = await match(*fixture)
            completed.append(result)
            record(name, completed, {**metadata, 'groups': summarize(completed, bots)})
            return result

    # Complete already running rounds if a fixture fails; preserve every result.
    outcomes = await asyncio.gather(*(one(f) for f in fixtures), return_exceptions=True)
    failures = [str(r) for r in outcomes if isinstance(r, BaseException)]
    metadata.update(phase='failed' if failures else 'complete', failures=failures,
                    groups=summarize(completed, bots))
    document = record(name, completed, metadata)
    lines = [f'# {name}', '', metadata['scope'], '',
             'Scores and RTTs are candidate / W-tap. Selection results are exploratory; fresh tests must be reported separately.', '',
             '| Candidate | Added RTT (ms) | Wins / games | Mean score | Fresh-hit margin | Longest strict lock (s) |',
             '|---|---|---:|---:|---:|---:|']
    for group in metadata['groups']:
        a, b = group['mean_score']
        lines.append(f'| {group["bot"]} | {group["rtt"][0]}/{group["rtt"][1]} | {group["wins"]}/{group["games"]} | '
                     f'{a:g}–{b:g} | {group["mean_fresh_margin"]:+g} | {group["max_strict_lock_ms"]/1000:.2f} |')
    (OUT / f'{name}.md').write_text('\n'.join(lines) + '\n')
    print('\n'.join(lines), flush=True)
    if failures:
        raise RuntimeError('; '.join(failures))
    return document


def model_policies():
    parent = properties(ROOT / 'policies/pvp-knows-wtap.properties')
    changes = {'base': {}, 'defense': {'received_hit_penalty': 1500},
               'aggress': {'received_hit_penalty': 650}, 'fit': {'own_fit_weight': 2},
               'dwell': {'model_min_action_ticks': 2}, 'edge': {'contact_edge_penalty': 200}}
    for suffix, delta in changes.items():
        save_policy('antiwt-m-' + suffix, {**parent, **delta,
                    'description': 'W-tap model screen: ' + (str(delta) if delta else 'unchanged known-opponent reference')})
    return ['antiwt-m-' + s for s in changes]


def hand_policies():
    parent = {'kind': 'momentum', 'click_mode': 'fixed', 'pause_ms': 50,
              'momentum_hysteresis': .12, 'momentum_away_min': 1,
              'momentum_gap': 3.10, 'momentum_lead_ms': 100, 'momentum_incoming_guard_ms': 300}
    changes = {'base': {}, 'delay50': {'reset_delay_ms': 50},
               'late': {'reset_delay_ms': 200, 'reset_rtt_adjusted': 1},
               'phase': {'reset_fresh_phase': 1}, 'walk': {'reset_walk': 1},
               'phasewalk': {'reset_fresh_phase': 1, 'reset_walk': 1}, 'every2': {'reset_every': 2},
               'spacing': {'momentum_spacing': 1}, 'adaptive': {'adaptive_reset': 1},
               'combo': {'momentum_spacing': 1, 'adaptive_reset': 1},
               'strafe350': {'momentum_strafe_ms': 350},
               'strafe700': {'momentum_strafe_ms': 700}, 'opening': {'opening_wait_ms': 150}}
    descriptions = {'base': 'Reference post-hit W-tap', 'delay50': 'Wait50ms longer before resetting sprint',
                    'late': 'Late sprint reset adjusted for added RTT', 'phase': 'Reset before the predicted fresh hit',
                    'walk': 'Keep W held while releasing the sprint key', 'phasewalk': 'Phase-timed sprint-key resets while holding W',
                    'every2': 'Reset sprint on alternating landed-hit cues', 'spacing': 'W-tap with pursuit spacing',
                    'adaptive': 'Skip resets during a predicted safe chase', 'combo': 'Pursuit spacing plus selective reset',
                    'strafe350': 'W-tap with A/D changes every350ms', 'strafe700': 'W-tap with A/D changes every700ms',
                    'opening': 'Delay initial forward movement150ms'}
    for suffix, delta in changes.items():
        save_policy('antiwt-h-' + suffix, {**parent, **delta,
                    'description': descriptions[suffix]})
    return ['antiwt-h-' + s for s in changes]


def model_control_policies():
    parent = properties(ROOT / 'policies/pvp-knows-wtap.properties')
    changes = {'cost25': {'model_switch_penalty': 25}, 'cost100': {'model_switch_penalty': 100},
               'nowalk': {'model_allow_walk': 0},
               'defensecost': {'received_hit_penalty': 1500, 'model_switch_penalty': 25}}
    for suffix, delta in changes.items():
        save_policy('antiwt-m-' + suffix, {**parent, **delta,
                    'description': 'Model controller input-switch experiment: ' + suffix})
    return ['antiwt-m-' + s for s in changes]


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['models', 'hands', 'run'])
    parser.add_argument('--name')
    parser.add_argument('--bots', nargs='+')
    parser.add_argument('--rtts', nargs='+', default=['0,0', '20,100'])
    parser.add_argument('--repeats', type=int, default=1)
    args = parser.parse_args()
    name = args.name or ('antiwt-model-screen' if args.command == 'models' else 'antiwt-hand-screen')
    if args.command == 'run' and not args.name:
        parser.error('Use --name to keep each evaluation separate')
    if (OUT / f'{name}.json').exists():
        parser.error('That report exists; use a fresh --name to preserve it')
    rtts = [tuple(map(int, value.split(','))) for value in args.rtts]
    if any(len(r) != 2 or any(not 0 <= v <= 500 for v in r) for r in rtts) or args.repeats < 1:
        parser.error('Use RTT pairs between0 and500ms and at least one repeat')
    bots = model_policies() if args.command == 'models' else hand_policies() if args.command == 'hands' else args.bots
    if not bots or 'wtap' in bots:
        parser.error('Specify candidate bots distinct from the fixed wtap opponent')
    asyncio.run(run(name, bots, rtts, args.repeats,
                   'One-factor model screen; baseline and five isolated parameter changes. Fixed policies, balanced starting sides; no tuning during games.'
                   if args.command == 'models' else None))
