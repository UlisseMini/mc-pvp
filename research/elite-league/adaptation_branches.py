"""Audit switch/no-switch causal forks for the frozen adaptive generalist."""
import argparse
import gzip
import json
from pathlib import Path
import shutil
import subprocess
from run import HERE,compile_model,java,calendars

def study(foes,tapes,out):
    compile_model();out.parent.mkdir(exist_ok=True,parents=True)
    targets=out.with_suffix('.opponents.txt')
    targets.write_text(''.join(str(p.resolve())+'\n' for p in foes))
    with subprocess.Popen(java('AdaptationBranches',HERE/'adaptive-frozen/challenger.net',targets,*tapes),stdout=subprocess.PIPE) as run,gzip.open(out,'xb') as f:
        shutil.copyfileobj(run.stdout,f)
        if run.wait():raise ValueError('Adaptive continuation diverged')
    with gzip.open(out,'rt') as f:rows=list(map(json.loads,f))
    if len(rows)!=4*len(foes)*len(tapes):raise ValueError('Missing switch forks')
    return rows

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('out',type=Path);p.add_argument('--sealed',type=Path);a=p.parse_args()
    foes=sorted((HERE/'adaptive-field/nets').glob(('shift-sealed-' if a.sealed else 'shift-train-')+'*.net'))
    ts=sorted(a.sealed.glob('*.txt.gz')) if a.sealed else calendars('development_selection_clocks')
    rows=study(foes,ts,a.out);eligible=[r for r in rows if r['switch_seen']]
    print(len(rows),'matches',len(eligible),'switch landmarks')
    print({k:sum(r['outcomes'][k][0] for r in eligible) for k in eligible[0]['outcomes']})
