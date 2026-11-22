"""Test how the unseen-opponent predictor responds to a hidden tactic change."""

import argparse
import random
import subprocess

import numpy as np
import torch

from prepare import BUILD, HERE, ROOT, OUT, decode, load, save
from train import Panel, Predictor, measure, summarize

SWITCH_MS = 2000
TIME_BANDS = ((0,1800), (2000,2300), (2300,2800), (2800,3800), (3800,6000))
PAIRS = (('sumo-seven-first','v3-memory-27'),('shift-check-v4-4','sumo-seven-ppo'))


def make(setup):
    folder = OUT/'switch'
    folder.mkdir(exist_ok=True)
    protocol = folder/'protocol.json'
    frozen = HERE/'switch-protocol.json'
    if not protocol.exists():
        save(protocol,load(frozen) if frozen.exists() else dict(pairs=PAIRS,switch_ms=SWITCH_MS,score_bands_ms=TIME_BANDS,
                           clocks=setup['clocks']['test'],seats=['a','b'],rtts=setup['rtt_pairs'],
                           note='Diagnostic on the held-out donor panel. Both policies receive the matching realized prefix.'))
    config = load(protocol)
    if frozen.exists() and config!=load(frozen):
        raise ValueError('Cached switch fixtures differ from the frozen panel')
    rng = random.Random(459127)
    fixtures = []
    for a,b in config['pairs']:
        for start,change in ((a,b),(b,a)):
            for j,clock in enumerate(config['clocks']):
                for cell,(own,other) in enumerate(config['rtts']):
                    for side in config['seats']:
                        actor = setup['actors'][(j+cell+(2 if side=='b' else 0))%len(setup['actors'])]
                        seeds = rng.getrandbits(60),rng.getrandbits(60)
                        # Both arms use the same priming wrapper and seed.
                        prefix = (actor,start,clock,side,own,other,*seeds,change)
                        fixtures.extend([(*prefix,500_000),(*prefix,config['switch_ms'])])
    if not (OUT/'switch.npz').exists():
        source = BUILD/setup['source']
        classes = folder/'classes'
        classes.mkdir(exist_ok=True)
        subprocess.run(['javac','-J-XX:ActiveProcessorCount=1','-proc:none','-cp',source,
                        '-d',classes,HERE/'PredictionTrace.java'],check=True,cwd=ROOT)
        schedule = folder/'fixtures.tsv'
        schedule.write_text(''.join('\t'.join(map(str,row))+'\n' for row in fixtures))
        wire = folder/'fixture.wire'
        subprocess.run(['java','-XX:ActiveProcessorCount=1','-Xmx640m','-cp',f'{classes}:{source}',
                        'PredictionTrace',schedule,wire],cwd=ROOT,check=True)
        decode(wire.read_bytes(),fixtures,OUT/'switch.npz',setup,'test')
        wire.unlink()
    panel = Panel('switch',setup)
    for stay,change in zip(panel.cuts[::2][:-1],panel.cuts[1::2]):
        length = np.searchsorted(panel.time[stay:change],config['switch_ms']-100,side='left')
        if not (np.array_equal(panel.x[stay:stay+length],panel.x[change:change+length])
                and np.array_equal(panel.y[stay:stay+length],panel.y[change:change+length])):
            raise ValueError('A tactic switch altered the observations or labels before its release')
    return panel


def score(panel,setup):
    ids = np.arange(len(panel.foe))
    results = {}
    for seed in setup['seeds']:
        mean,scale = [np.asarray(x,dtype=np.float32) for x in load(OUT/f'normalization-{seed}.json')['seen']]
        predictions = {}
        for name,kind in [('unseen_mlp','mlp'),('unseen_gru','gru')]:
            model = Predictor(kind,panel.x.shape[1],len(setup['foes']))
            model.load_state_dict(torch.load(OUT/'weights'/f'{seed}-{name}.pt',weights_only=True)['weights'])
            predictions[name] = measure(model,panel,ids,mean,scale,store=True)
            if kind=='gru':
                predictions['unseen_reset'] = measure(model,panel,ids,mean,scale,reset=True,store=True)
        arm = np.repeat(ids%2,panel.length)
        rounds = {}
        for lo,hi in TIME_BANDS:
            for condition in (0,1):
                for name,(loss,correct) in predictions.items():
                    lv,_,n = summarize(panel,loss,correct,(arm==condition)&(panel.time>=lo)&(panel.time<hi))
                    eligible = n>0
                    key = f'{lo}-{hi}:{"switch" if condition else "stay"}'
                    rounds.setdefault(key,{})[name] = dict(bouts=int(eligible.sum()),
                        mean=float(lv[eligible].mean()),by_action=lv[eligible].mean(0).tolist())
        results[str(seed)] = rounds
    save(OUT/'switch'/'results.json',results)
    print('Saved hidden-switch results',flush=True)


if __name__=='__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prepare-only',action='store_true')
    args = parser.parse_args()
    setup = load(OUT/'protocol.json')
    panel = make(setup)
    if not args.prepare_only:
        score(panel,setup)
