"""Fit the final movement/timing policy on the predeclared training matches only."""
import json,subprocess
from pathlib import Path
from run import HERE,compile_model,java
if __name__=='__main__':
    spec=json.loads((HERE/'revision-3.json').read_text());compile_model()
    donors=[]
    for mid in spec['train_donors']:
        sources=[d/(mid+'.txt.gz') for d in (HERE/'train-clocks',HERE/'league-clocks') if (d/(mid+'.txt.gz')).exists()]
        if len(sources)!=1:raise ValueError(mid)
        donors+=sources
    args=[spec[k] for k in ('seed','generations','population','elite','train_scenarios_per_generation','finalists')]
    result=subprocess.run(java('League',*args,*donors),stdout=subprocess.PIPE,check=True)
    with (HERE/'league-fit.json').open('xb') as f:f.write(result.stdout)
