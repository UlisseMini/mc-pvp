#!/usr/bin/env python3
"""Fixed-policy native comparisons and the requested opponent-knowledge ablation."""
import argparse
import asyncio
import hashlib
import json
import statistics
import time

from experiments import OUT, ROOT, RUNTIME, batch, league, properties, record
from lab_runtime import match_events

RTTS = [(0, 0), (20, 100), (100, 20)]
PAIRS = [('wtap', 'hold-w'), ('pvp-candidate', 'hold-w'), ('wtap', 'pvp-candidate')]


def perspective(result, bot):
    side = 'a' if result['policy_a'] == bot else 'b'
    assert result['policy_' + side] == bot
    other = 'b' if side == 'a' else 'a'
    return {'match': result['id'], 'side': side,
            'rtt': (result['ping_' + side], result['ping_' + other]),
            'score': (result['hits_' + side], result['hits_' + other]),
            'lock_ms': result['combat_metrics'][side]['sustained_native_combo']['duration_ms']}


def score_text(score):
    return f'{score[0]:g}–{score[1]:g}'


async def comparison_league():
    bots = ['wtap', 'hold-w', 'pvp-candidate']
    manifest = {'started_unix': time.time(), 'command': 'python3 compare_wtap.py league',
                'agent_sha256': hashlib.sha256((ROOT / 'build/lab-agent.jar').read_bytes()).hexdigest(),
                'policies': {bot: properties(ROOT / 'policies' / f'{bot}.properties') for bot in bots},
                'games': 36, 'games_per_pair_and_oriented_rtt': 4,
                'scope': 'All three pairings; 0/0 and20/100 RTT with starting sides and ping assignments swapped, repeated twice. No tuning.'}
    OUT.mkdir(exist_ok=True)
    (OUT / 'wtap-comparison-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    await league(bots, [(0, 0), (20, 100)], repeats=2)
    return summarize_league()


def summarize_league():
    manifest = json.loads((OUT / 'wtap-comparison-manifest.json').read_text())
    league = json.loads((OUT / 'league.json').read_text())
    results = league['results']
    assert len(results) == manifest['games'] == 36
    for result in results:
        assert result['validation']['passed']
        assert result['start'] >= manifest['started_unix'] * 1000
        assert result['parameters'] == {bot: manifest['policies'][bot] for bot in result['parameters']}
        assert all(sha == manifest['agent_sha256'] for sha in result['client_builds'].values())
    groups = []
    lines = ['# W-tap comparison', '',
             '36 fresh native 1.8.9 games, fixed policies. Scores and RTTs are oriented as the first-named bot/opponent. '
             'Each row has four games: two in each starting role. No tuning during evaluation.', '',
             '| First bot / opponent | RTT (ms) | Wins–losses | Mean score | All four scores | Longest strict lock (s) |',
             '|---|---|---:|---:|---|---:|']
    for bot, opponent in PAIRS:
        for rtt in RTTS:
            games = [perspective(r, bot) for r in results
                     if {r['policy_a'], r['policy_b']} == {bot, opponent}]
            games = [g for g in games if g['rtt'] == rtt]
            assert len(games) == 4
            assert sum(g['side'] == 'a' for g in games) == 2
            wins = sum(g['score'][0] > g['score'][1] for g in games)
            losses = sum(g['score'][0] < g['score'][1] for g in games)
            mean = tuple(statistics.mean(g['score'][i] for g in games) for i in (0, 1))
            lock = max(g['lock_ms'] for g in games)
            groups.append({'bot': bot, 'opponent': opponent, 'rtt': rtt, 'wins': wins,
                           'losses': losses, 'mean_score': mean, 'max_lock_ms': lock, 'games': games})
            lines.append(f'| {bot} / {opponent} | {rtt[0]}/{rtt[1]} | {wins}–{losses} | '
                         f'{score_text(mean)} | {", ".join(score_text(g["score"]) for g in games)} | {lock / 1000:.2f} |')
    lines += ['', 'W-tap uses the existing 50 ms post-hit release and 150 ms tactical delay. '
              'The candidate is the unchanged prior search selection, whose model assumes hold-W. '
              'Four games per condition are exploratory; clock phases and scheduling still vary.', '',
              'All games passed native reach, live-target, reaction-delay, click-clock and replication checks.']
    doc = record('wtap-comparison', results, {**manifest, 'groups': groups})
    (OUT / 'WTAP-COMPARISON.md').write_text('\n'.join(lines) + '\n')
    print('\n'.join(lines), flush=True)
    return doc


async def knowledge_ablation():
    original = properties(ROOT / 'policies/pvp-candidate.properties')
    known = properties(ROOT / 'policies/pvp-knows-wtap.properties')
    assert known['opponent_model'] == 'wtap'
    comparable = lambda p: {k: v for k, v in p.items() if k not in ('description', 'opponent_model')}
    assert comparable(original) == comparable(known), 'Only opponent-model knowledge may differ'
    fixtures = []
    for index, (our_ping, their_ping) in enumerate(RTTS):
        variants = ['pvp-knows-wtap', 'pvp-candidate'] if index % 2 == 0 else ['pvp-candidate', 'pvp-knows-wtap']
        for policy in variants:
            fixtures.append((policy, 'wtap', our_ping, their_ping) if index % 2 == 0
                            else ('wtap', policy, their_ping, our_ping))
    results = await batch(fixtures)
    diagnostics = {}
    for result in results:
        bot = 'pvp-knows-wtap' if 'pvp-knows-wtap' in (result['policy_a'], result['policy_b']) else 'pvp-candidate'
        own = perspective(result, bot)
        events_path = RUNTIME / 'bots' / result[own['side']].removeprefix('LabSlot') / 'events.jsonl'
        plans = [e for e in match_events(events_path,result['id']) if e['type'] == 'mpc_plan']
        expected = 'wtap' if bot == 'pvp-knows-wtap' else 'hold-w'
        assert plans and all(p['opponent_model'] == expected for p in plans)
        diagnostics[result['id']] = {'plans': len(plans), 'opponent_model': expected,
                                    'plans_predicting_wtaps': sum(p['predicted_opponent_wtaps'] > 0 for p in plans)}
    record('wtap-opponent-model', results, {
        'scope': 'Exactly three games per assumption, one each at0/0,20/100,100/20ms candidate/opponent RTT. '
                 'Starting side matched within each condition; only opponent_model differs. '
                 'Real opponent is the unchanged wtap policy; no parameter tuning.',
        'diagnostics': diagnostics, 'rtts_candidate_opponent': RTTS})
    lines = ['# Knowing the W-tap opponent', '',
             'Scores are candidate–W-tap. One game per model and ping condition; this is a small diagnostic test.', '',
             '| Candidate / W-tap RTT (ms) | Assumes hold-W | Models W-tap |', '|---|---:|---:|']
    for rtt in RTTS:
        scores = []
        for bot in ('pvp-candidate', 'pvp-knows-wtap'):
            games = [perspective(r, bot) for r in results if bot in (r['policy_a'], r['policy_b'])]
            scores.append(next(g['score'] for g in games if g['rtt'] == rtt))
        lines.append(f'| {rtt[0]}/{rtt[1]} | {score_text(scores[0])} | {score_text(scores[1])} |')
    (OUT / 'WTAP-OPPONENT-MODEL.md').write_text('\n'.join(lines) + '\n')
    print('\n'.join(lines), flush=True)
    return results


async def standstill_controls():
    results = await batch([(bot, 'standstill', 20, 100)
                           for bot in ('hold-w', 'wtap', 'pvp-knows-standstill')])
    checks = {}
    for r in results:
        path = RUNTIME / 'bots' / r['b'].removeprefix('LabSlot') / 'events.jsonl'
        observations = [e for e in match_events(path,r['id']) if e['type'] == 'observation']
        assert observations and all(not e['forward'] and e.get('strafe', 0) == 0 for e in observations)
        span = max(e['x'] for e in observations) - min(e['x'] for e in observations)
        assert span > .1, 'Stationary controls must still allow ordinary knockback movement'
        checks[r['id']] = {'no_movement_keys': True, 'knockback_x_span_m': span,
                          'observation_samples': len(observations)}
    record('standstill-controls', results, {'scope': 'Stationary clicking control;20/100ms attacker/standstill RTT',
                                           'standstill_checks': checks})
    for r in results:
        p = perspective(r, r['policy_a'])
        sequence = r['combat_metrics']['a']['native_denial_contact_sequence']
        print(r['id'], r['policy_a'], score_text(p['score']), 'strict lock seconds', p['lock_ms'] / 1000,
              'denial contact sequence seconds', sequence['duration_ms'] / 1000,
              'fresh contacts', sequence['fresh_contacts'], 'largest gap ms', sequence['max_contact_gap_ms'], flush=True)
    return results


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['league', 'summarize', 'ablation', 'standstill'])
    args = parser.parse_args()
    if args.command == 'summarize':
        summarize_league()
    else:
        asyncio.run(comparison_league() if args.command == 'league' else
                    knowledge_ablation() if args.command == 'ablation' else standstill_controls())
