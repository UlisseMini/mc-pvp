#!/usr/bin/env python3
"""Summarize the frozen matched suite; refuse unmatched or incomplete evidence."""
import collections
import gzip
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
POLICIES = {
    'research-wtap20': 'Plain W-tap, 0 ms',
    'research-hold20': 'Hold-W',
    'combo-wtap20': 'Historical W-tap, 150 ms',
    'cycle-word-r30': 'Fixed word',
    'cycle-strafe-r30': 'Word + strafe',
    'cycle-cue-r30': 'Word + cue timing',
}


def events(mid, side):
    with gzip.open(ROOT / f'research/traces/{mid}-{side}.jsonl.gz', 'rt') as stream:
        for line in stream:
            yield json.loads(line)


def intervals(rows):
    gaps = sorted((b-a)/1e6 for a, b in zip(rows, rows[1:]))
    if not gaps:
        raise ValueError('Missing native clock evidence')
    return {'samples': len(rows), 'mean_ms': sum(gaps)/len(gaps),
            'p95_ms': gaps[min(len(gaps)-1, math.ceil(.95*len(gaps))-1)],
            'max_ms': max(gaps), 'over_100_ms': sum(x > 100 for x in gaps)}


def main():
    report = json.loads((ROOT/'research/matched-native-20261101.json').read_text())
    if not report.get('complete'):
        raise ValueError('Suite incomplete: preserve the planned repeat set before ranking')
    by_policy = collections.defaultdict(list)
    quality = []
    metadata_path = ROOT/'research/combo-matched-native-metadata-20261101.json.gz'
    if metadata_path.exists():
        with gzip.open(metadata_path, 'rt') as stream:
            metadata = json.load(stream)
    else:
        metadata = {}
    for row in report['rows']:
        mid = row['match']
        if mid not in metadata:
            metadata[mid] = json.loads((ROOT/f'runtime/results/{mid}.json').read_text())
        game = metadata[mid]
        expected = {'mode': 'boxing', 'duration_limit_ms': 30000,
                    'attack_reach_a': 3.0, 'attack_reach_b': 3.0,
                    'click_cps_a': 20, 'click_cps_b': 20, 'ping_a': 0, 'ping_b': 120,
                    'reaction_ms_b': 0, 'policy_b': 'research-wake-hold',
                    'reaction_ms_a': 150 if row['policy'] == 'combo-wtap20' else 0}
        for key, value in expected.items():
            if game.get(key) != value:
                raise ValueError(f'{mid}: unmatched {key}: {game.get(key)} != {value}')
        if game['policy_a'] != row['policy'] or not row['validation']['passed']:
            raise ValueError(f'{mid}: policy or recorded validation failure')
        profile = game['combat_profile']
        if (profile['kb_horizontal'], profile['kb_vertical'], profile['fresh_hit_ticks']) != (1, 1, 10):
            raise ValueError(f'{mid}: combat profile differs')
        if float(game['parameters_b'].get('click_start_ms', 0)) != 2000:
            raise ValueError(f'{mid}: opening handicap differs')
        for side in ('a', 'b'):
            for key in ('aim_delay_ms', 'aim_noise_deg', 'aim_turn_rate_dps'):
                if float(game[f'parameters_{side}'].get(key, 0)) != 0:
                    raise ValueError(f'{mid}: imperfect aim')
        clock = {}
        first_clock = {}
        primary_clock = {}
        opening = {}
        hits = []
        for side in ('a', 'b', 'server'):
            samples = []
            sample_times = []
            for event in events(mid, side):
                if event.get('type') not in ('science_move', 'replication_server_clock', 'hit'):
                    continue
                if not game['start'] <= event['time'] <= game['finish']:
                    continue
                if side == 'server' and event['type'] == 'hit' and not event.get('rod'):
                    hits.append(event)
                selected = (side != 'server' and event['type'] == 'science_move') or (
                    side == 'server' and event['type'] == 'replication_server_clock'
                    and event['phase'] == 'tick_start')
                if selected:
                    samples.append(event['monotonic_ns'])
                    sample_times.append(event['time']-game['start'])
                    if side != 'server' and side not in opening:
                        opening[side] = event['before']
            clock[side] = intervals(samples)
            first_clock[side] = samples[0]
            prefix = [samples[0]]
            for i in range(1, len(samples)):
                if sample_times[i-1] <= row['first_return_ms']:
                    prefix.append(samples[i])
            primary_clock[side] = intervals(prefix)
        for start, expected_count in (
                (game['start'], row['fresh_hits_before_first_return']),
                (game['start']+2000, row['after_opponent_wake']['fresh_hits_before_first_return'])):
            count = 0
            for hit in hits:
                if hit['time'] < start:
                    continue
                if hit['attacker'] == game['b']:
                    break
                count += not hit['damage_upgrade']
            if count != expected_count:
                raise ValueError(f'{mid}: primary count disagrees with archived event order')
        gap = math.hypot(opening['a']['posX']-opening['b']['posX'],
                         opening['a']['posZ']-opening['b']['posZ'])
        if abs(gap-6) > 1e-9:
            raise ValueError(f'{mid}: opening distance is {gap}, expected 6')
        quality.append({'match': mid, 'clock_intervals': clock,
                        'primary_clock_intervals': primary_clock,
                        'primary_timing_flag': any(c['over_100_ms'] for c in primary_clock.values()),
                        'full_match_timing_flag': any(c['over_100_ms'] for c in clock.values()),
                        'elapsed_ms': game['finish']-game['start'], 'initial_gap': gap,
                        'first_event_relative_phase_mod50_ms': {
                            side: ((first_clock[side]-first_clock['a'])/1e6) % 50
                            for side in ('b', 'server')},
                        'phase_note': 'First B movement/server tick relative to first A movement, modulo nominal 50 ms. Descriptive only; clocks drift.'})
        by_policy[row['policy']].append(row)
    if set(by_policy) != set(POLICIES):
        raise ValueError('Missing or unexpected policy')
    for policy, rows in by_policy.items():
        rows.sort(key=lambda r: r['repeat'])
        if [r['repeat'] for r in rows] != [1, 2, 3]:
            raise ValueError(f'{policy}: repeat set differs')
    metrics = [
        ('Fresh hits before first return, including opening', lambda r: r['fresh_hits_before_first_return']),
        ('Fresh hits after opponent wakes, before first return', lambda r: r['after_opponent_wake']['fresh_hits_before_first_return']),
        ('Longest unanswered fresh run, whole match', lambda r: r['longest_unanswered_fresh_run']),
        ('Longest unanswered fresh run after wake', lambda r: r['after_opponent_wake']['longest_fresh_run']),
        ('Entire 30 s without a return', lambda r: 'yes' if r['zero_return_horizon'] else 'no'),
        ('First return after wake (ms)', lambda r: r['after_opponent_wake']['first_return_ms_after_wake']),
        ('Replay IDs', lambda r: r['match']),
    ]
    lines = ['# Matched native baselines, November 1', '', report['scope'], '',
             'Each cell lists repeats 1 / 2 / 3. All variants share the stated settings;',
             'the 150 ms W-tap is an explicitly separate historical reference. Native',
             'runs do not share exactly the same OS schedule or tick phase. Opening',
             'handicap hits and post-wake maintenance are shown separately.', '',
             '| Metric | ' + ' | '.join(POLICIES.values()) + ' |',
             '|---|' + '---|'*len(POLICIES)]
    for title, getter in metrics:
        cells = [' / '.join(str(getter(r)) for r in by_policy[p]) for p in POLICIES]
        lines.append('| ' + title + ' | ' + ' | '.join(cells) + ' |')
    all_clocks = [q['clock_intervals'][s] for q in quality for s in ('a', 'b', 'server')]
    primary_flags = [q['match'] for q in quality if q['primary_timing_flag']]
    full_flags = [q['match'] for q in quality if q['full_match_timing_flag']]
    lines += ['', f'All {len(quality)} matches passed the existing build, click-cap, native-reach',
              'and target-integrity checks. The report also verifies frozen settings and',
              'six-block opening distance from recorded data. Tick/movement interval',
              f'means range from {min(c["mean_ms"] for c in all_clocks):.3f} to',
              f'{max(c["mean_ms"] for c in all_clocks):.3f} ms; the largest interval is',
              f'{max(c["max_ms"] for c in all_clocks):.3f} ms. There are',
              f'{sum(c["over_100_ms"] for c in all_clocks)} intervals above 100 ms.',
              f'Additional timing audit flags intervals above 100 ms in the primary window of: {", ".join(primary_flags) or "none"}.',
              f'Whole-match timing flags: {", ".join(full_flags) or "none"}.',
              'These rows remain visible, but their affected metrics are timing-qualified,',
              'not clean-clock evidence. In particular, the first hold-W opening is flagged;',
              'the candidate and W-tap first-return comparisons have no such long stall.',
              'The 100 ms flag is an explicit supplementary audit, not a claim that smaller',
              'jitter is irrelevant. These checks do not establish whole-fight model parity.', '',
              'Three repeats are descriptive evidence, not a tight estimate of tail behavior.',
              'No superiority claim follows from one favorable opening or later run.', '']
    (ROOT/'research/combo-matched-native-tables-20261101.md').write_text('\n'.join(lines))
    (ROOT/'research/combo-matched-native-quality-20261101.json').write_text(
        json.dumps({'scope': 'Recorded setting and clock checks, no policy ranking', 'rows': quality}, indent=2)+'\n')
    if not metadata_path.exists():
        with gzip.open(metadata_path, 'wt') as stream:
            json.dump(metadata, stream, separators=(',', ':'))
    print('\n'.join(lines))


if __name__ == '__main__':
    main()
