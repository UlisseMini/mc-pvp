"""Check whether using memory only for lateral and click forecasts transfers."""

import argparse
import random
import subprocess

import numpy as np
import torch
from torch import nn

from prepare import BUILD, HERE, LEAGUE, OUT, ROOT, decode, hash_file, load, save
from train import Panel, Predictor, SIZES, measure, summary


class Selective(nn.Module):
    def __init__(self,generic,history):
        super().__init__()
        self.generic = generic
        self.history = history

    def forward(self,x,short,foe,reset=False):
        base = self.generic(x,short,foe).split(SIZES,dim=-1)
        context = self.history(x,short,foe,reset=reset).split(SIZES,dim=-1)
        return torch.cat((base[0],context[1],context[2],base[3]),dim=-1)


def collect(setup):
    folder = OUT/'selective'
    folder.mkdir(exist_ok=True)
    path = folder/'protocol.json'
    frozen = HERE/'selective-protocol.json'
    if not path.exists() and frozen.exists():
        save(path,load(frozen))
    if not path.exists():
        donor_dir = LEAGUE/'evidence/id-oracle/recognition/payoff/replication/spaced'
        remaining = [p for p in sorted(donor_dir.glob('m*.txt.gz'),key=lambda p:int(p.name.split('.')[0][1:]))
                     if str(p.relative_to(ROOT)) not in setup['clock_hashes']]
        clocks = [str(p.relative_to(ROOT)) for p in remaining[:12]]
        if len(clocks)<8:
            raise ValueError('A separate never-example clock panel is required')
        save(path,dict(clocks=clocks,clock_hashes={p:hash_file(ROOT/p) for p in clocks},
                       rule='Generic MLP for forward/sprint and jump. Frozen GRU for strafe and physical attack.',
                       scope='Follow-up driven by the first model result. Same unseen opponents on different archived clocks.'))
    config = load(path)
    if frozen.exists() and config!=load(frozen):
        raise ValueError('Cached selective fixtures differ from the frozen panel')
    if any(hash_file(ROOT/file)!=digest for file,digest in config['clock_hashes'].items()):
        raise ValueError('Frozen selective timing recording changed')
    if (OUT/'selective.npz').exists():
        return
    foes = [f for f in setup['foes'] if setup['families'][f] in setup['unseen']]
    rng = random.Random(250157)
    fixtures = []
    for k,clock in enumerate(config['clocks']):
        for j,(a,b) in enumerate(setup['rtt_pairs']):
            for side in ('a','b'):
                actor = setup['actors'][(k+j+(2 if side=='b' else 0)) % len(setup['actors'])]
                seeds = rng.getrandbits(60),rng.getrandbits(60)
                fixtures.extend((actor,foe,clock,side,a,b,*seeds) for foe in foes)
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
    decode(wire.read_bytes(),fixtures,OUT/'selective.npz',{'foes':setup['foes'],'actors':setup['actors'],
            'clocks':{'selective':config['clocks']}},'selective')
    wire.unlink()


def run(setup):
    panel = Panel('selective',setup)
    ids = np.arange(len(panel.foe))
    for seed in setup['seeds']:
        stats = [np.asarray(x,dtype=np.float32) for x in load(OUT/f'normalization-{seed}.json')['seen']]
        trained = {}
        predictions = {}
        for name,kind in [('unseen_mlp','mlp'),('unseen_gru','gru')]:
            model = Predictor(kind,panel.x.shape[1],len(setup['foes']))
            model.load_state_dict(torch.load(OUT/'weights'/f'{seed}-{name}.pt',weights_only=True)['weights'])
            trained[name] = model
            predictions[name] = measure(model,panel,ids,*stats,store=True)
            if kind=='gru':
                predictions['unseen_reset'] = measure(model,panel,ids,*stats,store=True,reset=True)
        selective = Selective(trained['unseen_mlp'],trained['unseen_gru'])
        predictions['unseen_selective'] = measure(selective,panel,ids,*stats,store=True)
        predictions['unseen_selective_reset'] = measure(selective,panel,ids,*stats,store=True,reset=True)
        save(OUT/'selective'/f'results-{seed}.json',summary(panel,predictions,setup))


if __name__=='__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prepare-only',action='store_true')
    args = parser.parse_args()
    setup = load(OUT/'protocol.json')
    collect(setup)
    if not args.prepare_only:
        run(setup)
