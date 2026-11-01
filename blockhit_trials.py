#!/usr/bin/env python3
"""Recorded native diamond duels for the timed-block policy (no simulated combat)."""
import argparse
import asyncio
import hashlib
import json
import shutil
import time
import uuid

import experiments
from lab import ROOT, RUNTIME
from lab_runtime import match_events
from replay import export


async def run(args):
    out = ROOT / 'experiments' / ('blockhit-' + time.strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:6])
    out.mkdir()
    names = {args.bot, *args.opponents}
    sources = ['src/lab/Client.java', 'src/lab/Agent.java', 'src/lab/BlockhitController.java', 'experiments.py', 'blockhit_trials.py']
    sources += ['policies/' + n + '.properties' for n in sorted(names)]
    hashes = {}
    for f in sources:
        dest = out / f
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / f, dest)
        hashes[f] = hashlib.sha256((ROOT / f).read_bytes()).hexdigest()
    report = {'agent_sha256': hashlib.sha256((ROOT / 'build/lab-agent.jar').read_bytes()).hexdigest(),
              'source_hashes': hashes, 'arguments': vars(args), 'results': []}
    (out / 'report.json').write_text(json.dumps(report, indent=2))
    print(out, flush=True)
    semaphore = asyncio.Semaphore(args.parallel)

    async def trial(opponent, repeat):
        async with semaphore:
            # Reverse allocation order on alternate repeats to check side effects.
            reverse = repeat % 2 == 1
            a, b = (opponent, args.bot) if reverse else (args.bot, opponent)
            pa, pb = (args.opp_ping, args.ping) if reverse else (args.ping, args.opp_ping)
            result = await experiments.match(a, b, pa, pb, mode='diamond')
            side = 'b' if reverse else 'a'
            other = 'a' if reverse else 'b'
            path = RUNTIME / 'bots' / result[side].removeprefix('LabSlot') / 'events.jsonl'
            events = list(match_events(path, result['id']))
            (out / (result['id'] + '-client.jsonl')).write_text(''.join(json.dumps(e) + '\n' for e in events))
            shutil.copyfile(RUNTIME / 'replays' / (result['id'] + '.jsonl'), out / (result['id'] + '.jsonl'))
            server = [json.loads(line) for line in (out / (result['id'] + '.jsonl')).read_text().splitlines()]
            # Independently reconstruct attempt accuracy from native transition
            # and damage-reduction events; multiple blocked hits count once.
            accuracy_checks = {}
            for s in ('a', 'b'):
                attempts = successes = 0
                active = saved = False
                for e in server:
                    if e['type'] == 'block_attempt' and e['player'] == result[s]:
                        if e['blocking']:
                            assert not active, 'duplicate block begin'
                            attempts += 1
                            saved = False
                        active = e['blocking']
                    if e['type'] == 'blocked_damage' and e['target'] == result[s]:
                        assert active, 'blocked damage without an observed block attempt'
                        if not saved:
                            successes += 1
                            saved = True
                actual = result['stats_' + s]['block_accuracy']
                assert (attempts, successes) == (actual['attempts'], actual['successful_attempts'])
                accuracy_checks[s] = {'attempts': attempts, 'successes': successes, 'passed': True}
                melee = [e for e in server if e['type'] == 'melee_damage' and e['target'] == result[s]]
                blocked = sum(e['blocked'] for e in melee)
                assert len(melee) == result['stats_' + s]['melee_hits_taken']
                expected_percent = 100.0 * blocked / len(melee) if melee else None
                assert result['stats_' + s]['block_percent'] == expected_percent
                accuracy_checks[s]['block_percent_verified'] = expected_percent
            result['block_accuracy_validation'] = accuracy_checks
            plans = [e for e in events if e['type'] == 'blockhit_plan']
            durations = [min(100, y['time'] - x['time']) for x, y in zip(plans, plans[1:])]
            duty = sum(dt for e, dt in zip(plans, durations) if e['native_blocking']) / max(1, sum(durations))
            received = result['stats_' + side]['melee_hits_taken']
            result['blockhit_summary'] = {
                'bot_side': side, 'opponent': opponent, 'won': result['winner'] == result[side],
                'blocked': result['blocks_' + side], 'received_melee': received,
                'block_rate': result['blocks_' + side] / received if received else None,
                'native_block_duty': duty, 'bot_hp': result['health_' + side],
                'block_accuracy': result['stats_' + side]['block_accuracy'],
                'opponent_hp': result['health_' + other],
                'saved_hearts': result['stats_' + side]['damage_breakdown']['blocking_saved']['damage'] / 2,
            }
            report['results'].append(result)
            report['sources_unchanged'] = all(hashlib.sha256((ROOT / f).read_bytes()).hexdigest() == h for f, h in hashes.items())
            (out / 'report.json').write_text(json.dumps(report, indent=2))
            export(result['id'])
            print(json.dumps({'id': result['id'], **result['blockhit_summary']}), flush=True)

    outcomes = await asyncio.gather(*(trial(opp, rep) for rep in range(args.repeats) for opp in args.opponents), return_exceptions=True)
    report['errors'] = [str(e) for e in outcomes if isinstance(e, BaseException)]
    (out / 'report.json').write_text(json.dumps(report, indent=2))
    if report['errors']:
        raise RuntimeError(report['errors'])
    assert report['sources_unchanged'], 'Sources changed during the batch'
    print('Saved ' + str(out / 'report.json'), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bot', default='blockhit_timing')
    parser.add_argument('--opponents', nargs='+', default=['wtap', 'hold-w', 'blockhit_wtap_control'])
    parser.add_argument('--repeats', type=int, default=3)
    parser.add_argument('--ping', type=int, default=20)
    parser.add_argument('--opp-ping', type=int, default=20)
    parser.add_argument('--parallel', type=int, choices=[1, 2, 3], default=3)
    asyncio.run(run(parser.parse_args()))
