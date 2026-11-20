"""Collect prospective donor clocks and play preregistered matched native sumo trials."""
import csv
import importlib.util
import json
import os
import random
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
D=Path(__file__).resolve().parent
ROOT=D.parents[2]
B=ROOT/'build/elite-league/sumo-inputs'
sys.path.insert(0,str(D.parent))
from native_trials import load_batch
import champions
spec=importlib.util.spec_from_file_location('ping_trials',D.parent/'ping-ppo/native.py')
ping=importlib.util.module_from_spec(spec);spec.loader.exec_module(ping)
WT=ping.WT
def donors():
    rows={}
    for k,(own,other) in enumerate([(31,109),(109,31),(0,0),(80,80),(45,65),(65,45)]):
        q=f'sumoinputs-clock-{k}';rows[q]=ping.trial(q,WT,WT,'a',own,other)
    path=D/'donors.json';found=json.loads(path.read_text())['matches'] if path.exists() else {}
    load_batch().run(rows,2,path,2,True,found,2)
    subprocess.run([sys.executable,ROOT/'research/sumo-rl/calendars.py',B/'clocks','--frozen-manifest',path],check=True)
if __name__=='__main__' and sys.argv[1]=='donors':donors()

from train import ARMS,SEEDS
import hashlib
import time
from collections import defaultdict

def read(path):return json.loads(path.read_text())
def freeze(path,data):
    content=(json.dumps(data,indent=2)+'\n').encode()
    if not path.exists():
        with tempfile.NamedTemporaryFile(dir=path.parent) as out:
            out.write(content);out.flush()
            try:os.link(out.name,path)
            except FileExistsError:pass
    if path.read_bytes()!=content:raise ValueError(f'Frozen file differs {path}')
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def wait(fn):
    while not fn():time.sleep(1)
def prepare():
    # Register the untrained neutral-RTT control with all other coefficients bit-identical.
    import struct,base64
    old=D.parent/'recorded-mmc-frozen/range-model/v4-ppo-g0496.net'
    w=struct.unpack('>229f',base64.b64decode(old.read_text().split('.')[1]));v=[]
    for i in range(8):v.extend(w[23*i:23*i+23]);v.extend([0.]*12)
    v.extend(w[184:]);v.append(1.)
    out=D/'incumbent-blind.net';text='INPUT8.'+base64.b64encode(struct.pack('>326f',*v)).decode()+'\n'
    if out.exists() and out.read_text()!=text:raise ValueError('Changed frozen control')
    out.write_text(text)
    champions.install('sumo-input-inc-blind',out,'Titleholder with own RTT held constant','sumo-input-inc-blind','direct')
def entrants():
    representatives={};best=None;rank=[]
    for name in ARMS:
        scores=[]
        for seed in SEEDS:
            id=f'{name}-{seed}'
            rows=list(csv.DictReader((D/'fits'/(id+'.csv')).open()));score=max(float(r['dev']) for r in rows)
            scores.append((score,seed,id));rank.append((score,id))
        representatives[name]=sorted(scores)[1][2]
    best=sorted(rank,key=lambda x:(-x[0],x[1]))[0][1]
    selected={('sumo-input-'+name):id for name,id in representatives.items()}
    if best not in selected.values():selected['sumo-input-devbest']=best
    for alias,id in selected.items():
        champions.install(alias,D/'fits'/(id+'.net'),'MMC input tournament '+id,id,'direct')
    freeze(D/'entrants.json',dict(representatives=selected,development_best=best,
        weights={id:sha(D/'fits'/(id+'.net')) for _,id in rank}))
    return list(selected)
def fixtures(prefix,arms,foes):
    rows=[]
    for arm in arms:
        for i,foe in enumerate(foes):
            for j,(own,other) in enumerate([(31,109),(109,31)]):
                for side in 'ab':
                    q=f'sinput-{prefix}-{arm}-{i}-{j}-{side}'
                    rows.append((q,ping.trial(q,arm,foe,side,own,other)))
    random.Random(301991).shuffle(rows);return dict(rows)
def run_rows(stage,rows):
    path=D/(stage+'.json');found=read(path)['matches'] if path.exists() else {}
    if path.exists() and read(path)['plan']!=rows:raise ValueError('A scheduled fixture changed')
    # If the runner was interrupted during a bout, recover the result by exact request ID.
    for f in (ROOT/'runtime/results').glob('m*.json'):
        if f.stat().st_mtime< (D/'protocol.md').stat().st_mtime-7200:continue
        g=read(f);q=g.get('request')
        if q in rows:
            if q in found and found[q]!=g['id']:raise ValueError(q)
            found[q]=g['id']
    if found.keys()!=rows.keys():load_batch().run(rows,2,path,2,True,found,2)
    elif not path.exists() or read(path)['matches']!=found:load_batch().save(path,rows,found)
def native_name(n):
    return {'wtap':WT,'hold':'research-hold20','trade':'trade-wtap','zest-strafe':'research-zest-strafe'}.get(n,n)
def full():
    prepare()
    controls=[WT,ping.OLD,'sumo-two-ping','sumo-input-inc-blind']
    # These cases can run while the learners are fitting. Include each exactly once in the screen.
    foes=[WT,'shift-check-v4-4'];run_rows('controls',fixtures('s',controls,foes))
    wait(lambda: all((D/'fits'/f'{a}-{s}.csv').exists() for a in ARMS for s in SEEDS))
    trained=entrants();run_rows('screen',fixtures('s',trained+controls,foes))
    wait(lambda:(D/'screen-audit.json').exists() and (D/'transfer.csv').exists())
    score=read(D/'screen-audit.json')['by_arm']
    def order(n):
        vals=[(v[0]+.5*v[2])/sum(v) for v in score[n].values()]
        wt=score[n][WT];return (-(sum(vals)/len(vals)+min(vals))/2,-(wt[0]+.5*wt[2])/sum(wt),n)
    winner=sorted(trained,key=order)[0];screen_winner=sorted([*trained,*controls],key=order)[0]
    id=read(D/'entrants.json')['representatives'][winner];sim=defaultdict(list)
    for r in csv.DictReader((D/'transfer.csv').open()):
        if r['arm']==id and native_name(r['foe']) not in foes+[winner]:sim[r['foe']].append(float(r['reward']))
    weak=min(sim,key=lambda n:sum(sim[n])/len(sim[n]));foes=foes+[native_name(weak)]
    freeze(D/'confirmation-selection.json',dict(challenger=winner,screen_winner=screen_winner,model_id=id,foes=foes,other_weakest=weak))
    run_rows('confirmation',fixtures('f',[winner,WT,ping.OLD,'sumo-two-ping'],foes))
if __name__=='__main__' and sys.argv[1]=='full':full()
