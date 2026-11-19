"""Fit the pressure policy on the predeclared development matches."""
import argparse
import json
import subprocess
from pathlib import Path
from run import ROOT, HERE, compile_model, java

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True, help='Fresh path for this fit')
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Choose a new output file so the registered fit remains reproducible')
    spec = json.loads((HERE/'revision-3.json').read_text())
    donors = []
    for mid in spec['train_donors']:
        sources = [d/(mid+'.txt.gz') for d in (HERE/'train-clocks',HERE/'league-clocks') if (d/(mid+'.txt.gz')).exists()]
        if len(sources) != 1:
            raise ValueError(f'Expected one clock for {mid}, found {len(sources)}')
        donors.extend(sources)
    compile_model()
    settings = [spec[k] for k in ('seed','generations','population','elite','train_scenarios_per_generation','finalists')]
    result = subprocess.run(java('League',*settings,*donors),cwd=ROOT,stdout=subprocess.PIPE,check=True)
    with args.output.open('xb') as f:
        f.write(result.stdout)
