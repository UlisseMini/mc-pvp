"""Link accepted regulation hits to the movement controller at physical click consumption."""

import argparse
from collections import Counter
from itertools import groupby
import json
from pathlib import Path
import sys

from audit import captures
from run import ROOT

sys.path.insert(0, str(ROOT))
from trade_return_windows import attack_kind, link_clicks


def capture(mid, side, source):
    path, = (ROOT / 'runtime/pov' / mid).glob(f'{side}-client-*-events.jsonl.gz')
    kinds = {'click', 'science_click', 'engine_attack'}
    if source:
        kinds.add('duel_network')
    return [e for e in captures.events(path, kinds) if e.get('match') == mid]


def episodes(hits, gap):
    current = []
    all_runs = []
    for _, entries in groupby(hits, key=lambda hit: hit['tick']):
        batch = list(entries)
        sides = {hit['ours'] for hit in batch}
        if len(sides) != 1:
            if current:
                all_runs.append(current)
            current = []
            continue
        for hit in batch:
            if current and (hit['ours'] != current[0]['ours'] or hit['ms'] - current[-1]['ms'] > gap):
                all_runs.append(current)
                current = []
            if hit['kind'] == 'fresh':
                current.append(hit)
    if current:
        all_runs.append(current)
    return all_runs


def analyze(mid, setup):
    with (ROOT / 'runtime/replays' / f'{mid}.jsonl').open() as stream:
        raw = list(map(json.loads, stream))
    match = next(e['result'] for e in raw if e['type'] == 'finish')
    side = setup['side']
    seats = (side, 'b' if side == 'a' else 'a')
    parameters = match[f'parameters_{side}']
    handoff = int(parameters.get('duel_network_wtap_after', 0)) > 0
    if match['reason'] != '100_hits':
        return {'match': mid, 'reason': match['reason'], 'seat': side, 'rtt': setup['rtt'], 'arm': setup['arm']}

    links = []
    diagnostics = []
    for i, s in enumerate(seats):
        stream = capture(mid, s, handoff and i == 0)
        _, attacks, check = link_clicks(raw, stream, match, s)
        links.append(attacks)
        diagnostics.append(check)
        if i == 0:
            heads = {e['tick']: e for e in stream if e['type'] == 'duel_network'}
    if any(d['errors'] for d in diagnostics):
        raise ValueError(f'{mid}: ambiguous click links {diagnostics}')

    hits = []
    for idx, event in enumerate(raw):
        if event['type'] != 'science_server_attack':
            continue
        kind = attack_kind(event, match)
        if kind not in ('fresh', 'upgrade'):
            continue
        ours = event['attacker'] == match[side]
        click = links[0 if ours else 1].get(idx)
        head = heads.get(click['native_tick']) if ours and click else None
        controller = 'wtap' if not ours or not handoff else 'unknown'
        if head:
            controller = 'wtap' if head['wtap_continuation'] else 'network'
            if not head['released']:
                raise ValueError(f'{mid}: unreleased network in regulation')
        hits.append(dict(ms=event['time']-match['start'], tick=event['server_tick'], ours=ours, kind=kind,
                         sprint=event['attacker_before']['sprint'], controller=controller, click=click,
                         velocity=event.get('velocity_packet')))
    assert [sum(h['ours'] == own for h in hits) for own in (True, False)] == [match[f'hits_{s}'] for s in seats]
    assert heads or not handoff
    runs = episodes(hits, 1100)
    longest = []
    for own in (True, False):
        run = max((r for r in runs if r[0]['ours'] == own), key=len, default=[])
        longest.append(run)
    chain5 = next((r for r in runs if len(r) >= 5), [])
    return {
        'match': mid, 'rtt': setup['rtt'], 'seat': side, 'rep': setup['rep'], 'arm': setup['arm'],
        'score': [match[f'hits_{s}'] for s in seats],
        'heads': {label: sum(e['wtap_continuation'] == gate for e in heads.values())
                  for gate, label in ((False, 'network'), (True, 'wtap'))} if heads else {},
        'own_source': Counter(e['controller'] for e in hits if e['ours']),
        'chain5': {'ours': chain5[0]['ours'], 'first': chain5[0]['ms'], 'fifth': chain5[4]['ms']} if chain5 else None,
        'longest': [dict(start=r[0]['ms'], finish=r[-1]['ms'], length=len(r),
                         first=r[0], second=r[1] if len(r) >= 2 else None,
                         source_after_two=Counter(h['controller'] for h in r[2:])) if r else None for r in longest],
        'runs_five_or_more': [sum(r[0]['ours'] == our and len(r) >= 5 for r in runs) for our in (True, False)],
        'run_points_after_two': [sum(max(0, len(r)-2) for r in runs if r[0]['ours'] == our) for our in (True, False)],
        'link_checks': diagnostics,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest', type=Path)
    parser.add_argument('destination', type=Path)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    report = {}
    for req, mid in manifest['matches'].items():
        setup = manifest['plan'][req]
        if setup['match_format'] != 'first-to-100':
            continue
        report[mid] = analyze(mid, setup)
        row = report[mid]
        print(mid, row.get('score'), row.get('heads'), row.get('chain5'), flush=True)
    tmp = args.destination.with_suffix('.tmp')
    tmp.write_text(json.dumps(report, indent=2) + '\n')
    tmp.replace(args.destination)


if __name__ == '__main__':
    main()
