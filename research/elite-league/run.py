"""Compile shared Java policies and fit families on registered training calendars."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SUMO = ROOT / 'research/sumo-rl'
BUILD = ROOT / 'build/elite-league/classes'
FAMILIES = ('LINEAR','MLP','RECURRENT','HYBRID')
SEEDS = (193081,492811)

def compile_model():
    BUILD.mkdir(parents=True,exist_ok=True)
    paths = ['src','tests','research/elite-league','research/sumo-rl','research/jump-reset-boxing',
             'research/sumo-strategies','research/maintenance-20261101/oracle-mechanism/transfer']
    subprocess.run(['javac','-J-XX:ActiveProcessorCount=3','-proc:none','-d',str(BUILD),'-sourcepath',os.pathsep.join(paths),
                    *map(str,sorted(HERE.glob('*.java')))],cwd=ROOT,check=True)

def java(main,*args):
    return [sys.executable,str(HERE/'cpu_limit.py'),'nice','-n','8','java','-ea','-XX:ActiveProcessorCount=4','-XX:+UseSerialGC','-XX:CICompilerCount=2',
            '-Xms512m','-Xmx3500m','-cp',str(BUILD),main,*map(str,args)]

def calendars(field):
    spec=json.loads((HERE/'protocol.json').read_text())
    paths=[]
    for mid in spec[field]:
        hits=list(SUMO.glob('*clocks/'+mid+'.txt.gz'))
        if len(hits)!=1:raise ValueError(f'{mid} matched {len(hits)} clocks')
        paths.append(hits[0])
    return paths

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out',type=Path)
    parser.add_argument('--generations',type=int,default=64)
    parser.add_argument('--population',type=int,default=36)
    parser.add_argument('--pairs',type=int,default=2)
    parser.add_argument('--workers',type=int,choices=range(1,5),default=4)
    parser.add_argument('--seeds',type=int,nargs='+',default=SEEDS)
    parser.add_argument('--families',nargs='+',choices=FAMILIES,default=FAMILIES)
    args=parser.parse_args()
    if args.out.exists():parser.error('Choose a fresh training directory')
    compile_model()
    args.out.mkdir(parents=True)
    with (args.out/'configuration.json').open('x') as stream:
        json.dump({**vars(args),'out':str(args.out)},stream,indent=2)
    for seed in args.seeds:
        for family in args.families:
            path=args.out/f'{family.lower()}-{seed}'
            subprocess.run(java('Evolve',args.workers,seed,args.generations,args.population,args.pairs,
                                family,path.resolve(),*calendars('training_clocks')),cwd=ROOT,check=True)
