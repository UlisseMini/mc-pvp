"""Paired map transfer at regulation 7 CPS. Only the default geometry may affect training or checkpoint selection."""
import argparse
import gzip
import hashlib
import json
import os
import subprocess
import sys
import time
from pathlib import Path
from platforms import OUT,record
from build_maps import ROOT,LEAGUE,BUILD,sha
sys.path.insert(0,str(LEAGUE))
from learner import CLOCKS,EVIDENCE,Worker,write_json
from id_evaluate import make_panel
from id_native import native_name
from native_league import alias,fixtures,cohort,WT
from league_tournament import stats
from forge_window import window
from long_specialist import sources,RUN,BASE
from scipy.stats import t as student
import numpy as np

def read(p):return json.loads(p.read_text())
def keys():return ('incumbent','seven','g1')
def register(stage):
    folder=OUT/'tournaments'/stage
    spec=folder/'protocol.json'
    if spec.exists():return folder,read(spec)
    maps=read(OUT/'split.json')
    if stage=='sealed-final':
        while not all((RUN/k/'selection.json').is_file() and
                ((d:=read(RUN/k/'selection.json'))['best_wins']==d['panel_size'] or d['stale']>=2) for k in keys()):time.sleep(10)
    contestants={}
    for key in keys():
        directory,_,net,target=sources(key)
        p=directory/net if stage=='baseline16k' else RUN/key/f"n{read(RUN/key/'selection.json')['selected']}.net"
        contestants[key]=dict(actor=str(p.relative_to(ROOT)),sha256=sha(p),target=target,
                         target_sha=read(RUN/key/'protocol.json')['target_sha'])
    names=['default','mmc-137',*['mmc-'+str(n) for n in maps['validation' if stage=='baseline16k' else 'sealed']]]
    doc=dict(stage=stage,contestants=contestants,maps=names,clock_split='eight previously exposed development donors, matched across maps',
        surface_split='four reusable validation geometries; another four used once when all final training branches are locked',
        primary='ring-out win fraction against the frozen training opponent; report W/D/L and time, plus plain W-tap against exactly the same opponent and W-tap itself',
        contrasts='matched by donor, action seeds, seat and both RTTs; descriptive paired 95% Student intervals on eight donor means',
        regulation='120 s MMC-range, 7/7 CPS, reach 3, mouse aim, zero added reaction delay; no map identity or opponent ID sent to any policy',
        origin='same 137 outline in original Z orientation controls for changing the original lab X opening; other maps preserve their recorded gold spawns',
        model_repetitions=2,native_blocks=8,selection='weights frozen before any outcome is read; later selection on original training geometry only')
    write_json(spec,doc,fixed=True)
    return folder,doc

def pairs(doc):
    rows={}
    for name in sorted(doc['contestants']):
        r=doc['contestants'][name]
        if sha(ROOT/r['actor'])!=r['sha256']:raise ValueError('Actor changed')
        for opponent in (r['target'],'wtap'):
            rows[name,opponent]=r['actor']
            rows['wtap',opponent]='wtap'
    return rows

def trials(folder,doc,map_name,native=False):
    # Keep file names, request IDs and order stable across processes and retries.
    return [(a,foe,model,f'p{j:02}') for j,((a,foe),model) in enumerate(pairs(doc).items())]

def parity(runtime,doc,folder):
    result=folder/'default-parity.json'
    if result.exists():return
    cells=make_panel(CLOCKS['dev'],1)
    plan=[]
    for a,opponent,model,_ in trials(folder,doc,'default'):
        plan.extend([[model,opponent,c,s,a_,b_,x,y] for _,_,s,a_,b_,c,x,y in cells])
    outputs=[]
    for cp in (None,runtime):
        w=Worker(runtime_directory=cp)
        try:outputs.append([r[:2] for j in range(0,len(plan),32) for r in w.run(plan[j:j+32],False)])
        finally:w.close()
    if outputs[0]!=outputs[1]:raise ValueError('Changed default outcomes or terminal times under the map runtime')
    write_json(result,dict(compared=len(plan),all_outcomes_and_times_identical=True,runtime=runtime.name),fixed=True)

def simulate(stage):
    folder,doc=register(stage)
    p=BUILD/'model-runtime.json'
    while not p.exists():time.sleep(2)
    runtime=Path(read(p)['directory']);parity(runtime,doc,folder)
    cells=make_panel(CLOCKS['dev'],doc['model_repetitions'])
    for name in doc['maps']:
        base=folder/'model'/name
        w=Worker(runtime_directory=runtime,map_name=None if name=='default' else name)
        try:
            for actor,foe,model,key in trials(folder,doc,name):
                dst=base/(key+'.json')
                if dst.exists():continue
                plan=[[model,foe,c,s,a,b,x,y] for i,r,s,a,b,c,x,y in cells]
                raw=[v for j in range(0,len(plan),32) for v in w.run(plan[j:j+32],False)]
                rows=[[i,rep,s,a,b,v[0],v[1]] for (i,rep,s,a,b,_,_,_),v in zip(cells,raw)]
                write_json(dst,dict(actor=actor,foe=foe,stats=stats(rows),games=rows),fixed=True)
        finally:w.close()
        summaries={}
        for actor,foe,_,key in trials(folder,doc,name):
            now=read(base/(key+'.json'));original=read(folder/'model/default'/(key+'.json'))
            v=np.array([r[5]==1 for r in now['games']],dtype=float)-np.array([r[5]==1 for r in original['games']],dtype=float)
            donors=[np.mean([n for n,c in zip(v,cells) if c[0]==i]) for i in range(len(CLOCKS['dev']))]
            mean=float(np.mean(donors));margin=float(np.std(donors,ddof=1)*student.ppf(.975,len(donors)-1)/np.sqrt(len(donors)))
            summaries[key]=dict(actor=actor,foe=foe,**now['stats'],win_delta_to_default=mean,paired_donor_interval=[mean-margin,mean+margin])
        write_json(base/'summary.json',summaries,fixed=True);print('Scored',name,flush=True)

def native(stage):
    folder,doc=register(stage)
    while not (BUILD/'model-runtime.json').is_file() or not (OUT/'collision/live-verification.json').is_file():time.sleep(3)
    release=read(BUILD/'registered-server.json')
    for idx,name in enumerate(doc['maps']):
        dest=folder/'native'/name
        if (dest/'summary.json').exists():continue
        plan={}
        for arm,foe,model,key in trials(folder,doc,name):
            actor=native_name(ROOT/model) if model.endswith('.net') else WT
            target=native_name(ROOT/foe) if foe.endswith('.net') else alias(foe)
            tag=f"map-{'16k' if stage=='baseline16k' else 'final'}-m{idx:02}-{key}"
            sub=fixtures(tag,[actor],[target],doc['native_blocks'],7)
            if name!='default':
                capture=json.load(gzip.open(OUT/(name+'.json.gz')))
                v=capture['volume']
                wire=gzip.decompress((OUT/'packed'/(name+'.bin.gz')).read_bytes())
                for r in sub.values():
                    r.update(map=name,diameter=max(v['width'],v['depth'])-2,start_gap=capture['gap'],volume_sha256=wire[68:100].hex())
                    r['command']+=' map='+name
            plan.update(sub)
        with window(server=release['server']) as bridge:records=cohort(dest,plan,bridge,block_barrier=True)
        rows={}
        for arm,foe,_,key in trials(folder,doc,name):
            selected=[(q,t) for q,t in plan.items() if '-'+key+'-' in q]
            def counts(v):
                vals=[records[q]['score'] for q,t in v];return dict(wins=vals.count(1),draws=vals.count(.5),losses=vals.count(0))
            rows[key]=dict(arm=arm,foe=foe,**counts(selected),seat={s:counts([r for r in selected if r[1]['side']==s]) for s in 'ab'},
                rtt={f'{a}/{b}':counts([r for r in selected if (r[1]['rtt'],r[1]['opponent_rtt'])==(a,b)]) for a,b in ((31,31),(109,109),(31,109),(109,31))})
        write_json(dest/'summary.json',rows,fixed=True);print('Native transfer',name,flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('stage',choices=['baseline16k','sealed-final']);parser.add_argument('task',choices=['model','native','register'])
    a=parser.parse_args();{'model':simulate,'native':native,'register':register}[a.task](a.stage)
