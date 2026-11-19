"""Compile the simulator, learn the registered policy or evaluate a frozen fit."""
import argparse
import json
from pathlib import Path
import subprocess

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
BUILD=ROOT/'build/sumo-rl/classes'
def compile_model():
    BUILD.mkdir(parents=True,exist_ok=True)
    paths=['src','tests','research/jump-reset-boxing','research/sumo-rl','research/sumo-strategies','research/maintenance-20261101/oracle-mechanism/transfer']
    sources=[str(f) for f in HERE.glob('*.java')]
    subprocess.run(['javac','-proc:none','-d',str(BUILD),'-sourcepath',':'.join(paths),*sources],cwd=ROOT,check=True)
def java(main,*args):
    return ['java','-ea','-Xmx2700m','-cp',str(BUILD),main,*map(str,args)]
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('mode',choices=['train','evaluate','evaluate-league','evaluate-solid','evaluate-lateral']);p.add_argument('clocks',type=Path);p.add_argument('output',type=Path)
    p.add_argument('--fit',type=Path,help='Optional fitted JSON to score instead of the frozen reference')
    args=p.parse_args()
    if args.output.exists():p.error('Choose a fresh output path')
    if args.mode=='train' and args.fit:p.error('--fit is for evaluation')
    compile_model()
    if args.mode=='train':
        spec=json.loads((HERE/'protocol.json').read_text())['training']
        if sorted(f.name.split('.')[0] for f in args.clocks.glob('*.txt.gz'))!=sorted(json.loads((HERE/'protocol.json').read_text())['development_clock_matches']):
            p.error('Training requires exactly the registered development donors')
        cmd=java('Learn',args.clocks,*(spec[k] for k in ('seed','generations','population','elite')))
    elif args.mode in ('evaluate-league','evaluate-solid','evaluate-lateral'):
        cmd=java('Evaluate',args.clocks,json.loads((args.fit or HERE/'league-fit.json').read_text())['code'],'league',{'evaluate-solid':'solid','evaluate-lateral':'lateral','evaluate-league':'frozen'}[args.mode])
    else:
        cmd=java('Evaluate',args.clocks,json.loads((args.fit or HERE/'fit.json').read_text())['mask'])
    result=subprocess.run(cmd,check=True,stdout=subprocess.PIPE)
    with args.output.open('xb') as stream:stream.write(result.stdout)
