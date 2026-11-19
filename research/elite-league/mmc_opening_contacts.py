"""Try one short forward-key change in sumo openings where no hurt cue has arrived."""
import argparse
import gzip
import json
import subprocess
import tempfile
from pathlib import Path

from adaptation import dump, freeze, sha
from mmc_league import EVIDENCE, FROZEN, WORK
from mmc_trade_fit import pool
from run import calendars, compile_model, java
from tournament import roster


def run(clicks=False):
    stage = FROZEN / 'mmc13-phase-trades'
    arm = json.loads((stage / 'selection.json').read_text())['contender']
    opponents = ['wtap', 'mmc13-actor-2-fit', 'continuation-02-fit']
    clocks = calendars('development_selection_clocks')
    dest = EVIDENCE / 'opening-contact'
    dest.mkdir(exist_ok=True)
    dump(dest / 'protocol.json', dict(arm=arm, weights=sha(stage / (arm + '.net')), opponents=opponents,
         development_clocks=[p.name for p in clocks], earlier_full_bouts=sha(stage / 'development.jsonl.gz'),
         landmark='First server end at or after 2000ms, with no hurt cue seen by the actor and no terminal result.',
         interventions=['unchanged', 'walk four own movement heads', 'sprint four', 'sprint eight', 'sprint sixteen',
                        'plain W-tap for rest of fight with primed local state'],
         controls='Temporary interventions change only the forward/sprint key. Retain the actor strafe, click and jump each tick.',
         snapshots='Time, center gap, local nearest box distance, own key/strafe/click and rival key/strafe/click.'))
    path = dest / ('clicks.jsonl.gz' if clicks else 'forks.jsonl.gz')
    if clicks:
        dump(dest / 'click-protocol.json', dict(original=sha(dest / 'forks.jsonl.gz'),
             reason='All 32 stalled duels saw nearby targets and both click gates closed. All four forward-key words retained 32 draws.',
             actions='Force only the normal capped click gate on the next 1, 3, 6 or 12 own heads. Also test four back-key heads with gate closed before a 3- or 6-head burst. Other channels track the primed actor.',
             check='Identical initial and unchanged terminal hashes and the same W-tap continuation as the original movement experiment.'))

    if not path.exists():
        compile_model()
        with tempfile.TemporaryDirectory(dir=WORK) as folder:
            tmp = Path(folder)
            roster(tmp/'arm', {arm:stage / (arm + '.net')})
            field = pool()
            roster(tmp/'foes', {n:field[n] for n in opponents})
            with (tmp/'rows').open('w') as out:
                subprocess.run(java('OpeningContactStudy', tmp/'arm', tmp/'foes', *(['--clicks'] if clicks else []), *clocks), stdout=out, check=True)
            rows = [json.loads(line) for line in (tmp/'rows').read_text().splitlines()]
            keys = ('clock','side','rtt','opponent')
            def key(row): return tuple(row[k] for k in keys)
            with gzip.open(stage/'development.jsonl.gz', 'rt') as original:
                archived = {(r['arm'],key(r)):r for r in map(json.loads, original) if r['arm'] in (arm,'wtap') and r['opponent'] in opponents}
            if len(rows) != 96 or len(set(map(key, rows))) != 96 or len(archived) != 192:
                raise ValueError('Incomplete paired opening panel')
            for r in rows:
                old = archived[arm,key(r)]
                observed = r['base_fresh'] if r['side']=='a' else r['base_fresh'][::-1]
                if (r['checkpoint_score'], observed, r['plain_wtap']) != (old['score'],[old['fresh'],old['incoming']],archived['wtap',key(r)]['score']):
                    raise ValueError('Opening histories differ from full tournament runs')
            if clicks:
                with gzip.open(dest/'forks.jsonl.gz','rt') as src:
                    previous = {key(r):r for r in map(json.loads,src)}
                for r in rows:
                    original = previous[key(r)]
                    for k in ('snapshots','fork_sha256','unchanged_terminal_sha256'):
                        if original.get(k) != r.get(k): raise ValueError('Changed opening history')
                    for n in ('unchanged','wtap'):
                        if original['branches'].get(n) != r['branches'].get(n): raise ValueError('Control branch changed')
            freeze(path, gzip.compress((tmp/'rows').read_bytes(),mtime=0))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--clicks',action='store_true')
    run(parser.parse_args().clicks)
