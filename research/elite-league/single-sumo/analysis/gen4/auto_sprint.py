"""Reproduce the Gen 4 Ctrl-only comparison using the league's eight development clocks."""
import argparse
import gzip
import json
import os
from pathlib import Path
import random
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[4]
CHAMPION = 'remote-004-main-gru'
FIELD = ROOT / 'research/elite-league/single-sumo/evidence/robustness-001/continuous-league/g004/tournament.json'
PIN = ROOT / 'build/elite-league/single-sumo/sim-0de762acda668fc8af56'


def schedule(zero_only):
    field = json.loads(FIELD.read_text())['field']
    clocks = [line.split('\t')[1] for line in (ROOT / 'research/elite-league/ping-ppo/clocks.tsv').read_text().splitlines()
              if line.startswith('dev\t')]
    foes = [CHAMPION] if zero_only else [CHAMPION, 'wtap', 'remote-004-counter-gru', 'sumo-seven-first', 'remote-003-counter-gru']
    pings = [(0, 0)] if zero_only else [(0, 0), (0, 80), (80, 0), (80, 80)]
    rng = random.Random(700477031 if zero_only else 2026112112)
    for foe in foes:
        for i, clock in enumerate(clocks):
            for rep in range(64 if zero_only else 4):
                for seat in 'ab':
                    for own, other in pings:
                        sa, sb = rng.getrandbits(58), rng.getrandbits(58)
                        for actor, arm in [(CHAMPION, 'auto-sprint'), (CHAMPION, 'intact'), ('wtap', 'intact')]:
                            yield ([foe, actor, arm, i, rep, seat, own, other, sa, sb],
                                   [field[actor]['reference'], field[foe]['reference'], clock, seat, own, other, sa, sb, arm, '-'])


def run(output, zero_only):
    output.mkdir(parents=True, exist_ok=True)
    if not PIN.is_dir():
        raise FileNotFoundError(f'This historical study uses the pinned league simulator at {PIN}')
    labels, fixtures = zip(*schedule(zero_only))
    (output / 'cells.json').write_text(json.dumps(labels))
    plan = output / 'cases.tsv'
    plan.write_text(''.join('\t'.join(map(str, row)) + '\n' for row in fixtures))
    subprocess.run(['javac', '-cp', PIN, '-d', output, ROOT / 'src/lab/DuelNetwork.java',
                    ROOT / 'src/lab/SumoLeaguePolicy.java', HERE / 'Gen4Review.java', HERE / 'TradeReview.java'], check=True)
    cp = os.pathsep.join(map(str, (output, PIN)))
    for main in ['Gen4Review', 'TradeReview']:
        path = output / (main + '.jsonl')
        subprocess.run(['java', '-Xmx640m', '-XX:ActiveProcessorCount=1', '-XX:+UseSerialGC', '-cp', cp,
                        main, plan, path], cwd=ROOT, check=True)
        with path.open('rb') as src, gzip.open(str(path) + '.gz', 'wb') as dest:
            dest.writelines(src)
        path.unlink()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--zero-only', action='store_true', help='Run the 1,024-seed zero-RTT follow-up against Gen 4')
    options = parser.parse_args()
    run(options.output.resolve(), options.zero_only)
