"""Compile shared Java policies and fit families on registered training calendars."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SUMO = ROOT / 'research/sumo-rl'
BUILD = ROOT / 'build/elite-league/classes'
FAMILIES = ('LINEAR','MLP','RECURRENT','HYBRID')
SEEDS = (193081,492811)

def class_path():
    pinned=BUILD.parent/'compiled-version.txt'
    return BUILD.parent/pinned.read_text().strip() if pinned.exists() else BUILD

def compile_model():
    # A JVM can load classes lazily. Never replace class files beneath a live tournament.
    BUILD.parent.mkdir(parents=True,exist_ok=True)
    paths=['src','tests','research/elite-league','research/sumo-rl','research/jump-reset-boxing',
           'research/sumo-strategies','research/maintenance-20261101/oracle-mechanism/transfer']
    source_hash=hashlib.sha256()
    sources={}
    for directory in paths:
        for p in sorted((ROOT/directory).rglob('*.java')):
            rel=p.relative_to(ROOT);sources[rel]=p.read_bytes()
            source_hash.update(str(rel).encode()+b'\0'+sources[rel]+b'\0')
    name='classes-'+source_hash.hexdigest()[:18];version=BUILD.parent/name
    if not version.exists():
        with tempfile.TemporaryDirectory(dir=BUILD.parent) as temp:
            snapshot=Path(temp)/'sources'
            for rel,data in sources.items():
                dest=snapshot/rel;dest.parent.mkdir(exist_ok=True,parents=True);dest.write_bytes(data)
            classes=Path(temp)/'classes';classes.mkdir()
            subprocess.run([sys.executable,str(HERE/'cpu_limit.py'),'nice','-n','8','javac','-J-XX:ActiveProcessorCount=2','-proc:none','-d',str(classes),'-sourcepath',os.pathsep.join(paths),
                            *map(str,sorted((snapshot/'research/elite-league').glob('*.java')))],cwd=snapshot,check=True)
            try:classes.rename(version)
            except OSError:
                if not version.is_dir():raise
    with tempfile.NamedTemporaryFile('w',dir=BUILD.parent,delete=False) as f:f.write(name+'\n')
    os.replace(f.name,BUILD.parent/'compiled-version.txt')
    global _compiled
    _compiled=version
    return version

_compiled=None

def java(main,*args):
    layout=os.environ.get('ELITE_LAYOUT','mmc')
    if layout not in ('mmc','legacy'):raise ValueError('Choose the recorded MMC layout or the archived legacy disk')
    return [sys.executable,str(HERE/'cpu_limit.py'),'nice','-n','8','java','-ea','-Dlab.sumo.legacy='+str(layout=='legacy').lower(),'-Dlab.mmc.legacy=true','-XX:ActiveProcessorCount=4','-XX:+UseSerialGC','-XX:CICompilerCount=2',
            '-Xms512m','-Xmx3500m','-cp',str(_compiled or class_path()),main,*map(str,args)]

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
