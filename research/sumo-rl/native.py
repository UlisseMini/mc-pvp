"""Run registered bot-only cohorts, retaining request IDs for replay and scoring."""
import argparse
import itertools
import json
from pathlib import Path
import random
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / 'research/sumo-strategies'))
import importlib.util
spec = importlib.util.spec_from_file_location('sumo_batch', ROOT / 'research/sumo-strategies/native.py')
batch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(batch)

def plan(prefix, clocks=False):
    rows = []
    groups = [('sumo','mmc','research-wtap20',2)] if clocks else [
        ('sumo','mmc','research-wtap20',4), ('sumo','mmc','research-hold20',2),
        ('sumo','mmc','trade-wtap',2), ('sumo','hypixel','research-wtap20',1),
        ('boxing','mmc','combo-wake-2000',2)]
    for mode, profile, opponent, repeats in groups:
        arms = ['research-wtap20'] if clocks else ['research-wtap20','sumo-rl-jump']
        for rep, rtt, side, arm in itertools.product(range(repeats), (0,80), 'ab', arms):
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
    args = p.parse_args()
    if args.output.exists() or not args.prefix.replace('-','').isalnum():
        p.error('Use a fresh prefix and manifest')
    trials = plan(args.prefix, args.clocks)
    if any(json.loads(f.read_text()).get('request') in trials for f in (batch.RUNTIME/'results').glob('*.json')):
        p.error('Request prefix already used')
    batch.run(trials, 3, args.output, reserve=2, share_with_players=True)
