"""Separate local contact from arrival in the next two fresh-damage drains."""

import argparse
from collections import Counter, defaultdict
import json
import math
from pathlib import Path

from boxing_return_geometry import summarize


def classify(window, side):
    g = window['sides'][side]
    has_contact = g['local_contact'] > 0
    scored = g['next_fresh_on_time']
    status = Counter(windows=1, contact=has_contact, fresh=scored)
    status['contact_without_fresh'] += has_contact and not scored
    status['fresh_without_bracket_contact'] += scored and not has_contact
    eligible = [c for c in g['samples'] if c['eligible']]
    status['no_eligible_click'] += not eligible
    status['eligible_but_out_of_box_reach'] += bool(eligible) and min(c['nearest'] for c in eligible) > 3
    status['reachable_eligible_without_contact'] += (
        bool(eligible) and min(c['nearest'] for c in eligible) <= 3 and not has_contact)
    return status


def arm_summary(windows, impulses):
    result = defaultdict(lambda: [Counter(), Counter()])
    physics = {(t, side): (math.hypot(x, z), y, ground) for t, side, x, y, z, _, ground in impulses}
    for w in windows:
        initial = [physics[(t, side)] for side, t in enumerate(w['trade'])]
        labels = ['all_trades']
        if w['trade'][0] < w['trade'][1] and all(x > .7 and y > 0 and grounded for x, y, grounded in initial):
            labels.append('first_with_two_grounded_sprint_hits')
        for side in (0, 1):
            status = classify(w, side)
            for key in labels:
                result[key][side].update(status)
    for sides in result.values():
        for s in sides:
            assert s['fresh'] == s['contact'] - s['contact_without_fresh'] + s['fresh_without_bracket_contact']
    return result


def add(destination, source):
    for group, counts in source.items():
        for side in (0, 1):
            destination[group][side].update(counts[side])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--native', action='store_true')
    parser.add_argument('--ping', type=int, default=20)
    parser.add_argument('--match', nargs='*')
    args = parser.parse_args()
    result = defaultdict(lambda: defaultdict(lambda: [Counter(), Counter()]))
    runs = []
    with args.input.open() as stream:
        for row in map(json.loads, stream):
            match = row['id'] if args.native else row['clock']
            if args.match and match not in args.match:
                continue
            arms = {'jump' if row['arm']=='boxing-study-jump' else 'wtap': row} if args.native else {a: row[a] for a in ('wtap', 'jump')}
            for arm, data in arms.items():
                if args.native:
                    variants = data['variants']
                else:
                    hits = [(tick, side, upgrade) for _, tick, side, upgrade in data['hits']]
                    drains = dict(data['drains'])
                    legs = (max(0, args.ping / 2 - 5), args.ping / 2 + .35, args.ping / 2 + 5)
                    variants = {str(ms): summarize(hits, drains, data['clicks'], row['threshold'], hits[-1][0], ms)
                                for ms in legs}
                for bracket, windows in variants.items():
                    stats = arm_summary(windows, data['impulses'])
                    add(result[f'{arm}/{bracket}'], stats)
                    runs.append(dict(match=match, seat=row['seat'], arm=arm, bracket=bracket, counts=stats))
    args.output.write_text(json.dumps(dict(aggregate=result, runs=runs), indent=2) + '\n')


if __name__ == '__main__':
    main()
