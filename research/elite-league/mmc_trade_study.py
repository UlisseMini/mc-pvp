"""Fork the new direct actor's first two observed exchanges against W-tap and its hardest rivals."""
from collections import defaultdict
import argparse
import gzip
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
from adaptation import dump, freeze, sha
from focus import play_model
from mmc_league import EVIDENCE, FROZEN, WORK, groups, setup
from run import ROOT, calendars, compile_model, java
from tournament import roster


def run(isolate=False):
    setup()
    original = EVIDENCE / 'training-trades'
    study = EVIDENCE / 'trade-inputs' if isolate else original
    study.mkdir(exist_ok=True)
    actor = 'mmc13-actor-2-fit'
    policy = FROZEN / 'mmc13-actor-2/contender.net'
    previous = FROZEN / 'mmc13-actor-1/contender.net'
    foes = {'wtap': None, 'mmc13-actor-1-fit': previous, 'adapt-04-pair': groups()['modern']['adapt-04-pair']}
    clocks = calendars('training_clocks')
    design = dict(
        arm=actor, policy_sha256=sha(policy), foes=list(foes), training_clocks=[p.name for p in clocks],
        decision='First or second episode with both own and opponent hurt cues in the preceding 230 ms. Intervene beginning at the following decision.',
        endpoint='Regulation ring-out. A draw scores half. Branch W-tap and the main actor from the same queues, full plant, clock and primed policy states.',
        controls='Play both the unchanged actor and plain W-tap from the common ordinary start. Leave fixtures that never reach the exchange in the entry denominator.')
    if isolate:
        design.update(scope='Mechanism diagnostic on previously exposed training clocks only. No title selection.',
                      interventions=['left', 'recoil-click'], channels=['key', 'strafe', 'click', 'jump'],
                      method='At each of the following three input decisions replace only the selected channels. Other channels use the original actor reacting to that branch. Score every subset and use four-input Shapley values to split the ring-out score difference. Report outcomes and variation by clock, seat and RTT.',
                      native='The native confirmation of the learned complete controller runs independently. A native single-input causal comparison is unmeasured.')
    dump(study / 'design.json', design)
    regular = original / 'from-start.jsonl.gz'
    play_model(regular, {'wtap': None, actor: policy}, foes, clocks)
    compile_model()
    with tempfile.TemporaryDirectory(dir=WORK) as temp:
        t = Path(temp)
        roster(t / 'arm', {actor: policy})
        roster(t / 'foes', foes)
        for index in (1, 2):
            output = study / f'exchange-{index}.jsonl.gz'
            if not output.exists():
                flags = ['--second-trade'] if index == 2 else []
                flags.extend(['--mouse', '--audit-forks'])
                if isolate:
                    flags.append('--isolate-inputs')
                args = java('TradeWords', t / 'arm', t / 'foes', *flags, *clocks)
                with subprocess.Popen(args, stdout=subprocess.PIPE, cwd=ROOT) as child:
                    with (t / 'run.gz').open('wb') as stream, gzip.GzipFile(fileobj=stream, mode='wb', mtime=0) as packed:
                        shutil.copyfileobj(child.stdout, packed)
                    if child.wait():
                        raise RuntimeError('Exchange continuation or its full-state check failed')
                freeze(output, (t / 'run.gz').read_bytes())
            if isolate:
                from trade_attribution import analyze
                analyze(output, original / output.name, study / f'comparison-{index}.json')
                print('Verified trade input decomposition', index, flush=True)
                continue
            cells = defaultdict(list)
            with gzip.open(output, 'rt') as stream:
                for row in map(json.loads, stream):
                    cells[row['opponent']].append(row)
            summary = {}
            for name, rows in cells.items():
                reached = [r for r in rows if 'continuations' in r]
                if len(rows) != 4 * len(clocks) or not all(r.get('unchanged_terminal_sha256') for r in reached):
                    raise ValueError('Incomplete exchange fixtures')
                branches = {}
                for branch in reached[0]['continuations'] if reached else []:
                    scores = [r['continuations'][branch][0] for r in reached]
                    deltas = [s - r['base'] for s, r in zip(scores, reached)]
                    branches[branch] = dict(points=sum(scores), improved=sum(d > 0 for d in deltas),
                                            worse=sum(d < 0 for d in deltas), ties=deltas.count(0))
                summary[name] = dict(starts=len(rows), reached=len(reached), branches=branches)
            dump(study / f'comparison-{index}.json', summary)
            print(index, [(k, v['reached'], v['branches'].get('wtap')) for k,v in summary.items()], flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--isolate-inputs', action='store_true')
    run(parser.parse_args().isolate_inputs)
