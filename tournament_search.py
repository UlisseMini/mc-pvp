#!/usr/bin/env python3
"""Small, resumable native boxing tournaments with frozen policies and timing gates.

Selection and confirmation are separate cohorts. A fixture is a full first-to-100
round, never a simulator score. Match clocks are not seedable; the seed shuffles
fixture order only. Native input, physics, miss clicks and reach remain unchanged.
"""
import argparse
import asyncio
import hashlib
import json
import random
import re
import signal
import statistics
import time
from pathlib import Path

from experiments import ROOT, match, save_policy
from lab_runtime import properties
from burst_science import diagnostic
from parity import archive
from combo_survival import analyze as combo_analysis

OUT = ROOT / 'experiments'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def checkpoint(path, document):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(document, indent=2) + '\n')
    temporary.replace(path)


def fixtures(bots, opponent, rtts, repeats, seed):
    rows = []
    for repeat in range(repeats):
        block = []
        for bot in bots:
            for own, other in rtts:
                for reverse in (False, True):
                    block.append(dict(candidate=bot, opponent=opponent, rtt=[own, other],
                                      reverse=reverse, repeat=repeat))
        random.Random(seed + repeat).shuffle(block)
        rows.extend(block)
    for i, row in enumerate(rows):
        row['fixture_id'] = i
    return rows


def summarize(document):
    groups = []
    for bot in document['candidates']:
        rows = [r for r in document['results'] if r['fixture']['candidate'] == bot and r['quality']['passed']]
        margins = [r['score'][0] - r['score'][1] for r in rows]
        def by_ping():
            for rtt in document['rtts']:
                subset = [r for r in rows if r['fixture']['rtt'] == rtt]
                yield {'rtt': rtt, 'n': len(subset), 'wins': sum(r['score'][0] > r['score'][1] for r in subset),
                       'mean_margin': statistics.mean(r['score'][0] - r['score'][1] for r in subset) if subset else None}
        groups.append({'candidate': bot, 'n': len(rows),
                       'wins': sum(m > 0 for m in margins),
                       'mean_score': [statistics.mean(r['score'][s] for r in rows) for s in (0, 1)] if rows else None,
                       'mean_margin': statistics.mean(margins) if rows else None,
                       'margin_range': [min(margins), max(margins)] if margins else None,
                       'mean_fresh_margin': statistics.mean(r['fresh'][0] - r['fresh'][1] for r in rows) if rows else None,
                       'max_unanswered': max((r['longest_streak'][0] for r in rows), default=0),
                       'max_combo_ms': max((r['combo_ms'][0] for r in rows), default=0),
                       'by_ping': list(by_ping()), 'matches': [r['game']['id'] for r in rows]})
    return groups


def prepare(name, candidates, opponent, rtts, repeats=1, seed=20261101, concurrency=2, stage='screen'):
    if not re.fullmatch('[a-z0-9-]{1,60}', name):
        raise ValueError('Use a short lowercase cohort name')
    path = OUT / (name + '.json')
    if path.exists():
        raise ValueError('Cohort already exists; run it to resume or use a new name')
    if not 1 <= concurrency <= 2 or not 1 <= repeats <= 20 or not candidates:
        raise ValueError('Use 1–2 parallel matches, 1–20 repeats, and at least one candidate')
    if any(not 0 <= v <= 500 for pair in rtts for v in pair) or any(len(pair) != 2 for pair in rtts):
        raise ValueError('RTTs must be pairs in 0–500ms')
    frozen = {}
    for bot in set([*candidates, opponent]):
        p = properties(ROOT / 'policies' / (bot + '.properties'))
        if p.get('science_trace') != '1' or p.get('science_network_trace') != '1':
            raise ValueError(bot + ': enable native science and network tracing')
        if float(p.get('attack_reach', 3)) != 3 or p.get('click_mode') == 'none':
            raise ValueError('Competitive search requires clicking opponents and native 3.0 reach')
        if not 1 <= int(p.get('click_cps', 10)) <= 30:
            raise ValueError('Invalid click ceiling')
        frozen[bot] = p
    if len({int(p.get('click_cps', 10)) for p in frozen.values()}) != 1:
        raise ValueError('Equal-ceiling leagues only; do not conflate CPS with algorithm gains')
    document = dict(name=name, created=time.time(), state='prepared', stage=stage,
                    candidates=candidates, opponent=opponent, rtts=rtts, repeats_per_side=repeats,
                    concurrency=concurrency, fixture_order_seed=seed, policies=frozen,
                    agent_sha256=sha(ROOT / 'build/lab-agent.jar'),
                    sources={p: sha(ROOT / p) for p in ['tournament_search.py', 'experiments.py', 'burst_science.py', 'combo_survival.py', 'native_windows.py']},
                    frame_sleep_ms=json.loads((ROOT / 'runtime/manager.json').read_text()).get('frame_sleep_ms'),
                    primary_metric='First-to-100 accepted native sword-hit margin; fresh hits and upgrades separate.',
                    quality_gate='Existing native input/reach/replication checks plus server/client movement p95<=80ms, max<=250ms, <=1% intervals>100ms.',
                    selection='Rank screening only; freeze selected settings before independent confirmation. Never select on one match.',
                    limits='Order seed does not seed JVM clocks. These rounds are independent, not paired deterministic counterfactuals. No server/opponent-private state drives policies.',
                    fixtures=fixtures(candidates, opponent, rtts, repeats, seed), results=[], failures=[])
    checkpoint(path, document)
    return path


def initial_policies(cps=20, aim_delay=150, aim_noise=2, aim_rate=360, tap_delay=150):
    """Small hypothesis screen with explicit control/ablation relationships."""
    trace = {'science_trace': 1, 'science_network_trace': 1, 'click_cps': cps, 'attack_reach': 3.0}
    if not 1 <= cps <= 30 or not 0 <= aim_delay <= 500 or not 0 <= aim_noise <= 10 or not (aim_rate == 0 or 30 <= aim_rate <= 1440) or not 0 <= tap_delay <= 1000:
        raise ValueError('Invalid ceiling or target tracking settings')
    baseline = {**properties(ROOT / 'policies/wtap.properties'), **trace,
                'aim_delay_ms': aim_delay, 'aim_noise_deg': aim_noise,
                'aim_turn_rate_dps': aim_rate, 'reaction_ms': tap_delay}
    save_policy('search-wtap', {**baseline, 'description': 'Frozen existing W-tap with configurable tracking, reach 3; research reference'})
    save_policy('search-control', {**baseline, 'description': 'Identical W-tap clone; measures phase/side variance'})
    save_policy('search-perfect', {**baseline, 'aim_delay_ms': 0, 'aim_noise_deg': 0,
                                  'aim_turn_rate_dps': 0, 'description': 'Control: only tracking changed to perfect; same W-tap delay'})
    tick = dict(kind='momentum', click_mode='fixed', reaction_ms=0,
                reset_delay_ms=150, pause_ms=50, pause_ticks=1, **trace)
    variants = {
        'search-tick': ({}, 'Control: one native movement-tick release, 150ms after visible hit'),
        'search-immediate': ({'reset_delay_ms': 0}, 'One factor: reset immediately instead of 150ms later'),
        'search-longtap': ({'pause_ticks': 2}, 'One factor: release for two movement ticks instead of one'),
        'search-spacing': ({'momentum_spacing': 1, 'momentum_gap': 3.1,
                            'momentum_hysteresis': .12, 'momentum_incoming_guard_ms': 150},
                           'Predictive coasting brake during pursuit; otherwise same one-tick reset'),
        'search-burst': ({'click_mode': 'motion-burst', 'burst_window_lead_ms': 50,
                          'burst_recoil': 1, 'burst_critical': 1},
                         'Existing local recoil/window/critical click gate; preserves all issued native clicks'),
    }
    for name, (delta, description) in variants.items():
        save_policy(name, {**tick, **delta, 'description': description})
    return ['search-control', 'search-perfect', *variants]


async def run(path):
    document = json.loads(path.read_text())
    if document['state'] == 'complete':
        print('Already complete:', path)
        return
    def frozen():
        if sha(ROOT / 'build/lab-agent.jar') != document['agent_sha256']:
            raise RuntimeError('Build changed; create a new cohort')
        for source, digest in document['sources'].items():
            if sha(ROOT / source) != digest:
                raise RuntimeError('Analysis/scheduler source changed: ' + source)
        for bot, policy in document['policies'].items():
            if properties(ROOT / 'policies' / (bot + '.properties')) != policy:
                raise RuntimeError('Policy changed: ' + bot)
    frozen()
    finished = {r['fixture']['fixture_id'] for r in document['results']}
    # A completed but timing-excluded fixture stays excluded; resampling needs a
    # new, declared cohort rather than quietly replacing inconvenient scores.
    pending = [f for f in document['fixtures'] if f['fixture_id'] not in finished]
    document['state'] = 'running'
    checkpoint(path, document)
    stop_requested = False
    def stop_after_batch():
        nonlocal stop_requested
        stop_requested = True
        print('Stop requested; finishing and recording the current native rounds.', flush=True)
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop_after_batch)

    async def one(f):
        frozen()
        a, b = f['candidate'], f['opponent']
        pa, pb = f['rtt']
        if f['reverse']:
            a, b, pa, pb = b, a, pb, pa
        game = await match(a, b, pa, pb)
        archive(game)
        quality = diagnostic(game)
        combo = combo_analysis(game)
        for side in ('a', 'b'):
            if game['client_frame_sleep_ms'].get(side) != document['frame_sleep_ms']:
                quality['issues'].append(side + ': frame sleep differs from frozen setting')
                quality['passed'] = False
        sides = ('b', 'a') if f['reverse'] else ('a', 'b')
        metrics = game['combat_metrics']
        row = dict(fixture=f, game=game, quality=quality, combo_analysis=combo,
                   score=[game['hits_' + s] for s in sides],
                   fresh=[metrics[s]['fresh_hurt_contacts'] for s in sides],
                   upgrades=[metrics[s]['damage_upgrades'] for s in sides],
                   longest_streak=[metrics[s]['longest_unanswered_streak'] for s in sides],
                   ray_denial_combo_ms=[metrics[s].get('sustained_native_combo', {}).get('duration_ms', 0) for s in sides],
                   combo_ms=[combo['sides'][s]['geometric_lock']['duration_ms'] for s in sides])
        document['results'].append(row)
        document['summary'] = summarize(document)
        checkpoint(path, document)
        print(json.dumps({'id': game['id'], 'candidate': f['candidate'], 'rtt': f['rtt'],
                          'score': row['score'], 'fresh': row['fresh'], 'quality': quality['passed'],
                          'issues': quality['issues']}), flush=True)
        if not quality['passed']:
            raise RuntimeError('Timing/trace gate failed; cohort paused after current batch')

    try:
        for offset in range(0, len(pending), document['concurrency']):
            outcomes = await asyncio.gather(*(one(f) for f in pending[offset:offset + document['concurrency']]), return_exceptions=True)
            errors = [str(e) for e in outcomes if isinstance(e, BaseException)]
            if errors:
                document['failures'].extend(errors)
                raise RuntimeError('; '.join(errors))
            if stop_requested:
                document['state'] = 'interrupted'
                document['last_error'] = 'Stopped after active batch; completed evidence preserved.'
                checkpoint(path, document)
                return
    except BaseException as error:
        document['state'] = 'interrupted'
        document['last_error'] = str(error)
        checkpoint(path, document)
        raise
    document['state'] = 'complete'
    document['finished'] = time.time()
    document['summary'] = summarize(document)
    checkpoint(path, document)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('screen')
    p.add_argument('name')
    p.add_argument('--cps', type=int, default=20)
    p.add_argument('--aim-delay', type=float, default=150)
    p.add_argument('--aim-noise', type=float, default=2)
    p.add_argument('--aim-rate', type=float, default=360)
    p.add_argument('--tap-delay', type=float, default=150)
    p = sub.add_parser('prepare')
    p.add_argument('name'); p.add_argument('--bots', nargs='+', required=True)
    p.add_argument('--opponent', default='search-wtap')
    p.add_argument('--rtts', nargs='+', default=['20,20'])
    p.add_argument('--repeats', type=int, default=1)
    p.add_argument('--seed', type=int, default=20261102)
    p.add_argument('--parallel', type=int, default=2)
    p.add_argument('--stage', choices=['screen', 'confirmation', 'ablation'], default='confirmation')
    for name in ('run', 'summary'):
        p = sub.add_parser(name); p.add_argument('name')
    args = parser.parse_args()
    if args.command == 'screen':
        print(prepare(args.name, initial_policies(args.cps, args.aim_delay, args.aim_noise, args.aim_rate, args.tap_delay), 'search-wtap', [[20, 20]]))
    elif args.command == 'prepare':
        print(prepare(args.name, args.bots, args.opponent, [list(map(int, p.split(','))) for p in args.rtts], args.repeats, args.seed, args.parallel, args.stage))
    elif args.command == 'run':
        asyncio.run(run(OUT / (args.name + '.json')))
    else:
        d = json.loads((OUT / (args.name + '.json')).read_text())
        print(json.dumps({'state': d['state'], 'summary': summarize(d)}, indent=2))


if __name__ == '__main__':
    main()
