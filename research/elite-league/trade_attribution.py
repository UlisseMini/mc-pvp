"""Attribute a registered three-decision intervention to four input channels on matched forks."""
from collections import defaultdict
import gzip
import json
from math import comb, isclose

from adaptation import dump


CHANNELS = ('key', 'strafe', 'click', 'jump')
WORDS = ('left', 'recoil-click')


def fixtures(path):
    rows = {}
    with gzip.open(path, 'rt') as stream:
        for row in map(json.loads, stream):
            key = (row['opponent'], row['clock'], row['rtt'], row['side'])
            if key in rows:
                raise ValueError(f'Duplicate fixture {key}')
            rows[key] = row
    return rows


def outcomes(scores):
    return dict(wins=scores.count(1), draws=scores.count(.5), losses=scores.count(0))


def decompose(row, word):
    branches = row['continuations']
    scores = [branches['continue'][0]]
    for mask in range(1, 16):
        channels = '+'.join(name for i, name in enumerate(CHANNELS) if mask & (1 << i))
        scores.append(branches[f'{word}/{channels}'][0])
    if scores[-1] != branches[word][0]:
        raise ValueError('Complete intervention differed from registered word')
    # Weight each marginal gain by the fraction of channel orderings in which
    # exactly this subset has already been substituted.
    result = {}
    for i, name in enumerate(CHANNELS):
        bit = 1 << i
        result[name] = sum((scores[mask | bit] - scores[mask]) / (4 * comb(3, mask.bit_count()))
                           for mask in range(16) if not mask & bit)
    if not isclose(sum(result.values()), scores[-1] - scores[0], abs_tol=1e-9):
        raise ValueError('Channel contributions did not sum to observed ring-out score difference')
    return result


def group(rows):
    branches = [row['continuations'] for row in rows]
    result = {name: outcomes([b[name][0] for b in branches])
              for name in ('wtap', 'continue', *WORDS)}
    result['forks'] = len(branches)
    for word in WORDS:
        contributions = [decompose(row, word) for row in rows]
        result[word]['shapley_points'] = {name: round(sum(c[name] for c in contributions), 6)
                                          for name in CHANNELS}
        result[word]['single_channel_vs_continue'] = {}
        for name in CHANNELS:
            delta = [b[f'{word}/{name}'][0] - b['continue'][0] for b in branches]
            result[word]['single_channel_vs_continue'][name] = dict(
                improved=sum(d > 0 for d in delta), worsened=sum(d < 0 for d in delta),
                equal=delta.count(0), net_points=sum(delta))
    return result


def analyze(source, original, destination):
    rows, prior = fixtures(source), fixtures(original)
    if rows.keys() != prior.keys():
        raise ValueError('Fork fixture grid differs from original')
    scopes = defaultdict(list)
    starts = defaultdict(int)
    for key, row in rows.items():
        previous = prior[key]
        opponent, clock, rtt, side = key
        for name, expected in previous.items():
            if name == 'continuations':
                if any(row[name][arm] != value for arm, value in expected.items()):
                    raise ValueError(f'Historical control diverged on {key}')
            elif row.get(name) != expected:
                raise ValueError(f'Historical {name} diverged on {key}')
        if ('continuations' in previous) != ('continuations' in row):
            raise ValueError(f'Exchange eligibility changed on {key}')
        for scope in ('all', f'rtt{rtt}-{side}', f'clock-{clock}'):
            starts[opponent, scope] += 1
            if 'continuations' in row:
                scopes[opponent, scope].append(row)
    report = defaultdict(dict)
    for (opponent, scope), count in starts.items():
        report[opponent][scope] = dict(starts=count, **group(scopes[opponent, scope]))
    dump(destination, report)
