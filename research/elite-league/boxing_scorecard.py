"""Regulation boxing hit margins, damage upgrades and doubles for both matched arms."""
from collections import defaultdict
import gzip
import itertools
import json
from statistics import median

from adaptation import dump
from boxing_regulation import NAMES, audited_bundle
from results import lines, outcome
from run import HERE, ROOT


def hit_counts(events):
    counts = {team: dict(fresh=0, upgrades=0, doubles=0) for team in ('for', 'against')}
    run_team, streak, last_fresh = None, 0, float('-inf')
    for _, group in itertools.groupby(events, key=lambda e: e[0]):
        batch = list(group)
        teams = set()
        fresh = 0
        for _, time, team, upgrade in batch:
            counts[team]['upgrades' if upgrade else 'fresh'] += 1
            teams.add(team)
            if not upgrade:
                fresh += 1
        # Both sides scored in the same server tick. Neither secured an unanswered double.
        team = next(iter(teams)) if len(teams) == 1 else None
        at = batch[0][1]
        if team != run_team or team is None or at - last_fresh > 850:
            run_team, streak = team, 0
        if team is not None and fresh:
            if streak == 1:
                counts[team]['doubles'] += 1
            streak += fresh
            last_fresh = at
    return counts


def row(arm, opponent, rtt, side, ident, score, margin, counts):
    own, other = counts['for'], counts['against']
    if own['fresh'] + own['upgrades'] - other['fresh'] - other['upgrades'] != margin:
        raise ValueError(f'Accepted hit margin differs from referee on {ident}')
    return dict(arm=arm, opponent=opponent, rtt=rtt, side=side, id=ident,
                score=score, margin=margin, hits=counts)


def read_native(game):
    mid, side = game['mid'], game['side']
    record = json.loads((ROOT / f'runtime/results/{mid}.json').read_text())
    ours = record[side]
    with (ROOT / f'runtime/replays/{mid}.jsonl').open() as stream:
        # Most lines are unrelated snapshots. Decode only hit rows while preserving server order.
        hits = (json.loads(line) for line in stream if '"hit"' in line)
        events = ((e['server_tick'], e['time'], 'for' if e['attacker'] == ours else 'against', e['damage_upgrade'])
                  for e in hits if e['type'] == 'hit' and not e['rod'])
        counts = hit_counts(events)
    for name, seat in [('for', side), ('against', 'b' if side == 'a' else 'a')]:
        if counts[name]['fresh'] + counts[name]['upgrades'] != record['hits_' + seat]:
            raise ValueError(f'Accepted hit count differs on {mid} {seat}')
    return row(game['arm'], game['opponent'], game['rtt'], side, mid,
               .5 if game['draw'] else int(game['win']), game['margin'], counts)


def read_model(g):
    events = ((at, at, 'against' if against else 'for', upgrade) for at, against, upgrade in g['hits'])
    return row(NAMES[g['arm']], NAMES[g['opponent']], g['rtt'], g['side'], g['clock'],
               g['score'], g['margin'], hit_counts(events))


def summarize(rows):
    cells = defaultdict(list)
    for g in rows:
        for cell in ('all', f"{g['rtt']}/{g['side']}"):
            cells[g['opponent'], g['arm'], cell].append(g)
    table = {}
    for (opponent, arm, cell), games in cells.items():
        margins = [g['margin'] for g in games]
        hits = {t: {k: sum(g['hits'][t][k] for g in games) for k in ('fresh','upgrades','doubles')}
                for t in ('for','against')}
        table.setdefault(opponent, {}).setdefault(arm, {})[cell] = dict(
            outcomes=outcome([g['score'] for g in games]), accepted_margin_median=median(margins),
            accepted_margin_range=[min(margins), max(margins)], hits=hits,
            fresh_margin=hits['for']['fresh']-hits['against']['fresh'])
    for opponent, arms in table.items():
        control = arms[NAMES['wtap']]
        for arm, cells in arms.items():
            if cells.keys() != control.keys() or any(cells[k]['outcomes']['games'] != control[k]['outcomes']['games'] for k in cells):
                raise ValueError(f'Unequal exposure in {arm} versus {opponent}')
    return table


def publish(dest):
    with gzip.open(audited_bundle(dest), 'rt') as f:
        bundle = json.load(f)
    native = [read_native(g) for g in bundle['matches']]
    model = [read_model(g) for g in lines(dest / 'boxing.jsonl.gz')]
    if len(native) != 64 or len(model) != 256 or bundle['summary']['binding_disagreements']:
        raise ValueError('Missing audited regulation field')
    dump(dest / 'scorecard.json', dict(
        double_definition='One double per unanswered streak when it reaches two fresh hits within 850 ms. A tick on which both sides score breaks the streak. Native uses the recorded server tick and simulation groups the accepted hits at each server drain.',
        native=summarize(native), model=summarize(model), native_matches=native, model_matches=model))


if __name__ == '__main__':
    from cpu_limit import supervise_script
    supervise_script()
    publish(HERE / 'evidence/boxing100')
