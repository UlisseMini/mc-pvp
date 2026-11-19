"""Run independent actor-critic fits and a population league on the registered training donors."""
import argparse
import json
import subprocess
from champions import population
from pathlib import Path
from run import ROOT,HERE,compile_model,java,calendars
from tournament import roster

def train_ppo(out):
    for seed,warm in [(330191,HERE/'frozen/mlp.net'),(752311,HERE/'challenge-frozen/anti-hold.net')]:
        subprocess.run(java('PPO',out/f'ppo-{seed}',768,48,seed,warm,HERE/'frozen',HERE/'challenge-frozen',
                            *calendars('training_clocks')),cwd=ROOT,check=True)

def train_population(out):
    # Frozen PPO opponents by fixed update index, never by prospective performance.
    ancestors={f'ppo-{seed}-g{k:04}':out/f'ppo-{seed}'/f'g{k:04}.net'
               for seed in (330191,752311) for k in (256,512,767)}
    path=out/'population-ancestors.txt';roster(path,ancestors)
    command=java('Population',out/'population',HERE/'frozen',HERE/'challenge-frozen',12,120,48,819336,4,
                 path,*calendars('training_clocks'))
    published=set()
    with subprocess.Popen(command,cwd=ROOT) as runner:
        while True:
            for record in sorted((out/'population').glob('epoch*/stage-selection.json')):
                if record in published or not record.stat().st_size:continue
                try:population(record.parent)
                except json.JSONDecodeError:continue
                published.add(record)
            if runner.poll() is not None:
                if runner.returncode:raise subprocess.CalledProcessError(runner.returncode,command)
                if len(published)!=12:raise ValueError('Incomplete population winner set')
                break
            try:runner.wait(timeout=2)
            except subprocess.TimeoutExpired:pass


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('out',type=Path);p.add_argument('mode',choices=('ppo','population','both'));a=p.parse_args()
    a.out.mkdir(exist_ok=True,parents=True);compile_model()
    if a.mode in ('ppo','both'):train_ppo(a.out)
    if a.mode in ('population','both'):train_population(a.out)
