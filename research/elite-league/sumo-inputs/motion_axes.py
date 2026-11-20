"""Measure direct dependence on radial, sideways and vertical self velocity with physical links intact."""
import base64
import csv
import shutil
import statistics
import struct
import subprocess
import sys
from live import D, B, ROOT, WT, ping, read, freeze, wait, sha, champions, fixtures, run_rows
from report import grouped, native, score, pct, pt, wld
from train import SEEDS, java

F = D / 'axes'
CHANNELS = {'radial': [2], 'sideways': [3], 'vertical': [4], 'all': [2, 3, 4]}

def export():
    F.mkdir(exist_ok=True)
    entrants = {'wtap': None, ping.OLD: D.parent / 'recorded-mmc-frozen/range-model' / (ping.OLD + '.net')}
    representative = read(D / 'entrants.json')['representatives']['sumo-input-base8']
    for seed in SEEDS:
        parent = f'base8-{seed}'
        path = D / 'fits' / (parent + '.net')
        entrants[parent] = path
        tag, raw = path.read_text().strip().split('.')
        weights = struct.unpack('>326f', base64.b64decode(raw))
        assert tag == 'INPUT8' and weights[-1] == 0
        for group, features in CHANNELS.items():
            probe = list(weights)
            for unit in range(8):
                for f in features:
                    probe[35*unit + 1 + f] = 0.
            name = f'no-{group}-{seed}'
            dest = F / (name + '.net')
            data = tag + '.' + base64.b64encode(struct.pack('>326f', *probe)).decode() + '\n'
            if dest.exists() and dest.read_text() != data:
                raise ValueError('Frozen probe differs ' + name)
            dest.write_text(data)
            entrants[name] = dest
            if parent == representative:
                champions.install('sumo-axis-no-' + group, dest, 'Own velocity probe ' + group, name, 'direct')
    freeze(F / 'hashes.json', {name: sha(p) for name, p in entrants.items() if p})
    (F / 'entrants.tsv').write_text(''.join(name + ('\t' + str(p.relative_to(ROOT)) if p else '') + '\n'
                                           for name, p in entrants.items()))

def format_results():
    models = grouped(csv.DictReader((F / 'transfer.csv').open()))
    rows = native('axes')
    out = ['# Removing individual own velocity readings', '',
           'Three base8 checkpoints, frozen weights, four new model clocks, ten opponents, and 320 fights per checkpoint. The model changes below count percentage points with half credit for a draw. Native records are wins–losses–draws. Each native policy faced each foe four times, in both seats and on both asymmetric links.', '',
           '| Direct reading removed | Model change by training seed | Model mean | Native vs W-tap | Native vs shift-check | Native A / B | Native 31/109 / 109/31 | Plain W-tap model / native vs both |',
           '|---|---:|---:|---:|---:|---:|---:|---:|']
    baseline = pct(score(list(models['wtap'].values()))) + ' / ' + wld(list(rows[WT].values()))
    for group in ['intact', *CHANNELS]:
        arm = 'sumo-input-base8' if group == 'intact' else 'sumo-axis-no-' + group
        diffs, means = [], []
        for seed in SEEDS:
            orig = models[f'base8-{seed}']
            current = orig if group == 'intact' else models[f'no-{group}-{seed}']
            diffs.append(statistics.mean(current[k] - orig[k] for k in orig))
            means.append(score(list(current.values())))
        v = rows[arm]
        def cell(i, value):return wld([y for k, y in v.items() if k[i] == value])
        out.append('| ' + ' | '.join([group, ' / '.join(map(pt, diffs)), pct(statistics.mean(means)),
             cell(0, WT), cell(0, 'shift-check-v4-4'), cell(1, 'a') + ' / ' + cell(1, 'b'),
             cell(2, 31) + ' / ' + cell(2, 109), baseline]) + ' |')
    out.extend(['', 'Neutralizing a component tests how the frozen actor uses its direct reading. Because the actor can still observe range, height, ground state and hurt cues, this intervention permits indirect estimates. The initial group-level result motivated this follow-up. These four model clocks and the native bouts are fresh.', ''])
    (F / 'results.md').write_text('\n'.join(out))

def main():
    export()
    keys = [f'sinput-f-{WT}-0-{pair}-{s}' for pair in range(2) for s in 'ab']
    path = D / 'confirmation.json'
    wait(lambda: path.exists() and set(keys) <= read(path)['matches'].keys())
    a = read(path)
    manifest = F / 'donors.json'
    freeze(manifest, dict(plan={q:a['plan'][q] for q in keys}, matches={q:a['matches'][q] for q in keys}))
    subprocess.run([sys.executable, ROOT / 'research/sumo-rl/calendars.py', B / 'axes-clocks',
                    '--frozen-manifest', manifest], check=True)
    clocks = []
    for src in sorted((B / 'axes-clocks').glob('m*.txt.gz')):
        shutil.copyfile(src, F / src.name)
        clocks.append(str((F / src.name).relative_to(ROOT)))
    assert len(clocks) == 4
    (F / 'clocks.txt').write_text('\n'.join(clocks)+'\n')
    if not (F / 'transfer.csv').exists():
        subprocess.run(java('InputProbe', F), cwd=ROOT, check=True)
    wait(lambda: (D / 'historical-title.json').exists())
    names = ['sumo-input-base8', WT] + ['sumo-axis-no-' + n for n in CHANNELS]
    run_rows('axes', fixtures('v', names, [WT, 'shift-check-v4-4']))
    subprocess.run([sys.executable, D.parent / 'cpu_limit.py', '--audit', sys.executable, '-c',
                    "import sys;sys.path.insert(0,sys.argv[1]);from audit_run import certify;certify('axes')", str(D)], check=True)
    format_results()

if __name__ == '__main__':
    main()
