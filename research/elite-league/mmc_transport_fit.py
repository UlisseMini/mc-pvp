"""Test whether two fixed packet-family delays transfer without access to recorded packet timings."""
import gzip
import hashlib
import itertools
import json

from cpu_limit import supervise_script
from mmc_packet_fidelity import EVIDENCE, ROOT, compare, compile_model, freeze


DELAYS = (0, .35, .75, 1.5, 3, 6, 12, 25)
CONTROL = 'fixed'


def compact(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()


def collect(ids, settings):
    rows = {}
    args = [(.35,.35) if key == CONTROL else 'family='+key for key in settings]
    for mid in ids:
        results = compare(mid, args, 'recorded')
        if not isinstance(results, list) or len(results) != len(args):
            raise ValueError(f'Missing forecast {mid}: {results}')
        rows[mid] = {key: dict(exact=r['exact'], error=r['max_position_error'], moves=r['moves'])
                     for key, r in zip(settings, results, strict=True)}
        if rows[mid][CONTROL] != rows[mid]['0.35,0.35']:
            raise AssertionError(f'Equal-overhead control changed on {mid}')
    return rows


def metrics(rows, key):
    games = [v[key] for v in rows.values()]
    return dict(exact=sum(g['exact'] for g in games), n=len(games),
                mean_max_error=sum(g['error'] for g in games)/len(games))


def run():
    previous = json.loads((EVIDENCE / 'sprint-drain-protocol.json').read_text())['panels']
    training = {mid: cell for panel in previous.values() for cell, mid in panel.items()}
    manifest = json.loads((ROOT / 'build/elite-league/mmc13/mmc13-opening.json').read_text())
    by_cell = {}
    for req, mid in manifest['matches'].items():
        g = manifest['plan'][req]
        cell = f"{g['arm']} {g['rtt']} {g['side']}"
        if mid not in training:
            by_cell.setdefault(cell, []).append((hashlib.sha256(req.encode()).hexdigest(),mid))
    validation = {min(values)[1]:cell for cell,values in by_cell.items()}
    if len(training) != 24 or len(validation) != 12 or set(validation.values()) != set(training.values()):
        raise ValueError('Incomplete or differing match strata')
    dest = EVIDENCE.parent / 'transport'
    dest.mkdir(exist_ok=True)
    protocol = dict(training=training, validation=validation, delays_ms=DELAYS,
        method='Generated S12 velocity and flags-bearing own S1C get separate fixed residuals on top of half RTT. All other packets use 0.35 ms. Preserve FIFO on each route. Input consists of real controls and host stages. Real packet timings are withheld.',
        split='Fit only on the 24 previously diagnosed native openings. Take the smallest SHA256 of request ID among remaining original opening bouts in each arm/seat/RTT cell. Match-level budgets precede the later comparison. All 36 are from the earlier development tournament.',
        ranking='Maximize entirely exact two-player openings on training. Then minimize mean maximum position error across bouts. Break ties by smaller total absolute departure from the fixed 0.35 ms reference then by the two delays. Also fit the restricted equal-delay family. Freeze choices before replaying the 12 additional openings. Report every result and retain the baseline simulator for tournaments.')
    freeze(dest / 'protocol.json', compact(protocol))
    compile_model()
    pairs = {f'{v},{s}':(v,s) for v,s in itertools.product(DELAYS,repeat=2)}
    train_file = dest / 'training.json.gz'
    if not train_file.exists():
        training_rows = collect(training, [CONTROL,*pairs])
        freeze(train_file, gzip.compress(compact(training_rows), mtime=0))
    training_rows = json.loads(gzip.decompress(train_file.read_bytes()))
    if set(training_rows)!=set(training):
        raise ValueError('Training rows differ from registered split')
    def order(key):
        s = metrics(training_rows, key)
        v, f = pairs[key]
        return -s['exact'],s['mean_max_error'],abs(v-.35)+abs(f-.35),v,f
    choice = min(pairs,key=order)
    equal = min((key for key,(v,s) in pairs.items() if v==s), key=order)
    selected = dict(unrestricted=choice, shared=equal)
    freeze(dest / 'fit.json',compact(selected))
    keys = list(dict.fromkeys([CONTROL,'0.35,0.35',equal,choice]))
    validation_rows = collect(validation, keys)
    freeze(dest / 'validation.json.gz',gzip.compress(compact(validation_rows),mtime=0))
    summary = {}
    for label, data, cells in [('training', training_rows, training), ('validation', validation_rows, validation)]:
        splits = {'all':data}
        for mid, cell in cells.items():
            arm,rtt,side = cell.split()
            for subgroup in (arm,f'{rtt}/{side}'):
                splits.setdefault(subgroup,{})[mid]=data[mid]
        summary[label] = {g:{key:metrics(rs,key) for key in keys} for g,rs in splits.items()}
    freeze(dest / 'summary.json', compact(summary))
    print(selected,summary['training']['all'],summary['validation']['all'],flush=True)


if __name__ == '__main__':
    supervise_script()
    run()
