"""Recheck independent native openings using three progressively richer kinds of recorded inputs."""
import argparse
import gzip
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[1];SUMO=ROOT/'research/sumo-rl'
sys.path.insert(0,str(SUMO))
from run import compile_model
from validate import client
from input_replay import compare

def load_strict():
    sys.path.insert(0,str(ROOT/"research"))
    spec=importlib.util.spec_from_file_location('strict_clock_reference',ROOT/'research/periodic-clock-replay.py')
    mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
    return mod

def pick(manifest,names):
    d=json.loads(manifest.read_text())
    if d['matches'].keys()!=d['plan'].keys():raise ValueError('Incomplete native cohort')
    cells={}
    for req,mid in sorted(d['matches'].items(),key=lambda q:int(q[1][1:])):
        p=d['plan'][req];key=(p['arm'],p['rtt'],p['side'])
        if p['arm'] not in names or p['mode']!='sumo' or p['profile']!='mmc' or p['rtt'] not in (0,80) or key in cells:continue
        g=json.loads((ROOT/f'runtime/results/{mid}.json').read_text())
        if all(g['parameters_'+s].get('science_trace')=='1' for s in 'ab'):cells[key]=mid
    if len(cells)!=4*len(set(names)):raise ValueError(f'Incomplete paired movement coverage: {list(cells)}')
    return {' '.join(map(str,k)):v for k,v in cells.items()}

def measure(manifest,names,out):
    ids=pick(manifest,names);compile_model();strict=load_strict()
    summary={'selected':ids,'physical':{},'travel':{},'packet_timing':{}}
    with tempfile.TemporaryDirectory(prefix='league-trace-') as cp:
        subprocess.run([str(strict.JAVA.with_name('javac')),'-J-XX:ActiveProcessorCount=2','-proc:none','-d',cp,*[str(ROOT/s) for s in strict.SOURCES]],check=True)
        for mid in ids.values():
            summary['travel'][mid]=[client(mid,s) for s in 'ab']
            try:summary['physical'][mid]=compare(mid,[(.35,.35)])[0]
            except (ValueError,IndexError,KeyError,StopIteration,TypeError) as exc:summary['physical'][mid]={'unscored':str(exc)}
            try:
                d=strict.run(mid,cp,True,4,True)
                summary['packet_timing'][mid]={k:v for k,v in d.items() if k not in ('rows','method')}
            except (ValueError,IndexError,KeyError,StopIteration,TypeError) as exc:summary['packet_timing'][mid]={'unscored':str(exc)}
            print(mid,summary['physical'][mid].get('exact'),[x.get('step_failures') for x in summary['travel'][mid]],
                  summary['packet_timing'][mid].get('completed_event_rows'),flush=True)
    with out.open('xb') as f:f.write(gzip.compress(json.dumps(summary,separators=(',',':')).encode(),mtime=0))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('manifest',type=Path);p.add_argument('out',type=Path);p.add_argument('arms',nargs='+')
    a=p.parse_args();measure(a.manifest,a.arms,a.out)
