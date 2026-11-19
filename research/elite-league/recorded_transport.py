"""Train and score matched policy-search tracks under native wire quantiles and the ordinary simulator."""
import gzip
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
from collections import Counter,defaultdict

from adaptation import dump,freeze
import champions
from recorded_archive import field
from recorded_mmc_league import DEST,TEMP,publish
from run import HERE,ROOT,calendars,compile_model,java
from tournament import roster

def read(path):return json.loads(path.read_text())

def fit():
    d=DEST/'transport-training';tmp=TEMP/'transport';tmp.mkdir(exist_ok=True)
    ids=read(d/'protocol.json')['requests']
    while not all((d/(q+'.json')).exists() for q in ids):
        subprocess.run([sys.executable,str(HERE/'cpu_limit.py'),'--audit',sys.executable,str(HERE.parent/'mmc-knockback/transport_donors.py')],check=True)
        if not all((d/(q+'.json')).exists() for q in ids):time.sleep(15)
    train_ids=[q for q in ids if 'mmc13-actor-2-fit' in q];dev_ids=[q for q in ids if q not in train_ids]
    parent='v3-memory-27';pool,students=field()
    for n in ('v3-memory-27','v3-feed-39'):pool[n]=DEST/'memory'/(n+'.net')
    foes={n:pool[n] for n in ['wtap','population-09','adapt-01-recurrent','adapt-02-recurrent',*students,'v3-feed-39','continuation-03-fit']}
    dump(d/'training-protocol.json',dict(parent=parent,training_donors=train_ids,development_donors=dev_ids,foes=list(foes),
        training='80 generations per track, 20 actors plus plain W-tap, both seats, both RTTs. Each generation draws the identical calendars, opponents and seats for both tracks. Fixed track uses the standard 0.35 ms everywhere. Mixed track gives half the cases lane-specific native wire-delay distributions, on top of the configured one-way RTT. Score equal-weight opponent mean, bottom quartile of opponent/seat/RTT cells and weakest opponent in weights 0.4/0.4/0.2. Prioritize W-tap during selection.',
        residual='The link model samples bounded residuals by an exogenous seed, route and 50 ms interval with monotonic FIFO receipt. Five-second measurements supply the percentile distribution, not historical payloads or opponent actions. This is a robustness intervention, not an identified model of packet delivery.',
        development='Use distinct donor fixtures and original development calendars, both fixed and varying links. Freeze training weights before scoring. These are development fixtures.'))
    controls={n:pool[n] for n in ['wtap',parent,'v3-feed-39',students[-1]]}
    return search(d,tmp,parent,pool[parent],foes,controls,train_ids,dev_ids,'v3')

def search(d,tmp,parent,source,foes,controls,train_ids,dev_ids,version):
    operation={'v3':'','v4':'-range','v4-sprint':'-range'}[version]
    fit_op='fit-sprint' if version=='v4-sprint' else 'fit'
    def donors(name,requests):
        lines=[]
        for q in requests:
            r=read(d/(q+'.json'))
            lines.append(' '.join(map(str,[int(r['rtt']),*[v for lane in ('aup','bup','adown','bdown') for v in r['lanes'][lane]['quantiles']]])))
        path=tmp/name;freeze(path,('\n'.join(lines)+'\n').encode());return path
    train=donors('training-donors',train_ids);dev=donors('development-donors',dev_ids)
    def saved_roster(name, field):
        with tempfile.TemporaryDirectory(dir=tmp) as directory:
            path=Path(directory)/name;roster(path,field);freeze(tmp/name,path.read_bytes())
    saved_roster('opponents',foes);saved_roster('source',{parent:source})
    compile_model();saved=tmp/'trained'
    if not saved.is_dir():
        with tempfile.TemporaryDirectory(dir=tmp) as sub:
            out=Path(sub)/'checkpoints';out.mkdir()
            subprocess.run(java('RecordedTransportLeague',fit_op+operation,train,tmp/'opponents',tmp/'source',out,*calendars('training_clocks')),cwd=ROOT,check=True)
            out.rename(saved)
    arms=controls.copy()
    for f in sorted(saved.glob('*.net')):
        n=version+'-link-'+f.stem;arms[n]=d/(n+'.net');freeze(arms[n],f.read_bytes())
    freeze(d/'training.jsonl', (saved/'training.jsonl').read_bytes())
    saved_roster('entrants',arms);raw=d/'development.jsonl.gz'
    if not raw.exists():
        with tempfile.TemporaryFile() as f:
            subprocess.run(java('RecordedTransportLeague','score'+operation,dev,tmp/'opponents',tmp/'entrants',tmp,*calendars('development_selection_clocks')),cwd=ROOT,stdout=f,check=True)
            f.seek(0);freeze(raw,gzip.compress(f.read(),mtime=0))
    cells=defaultdict(lambda:defaultdict(lambda:defaultdict(lambda:defaultdict(Counter))))
    with gzip.open(raw,'rt') as f:
        for r in map(json.loads,f):
            cells[r['arm']][r['opponent']][r['transport']][r['side']+str(r['rtt'])][{1:'W',.5:'D',0:'L'}[r['score']]]+=1
    ranks=[]
    for arm,field_cells in cells.items():
        by_opponent={};all_cells=[]
        for foe,modes in field_cells.items():
            results=[]
            for groups in modes.values():
                for c in groups.values():
                    if c.total()!=8:raise ValueError('Incomplete cell')
                    results.append((c['W']+.5*c['D'])/c.total())
            if len(results)!=8:raise ValueError('Missing settings')
            by_opponent[foe]=sum(results)/len(results);all_cells+=results
        mean=sum(all_cells)/len(all_cells);bottom=sorted(all_cells)[:len(all_cells)//4]
        ranks.append(dict(name=arm,wtap=by_opponent['wtap'],rates=by_opponent,mean=mean,merit=.4*mean+.4*sum(bottom)/len(bottom)+.2*min(by_opponent.values())))
    ranks.sort(key=lambda r:(r['wtap']<.8,-r['merit'],-r['mean'],r['name']))
    for track in ('fixed','jitter'):
        selected=next(r['name'] for r in ranks if r['name'].startswith(version+'-link-'+track))
        champions.install(selected,arms[selected],'Recorded wire-transport training, '+track+' track',selected,'direct')
        publish('minemen-'+version+'-link-'+track,selected,arms)
    winner=ranks[0]['name'];publish('minemen-'+version+'-link-cup',winner,arms)
    dump(d/'results.json',dict(winner=winner,ranks=ranks,cells=cells))
    print('Transport search',winner,flush=True)
if __name__=='__main__':fit()
