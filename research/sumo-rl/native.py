"""Run registered bot-only cohorts, retaining request IDs for replay and scoring."""
import argparse
import hashlib
import itertools
import json
from pathlib import Path
import random
import sys
import time

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / 'research/sumo-strategies'))
import importlib.util
spec = importlib.util.spec_from_file_location('sumo_batch', ROOT / 'research/sumo-strategies/native.py')
batch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(batch)

def plan(prefix, clocks=False, policy="sumo-rl", lateral=False):
    rows = []
    groups = [('sumo','mmc','research-wtap20',2)] if clocks else [
        ('sumo','mmc','research-wtap20',4), ('sumo','mmc','research-hold20',2),
        ('sumo','mmc','trade-wtap',2), ('sumo','hypixel','research-wtap20',1),
        ('boxing','mmc','combo-wake-2000',2)]
    if not clocks and policy=='sumo-rl-pressure':
        groups.append(('sumo','mmc','jump-reset',2))
    if lateral:
        if clocks or policy!='sumo-rl-pressure':raise ValueError('Lateral check requires frozen league policy')
        groups=[('sumo','mmc','research-zest-strafe',4)]
    for mode, profile, opponent, repeats in groups:
        arms = ['research-wtap20'] if clocks else ['research-wtap20',policy]
        if policy=='sumo-rl-pressure' and mode=='sumo' and profile=='mmc' and opponent in ('research-wtap20','research-hold20') and not clocks:
            arms.append('research-hold20')
        for rep, rtt, side, arm in itertools.product(range(repeats), (0,80), 'ab', arms):
            if arm=='research-hold20' and rep>=2:continue
            name = f'{prefix}-{mode}-{profile}-{opponent}-{rep}-{rtt}-{side}-{arm}'
            a, b = (arm,opponent) if side == 'a' else (opponent,arm)
            start = f'lab sumo {a} {b} {rtt} {rtt}' if mode == 'sumo' else f'lab scenario {a} {b} {rtt} {rtt} 20000'
            command = f'{start} {name} preset={profile} reach=3 cps=20 tapdelay=0 aimdelay=0 aimnoise=0 aimrate=0'
            rows.append((name,dict(mode=mode,profile=profile,opponent=opponent,arm=arm,rep=rep,rtt=rtt,side=side,command=command)))
    random.Random(23113).shuffle(rows)
    return dict(rows)

if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('output', type=Path)
    p.add_argument('--prefix', required=True)
    p.add_argument('--clocks', action='store_true')
    p.add_argument('--lateral', action='store_true')
    p.add_argument('--policy', choices=['sumo-rl','sumo-rl-jump','sumo-rl-pressure'], default='sumo-rl')
    args = p.parse_args()
    if args.output.exists() or not args.prefix.replace('-','').isalnum():
        p.error('Use a fresh prefix and manifest')
    trials = plan(args.prefix, args.clocks, args.policy, args.lateral)
    if any(json.loads(f.read_text()).get('request') in trials for f in (batch.RUNTIME/'results').glob('*.json')):
        p.error('Request prefix already used')
    if not args.clocks:
        fit=json.loads((HERE/{'sumo-rl':'fit.json','sumo-rl-jump':'v1/fit.json','sumo-rl-pressure':'league-fit.json'}[args.policy]).read_text())
        settings=(ROOT/'policies'/f'{args.policy}.properties').read_text().splitlines()
        if f"sumo_jump_mask={fit['mask']}" not in settings:raise ValueError("Policy file differs from frozen fit")
        expected=hashlib.sha256((ROOT/'build/practice-client/practice-bridge.jar').read_bytes()).hexdigest()
        deadline=time.monotonic()+120
        while time.monotonic()<deadline:
            status=json.loads((batch.RUNTIME/'status.json').read_text())
            manager=json.loads((batch.RUNTIME/'manager.json').read_text())
            if all(f'LabSlot{i}' in status['players'] and
                   (lambda h:h['bridge_sha256']==expected and time.time()-h['time']/1000<5)(
                       json.loads((batch.RUNTIME/f'bots/{i}/heartbeat.json').read_text()))
                   for i in range(manager['bot_slots'])):break
            time.sleep(1)
        else:raise RuntimeError("Bot pool did not load the same current bridge")
    batch.run(trials, 3, args.output, reserve=2, share_with_players=True)
