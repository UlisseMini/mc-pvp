"""Run every seed from scratch and preserve a completed learner's immutable checkpoint."""
import os
import subprocess
import sys
from pathlib import Path
D=Path(__file__).resolve().parent
ROOT=D.parents[2]
B=ROOT/'build/elite-league/sumo-inputs'
ARMS='base8 blind8 noself8 nofoe8 nohurt8 wide32 motion8 edge8 timing8 full8 full32'.split()
SEEDS=[7181,29137,66047]
def java(*args):
    return [sys.executable,str(D.parent/'cpu_limit.py'),'nice','-n','8','java','-ea','-XX:ActiveProcessorCount=3','-XX:+UseSerialGC','-Xms300m','-Xmx1650m','-cp',str(B/'classes'),*map(str,args)]
if __name__=='__main__':
    for seed in SEEDS:
        for arm in ARMS:
            if not (D/'fits'/f'{arm}-{seed}.csv').exists():subprocess.run(java('InputStudy',arm,seed),check=True,cwd=ROOT)
