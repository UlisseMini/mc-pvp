"""Fit independent best responses and select both method champions before prospective evaluation."""
import csv
import os
import subprocess
import sys
from study import D,ROOT,BUILD,NEW,OLD,freeze,read,sha
from live import champions
F=D/'specialist'
SEEDS=(4111,6421,8317,9929)
SOURCES={'old':D/'frozen'/(OLD+'.net'),'edge':D/'frozen'/(NEW+'.net'),'wide':D.parent/'sumo-inputs/fits/full32-66047.net'}
CLASSES=BUILD/'classes'
CP=os.pathsep.join([str(CLASSES),str(ROOT/'build/elite-league/sumo-inputs/classes')])
def java(*args):
    return [sys.executable,D.parent/'cpu_limit.py','nice','-n','8','java','-ea','-XX:ActiveProcessorCount=3','-XX:+UseSerialGC','-Xmx1800m','-cp',CP,*map(str,args)]
def fit(method,name,source,seed):
    p=F/'fits'/(name+'.csv')
    if not p.exists():subprocess.run(java('FrozenSumoResponse',method,name,source,seed),cwd=ROOT,check=True)
    return max(float(r['merit']) for r in csv.DictReader(p.open()))
def main():
    CLASSES.mkdir(parents=True,exist_ok=True)
    subprocess.run([sys.executable,D.parent/'cpu_limit.py','nice','-n','8','javac','-J-XX:ActiveProcessorCount=1','-proc:none','-cp',CP,'-d',CLASSES,D.parent/'FrozenSumoResponse.java'],check=True)
    prior=D.parent/'sumo-inputs'
    clocks=sorted(p for folder in [prior,prior/'refine',prior/'axes',prior/'ensemble'] for p in folder.glob('m*.txt.gz'))
    assert len({p.name for p in clocks})==18
    (F/'selection-clocks.txt').write_text(''.join(str(p.relative_to(ROOT))+'\n' for p in clocks))
    ids=[]
    for seed in SEEDS:
        scores={}
        for label,source in SOURCES.items():
            name=f'ppo-{label}-{seed}';scores[name]=fit('ppo',name,source,seed);ids.append(name)
        parent=min(scores,key=lambda n:(-scores[n],n));name=f'evolve-{seed}'
        fit('evolve',name,F/'fits'/(parent+'.net'),seed);ids.append(name)
    (F/'candidates.txt').write_text('\n'.join(ids)+'\n')
    if not (F/'selection.csv').exists():subprocess.run(java('FrozenSumoResponse','select'),check=True)
    rows=list(csv.DictReader((F/'selection.csv').open()))
    selected={}
    for method in ('ppo','evolve'):
        row=min((r for r in rows if r['id'].startswith(method+'-')),key=lambda r:(-float(r['merit']),r['id']))
        selected[method]=row
        champions.install('sumo-frozen-'+method,F/'fits'/(row['id']+'.net'),'Best response to frozen v4-ppo-g0496, '+row['id'],'frozen-v4-'+method,'winner')
    primary=max(selected,key=lambda m:(float(selected[m]['merit']),m=='ppo'))
    freeze(F/'ready.json',dict(primary=primary,alternate=next(m for m in selected if m!=primary),methods=selected,
        target_sha256=sha(D/'frozen'/(OLD+'.net')),weights={n:sha(F/'fits'/(n+'.net')) for n in ids}))
    print('Frozen specialists',read(F/'ready.json')['methods'],flush=True)
if __name__=='__main__':main()
