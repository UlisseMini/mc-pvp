"""Register post-fit native replays from the existing tournament without requesting additional matches."""
import argparse
import gzip
import hashlib
import json
import subprocess
import sys
import time

from mmc_transport_fit import CONTROL, EVIDENCE, ROOT, collect, compact, compile_model, freeze, metrics


DEST = EVIDENCE.parent / 'transport'
WORK = ROOT / 'build/elite-league/mmc13'
MANIFEST = WORK / 'mmc13-confirmation.partial.json'
PROTOCOL = DEST / 'prospective-protocol.json'


def register():
    if PROTOCOL.exists():
        return
    manifest = json.loads(MANIFEST.read_text())
    status = json.loads((ROOT / 'runtime/status.json').read_text())
    if abs(time.time()-status['time']/1000) > 5:
        raise ValueError('Stale lab status')
    used = set(manifest['matches']) | {g.get('request') for g in status['matches']}
    if any(str(g.get('request')).startswith('m13centerconfirmation-') for g in status['matches']):
        raise ValueError('Register this panel between confirmation tranches')
    selected = {}
    for req, task in manifest['plan'].items():
        if req in used or task['opponent'] not in ('research-wtap20','adapt-04-pair'):
            continue
        cell = (task['arm'],task['opponent'],task['rtt'],task['side'])
        selected.setdefault(cell,req)
    if len(selected) != 40:
        raise ValueError(f'Expected 40 not-yet-played cells, got {len(selected)}')
    spec = dict(fit_sha256=hashlib.sha256((DEST/'fit.json').read_bytes()).hexdigest(),
                registered_ms=time.time_ns()//1_000_000, fixtures={req:list(k) for k,req in selected.items()},
                selection='First unissued request in the frozen native schedule per arm, opponent, seat and RTT. Five contenders face plain W-tap and adapt-04-pair. Register between cohorts. Retain any invalid or unscorable row in the denominator.',
                target='First two seconds, all positions and velocities on every native travel step for both players, from the measured inputs and host stages only. Check fixed timing and both fitted alternatives without changing them.')
    freeze(PROTOCOL,compact(spec))


def score(spec, matches):
    fit = DEST/'fit.json'
    if spec['fit_sha256'] != hashlib.sha256(fit.read_bytes()).hexdigest():
        raise ValueError('Transport parameters changed after registration')
    keys = list(dict.fromkeys([CONTROL,'0.35,0.35',*json.loads(fit.read_text()).values()]))
    compile_model()
    rows = {}
    for req,cell in spec['fixtures'].items():
        mid = matches[req]
        record = json.loads((ROOT / f'runtime/results/{mid}.json').read_text())
        if record['request'] != req or record['start'] <= spec['registered_ms']:
            raise ValueError(f'Prospective match identity or start is invalid on {mid}')
        row = dict(mid=mid,cell=cell)
        if record['reason'] not in ('ring_out','double_ring_out','timeout_draw'):
            row['unscored'] = record['reason']
        else:
            try:
                row['results'] = collect([mid],keys)[mid]
            except (OSError, IndexError, KeyError, StopIteration, ValueError) as e:
                row['unscored'] = repr(e)
        rows[req] = row
    splits = {}
    for req,r in rows.items():
        arm,opp,rtt,side = r['cell']
        for label in ('all',arm,'vs '+opp,f'{rtt}/{side}'):
            splits.setdefault(label,{})[req]=r
    summary = {}
    for label, group in splits.items():
        scorable = {k:v['results'] for k,v in group.items() if 'results' in v}
        summary[label] = dict(scheduled=len(group),unscored=len(group)-len(scorable),
                              scores={k:metrics(scorable,k) for k in keys} if scorable else {})
    freeze(DEST/'prospective.json.gz',gzip.compress(compact(dict(rows=rows,summary=summary)),mtime=0))
    print(summary['all'],flush=True)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('stage',choices=('register','wait','score'))
    stage=p.parse_args().stage
    if stage=='register':
        register()
    elif stage=='wait':
        spec = json.loads(PROTOCOL.read_text())
        deadline = json.loads((WORK/'admission-deadline.json').read_text())['unix_time']
        while time.time()<deadline:
            matches = json.loads(MANIFEST.read_text())['matches']
            if spec['fixtures'].keys() <= matches.keys():
                subprocess.run([sys.executable,__file__,'score'],check=True)
                break
            time.sleep(15)
    else:
        from cpu_limit import supervise_script
        supervise_script()
        score(json.loads(PROTOCOL.read_text()),json.loads(MANIFEST.read_text())['matches'])
