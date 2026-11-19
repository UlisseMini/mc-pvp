"""Audit both clients before certifying the native sumo final, retaining every invalid attempt."""
import argparse
import fcntl
import json
import subprocess
import sys
import time
from pathlib import Path

from adaptation import dump, freeze, sha
from audit import one
from movement_audit import legacy
from native_trials import run_batch
from results import native
from run import HERE, ROOT


def read(path):
    return json.loads(path.read_text())


def verify_match(mid, trial, scores):
    audit_dir = scores / 'movement'
    audit_dir.mkdir(parents=True, exist_ok=True)
    destination = audit_dir / (mid + '.json')
    if destination.exists():
        return read(destination)
    record = read(ROOT / 'runtime/results' / (mid + '.json'))
    if record['reason'] == 'invalid_client_view':
        result = dict(mid=mid, request=record['request'], valid=False, reason=record['reason'], sides={})
    else:
        score_path = scores / (mid + '.json')
        if not score_path.exists():
            with (ROOT / 'build/elite-league/replay-scoring.lock').open('a') as lock:
                fcntl.flock(lock, fcntl.LOCK_EX)
                if not score_path.exists():
                    dump(score_path, one(mid, trial))
        score = read(score_path)
        if score['mid'] != mid or any(score[k] != v for k, v in trial.items() if k != 'command'):
            raise ValueError('Input audit does not match fixture ' + mid)
        checks = {}
        for seat in 'ab':
            policy = record['policy_' + seat]
            if seat in score['bindings']:
                verified = score['bindings'][seat]
                count = verified['verified_move_heads']
                coverage = verified.get('move_coverage')
                if coverage and (coverage['missing_inputs'] or coverage['missing_moves']):
                    raise ValueError('Missing interior network or movement samples in ' + mid)
                if not count:
                    raise ValueError('No verified neural movements in ' + mid)
                checks[seat] = dict(policy=policy, method='frozen network replay and actual movement',
                                    heads=count, disagreements=verified['mismatches'])
                if coverage:
                    checks[seat]['coverage'] = coverage
            elif policy in ('research-wtap20', 'research-hold20') or (
                    trial.get('mode')=='boxing' and trial.get('gate_k') in (3,5,10)
                    and policy==trial['opponent'] and policy==f"breadth-{trial.get('defender_style')}-wake{trial['gate_k']}"
                    and record['parameters_'+seat]['kind'] in ('wtap','hold','tradetap')):
                end_tick=(trial.get('gate_k') in (3,5,10) and policy==trial['opponent']
                          and record['parameters_'+seat].get('science_trace','0')!='1')
                checks[seat] = dict(policy=policy, **legacy(mid, seat, record, observed_keys=end_tick))
            else:
                raise ValueError('Unaudited controller ' + policy)
        result = dict(mid=mid, request=record['request'], sides=checks,
                      valid=all(not side['disagreements'] for side in checks.values()))
    dump(destination, result)
    return result


def scan(manifest, scores):
    # This also accepts a live partial manifest, whose completed results only grow.
    data = read(manifest)
    for request, mid in data['matches'].items():
        while True:
            try:
                row = verify_match(mid, data['plan'][request], scores)
                break
            except (OSError, EOFError, ValueError):
                finish = read(ROOT / 'runtime/results' / (mid + '.json'))['finish']
                if time.time() - finish / 1000 > 60:
                    raise
                time.sleep(3)
        if row['request'] != request:
            raise ValueError('Audit cache belongs to a different fixture ' + mid)


def governed_scan(manifest, scores):
    subprocess.run([sys.executable, str(HERE / 'cpu_limit.py'), '--audit', sys.executable,
                    __file__, str(manifest), str(scores)], check=True)


def eligible(original, manifest, evidence, scores):
    protocol = evidence / 'input-protocol.json'
    schedule = read(manifest)
    if not protocol.is_file() or schedule['matches'].keys() != schedule['plan'].keys():
        raise ValueError('Missing final control protocol or unfinished schedule')
    bases = {request: request for request in schedule['plan']}
    accepted = {}
    lineage = {base: [] for base in bases}
    pinned = original.with_suffix('.build.json').read_bytes()
    current = manifest
    for attempt in range(4):
        governed_scan(current, scores)
        batch = read(current)
        if batch['matches'].keys() != bases.keys() or batch['plan'].keys() != bases.keys():
            raise ValueError('Input audit retry schedule changed')
        retry = {}
        next_bases = {}
        for request, base in bases.items():
            mid = batch['matches'][request]
            audit = read(scores / 'movement' / (mid + '.json'))
            lineage[base].append(audit)
            if audit['valid']:
                accepted[base] = (request, mid, batch['plan'][request])
                continue
            retry_id = f'{base}-input-retry{attempt+1}'
            words = schedule['plan'][base]['command'].split()
            words[words.index(base)] = retry_id
            retry[retry_id] = dict(schedule['plan'][base], command=' '.join(words))
            next_bases[retry_id] = base
        if not retry:
            break
        dump(evidence / f'input-retry{attempt+1}-schedule.json',
             dict(protocol=sha(protocol), rejected={b: lineage[b][-1] for b in next_bases.values()}, plan=retry))
        if attempt == 3:
            raise ValueError('Repeated control failures in the same final fixture')
        if attempt == 0 and not (evidence / 'native-before-input-repair.json.gz').exists():
            native(manifest, scores, evidence / 'native-before-input-repair.json.gz')
        current = original.with_name(original.stem + f'-input-retry{attempt+1}.json')
        freeze(current.with_suffix('.build.json'), pinned)
        run_batch(retry, current)
        bases = next_bases
    if accepted.keys() != schedule['plan'].keys() or len({m for _, m, _ in accepted.values()}) != len(accepted):
        raise ValueError('Missing or duplicated eligible final matches')
    dump(evidence / 'input-audit.json', dict(protocol=sha(protocol), original=sha(original),
         referee_eligible=sha(manifest), attempts=lineage))
    if all(len(v) == 1 for v in lineage.values()):
        return manifest
    result = original.with_name(original.stem + '-verified.json')
    dump(result, dict(plan={q: p for q, _, p in accepted.values()},
                      matches={q: m for q, m, _ in accepted.values()}))
    return result


def follow(manifest, scores):
    deadline = read(manifest.parent / 'admission-deadline.json')['unix_time']
    cached = set()
    while True:
        if manifest.is_file():
            data = read(manifest)
            if set(data['matches']) != cached:
                governed_scan(manifest, scores)
                cached = set(data['matches'])
            if data['matches'].keys() == data['plan'].keys():
                return
        if time.time() >= deadline + 180:
            return
        time.sleep(20)


def follow_registered():
    from mmc_league import WORK, registered_control_stages
    deadline = read(WORK / 'admission-deadline.json')['unix_time']
    completed = {}
    while time.time() < deadline + 180:
        for tag in registered_control_stages():
            name = 'mmc13-' + tag
            current = WORK / (name + '.partial.json')
            if not current.is_file():continue
            trial = read(current)
            count = len(trial['matches'])
            if count == completed.get(tag,0):continue
            governed_scan(current,WORK/(name+'-scores'))
            completed[tag] = count
        time.sleep(12)


if __name__ == '__main__':
    if sys.argv[1:]==['--follow-registered']:
        follow_registered();sys.exit(0)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest', type=Path)
    parser.add_argument('scores', type=Path)
    parser.add_argument('--follow', action='store_true')
    args = parser.parse_args()
    (follow if args.follow else scan)(args.manifest, args.scores)
