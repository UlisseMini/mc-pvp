"""Compare the measured-map finalists on one prospective model and native schedule."""
import argparse
import gzip
import json
from adaptation import dump, sha
from history_cup import FIELD, mirrors
import focus
from mmc_league import EVIDENCE, FROZEN, WORK, WT, contest, contest_plan, finalists, groups, native_name, setup
from native_trials import run_batch
from reigning import publish
from run import HERE


def common_field():
    incumbent, _, controls, _ = finalists()
    policies = groups()['modern'] | controls
    for i in (2, 3):
        for kind in ('explicit', 'neural'):
            policies[f'mmc13-{i:02d}-{kind}'] = FROZEN / f'round-{i:02d}' / (kind + '.net')
    for i in (4, 5):
        policies[f'mmc13-{i:02d}-pair'] = FROZEN / f'round-{i:02d}/contender.net'
    for i in (1, 2):
        policies[f'mmc13-actor-{i}-fit'] = FROZEN / f'mmc13-actor-{i}/contender.net'
    entries = dict.fromkeys(['wtap', 'elite-population', incumbent])
    for filename in ('actor-confirmation.json', 'pairs-confirmation.json'):
        spec = json.loads((FROZEN / filename).read_text())
        for n, digest in spec['arms'].items():
            if focus.identity(n, policies[n]) != digest:
                raise ValueError('Confirmation entrant differs from frozen weights: ' + n)
            entries[n] = None
    arms = focus.unique({n: policies[n] for n in entries})
    switches = json.loads((FIELD / 'field.json').read_text())['shift-sealed-04']
    path = FIELD / 'nets' / switches['net']
    if sha(path) != switches['sha256']:
        raise ValueError('Switching opponent differs from archive')
    policies['shift-sealed-04'] = path
    foes = dict.fromkeys(['wtap', 'hold', incumbent])
    for folder in ('round-03', 'round-05', 'mmc13-actor-2'):
        selection = json.loads((FROZEN / folder / 'selection.json').read_text())
        weak = next(n for n in selection['ranking'][0]['weakest']
                    if focus.identity(n, policies[n]) != focus.identity(selection['winner'], policies[selection['winner']]))
        foes[weak] = None
    foes['shift-sealed-04'] = None
    return arms, focus.unique({n: policies[n] for n in foes})


def run(native_only=False):
    setup()
    arms, foes = common_field()
    repeats = {n: 12 if n == 'wtap' else 3 for n in foes}
    dump(FROZEN / 'final-entrants.json', dict(
        protocol=sha(HERE / 'mmc13-final-protocol.json'),
        entrants={n: focus.identity(n, p) for n, p in arms.items()},
        opponents={n: focus.identity(n, p) for n, p in foes.items()}, repeats=repeats))
    clocks = mirrors('mmc13-final', WORK)
    if native_only:
        scheduled = json.loads((EVIDENCE/'final/schedule.json').read_text())
        expected = dict(profile='mmc',arms={n:focus.identity(n,p) for n,p in arms.items()},
                        opponents={n:focus.identity(n,p) for n,p in foes.items()},repeats=repeats)
        if scheduled!=expected:raise ValueError('Native final differs from published schedule')
        # The normal final owns exclusions, input audits and publication. This worker
        # can play the same frozen schedule while the main worker computes its model.
        run_batch(contest_plan('final',arms,foes,repeats),WORK/'mmc13-final.json')
        return
    rows, model = contest('final', arms, foes, repeats, clocks)
    out = EVIDENCE / 'final'
    verdicts = {}
    for arm in map(native_name, arms):
        vs_wtap = [r for r in rows if r['arm'] == arm and r['opponent'] == WT]
        sim = [r for r in model if r['arm'] == arm and r['opponent'] == WT]
        cells = {}
        for rtt in (0, 80):
            for side in 'ab':
                games = [r for r in vs_wtap if (r['rtt'], r['side']) == (rtt, side)]
                if len(games) != 12:
                    raise ValueError('Missing W-tap stratum')
                cells[f'{rtt}/{side}'] = sum(r['win'] for r in games)
        if len(sim) != 32:
            raise ValueError('Missing prospective model fixtures')
        native_wins = sum(cells.values())
        model_wins = sum(r['score'] == 1 for r in sim)
        verdicts[arm] = dict(native_wins=native_wins, native_games=48, native_draws=sum(r['draw'] for r in vs_wtap),
                             model_wins=model_wins, model_games=32, native_cell_wins=cells,
                             qualified=native_wins >= 44 and min(cells.values()) >= 10 and model_wins >= 29)
    with gzip.open(out / 'native.json.gz', 'rt') as stream:
        audit = json.load(stream)
    if len(audit['matches']) != len(rows) or audit['summary']['binding_disagreements']:
        raise ValueError('Native controls failed verification')
    winner = json.loads((out / 'outcomes.json').read_text())['winner']
    receipt = out / 'title.json'
    dump(receipt, dict(winner=winner, wtap=verdicts, audited_sha256=sha(out / 'native.json.gz'),
                       physical_inputs_sha256=sha(out / 'input-audit.json'), outcome_sha256=sha(out / 'outcomes.json')))
    publish('best-sumo', winner, 'Recorded MMC platform native final winner (' + winner + ')', receipt)
    print(winner, verdicts[winner], flush=True)


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--native-only',action='store_true')
    run(parser.parse_args().native_only)
