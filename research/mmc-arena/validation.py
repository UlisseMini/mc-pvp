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
from build_maps import ROOT,LEAGUE,BUILD,LBUILD,sha
sys.path.insert(0,str(LEAGUE))
from learner import CLOCKS,EVIDENCE,Worker,write_json
from id_evaluate import make_panel
from id_native import native_name
from native_league import alias,fixtures,cohort,fair_cohort,WT
from league_tournament import stats
from long_specialist import sources,RUN,BASE
from scipy.stats import t as student
import numpy as np

def read(p):return json.loads(p.read_text())
def keys():return ('incumbent','seven','g1')
STAGES={'baseline16k':'16k','checkpoint32k':'32k','sealed-final':'final'}
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
        if stage=='baseline16k':p=directory/net
        elif stage=='checkpoint32k':p=RUN/key/'n32768.net'
        else:p=RUN/key/f"n{read(RUN/key/'selection.json')['selected']}.net"
        contestants[key]=dict(actor=str(p.relative_to(ROOT)),sha256=sha(p),target=target,
                         target_sha=read(RUN/key/'protocol.json')['target_sha'])
    names=['default','mmc-137',*['mmc-'+str(n) for n in maps['sealed' if stage=='sealed-final' else 'validation']]]
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
    from report_transfer import report
    report(stage)

def verify_spawn(mid,expected_name):
    game=read(ROOT/'runtime/results'/(mid+'.json'))
    arena=game['sumo_arena']
    if arena.get('map','default')!=expected_name:raise ValueError('Native map mismatch in '+mid)
    cells=arena.get('gold_spawns',[[-4,0],[4,0]])
    if expected_name!='default':
        source=read(OUT/'catalog.json')
        if not any(r['name']==expected_name for r in source):raise ValueError('Unexpected recording')
        wire=gzip.decompress((OUT/'packed'/(expected_name+'.bin.gz')).read_bytes())
        if arena.get('observed_volume_sha256')!=wire[68:100].hex() or arena['source_volume_sha256']!=wire[68:100].hex():
            raise ValueError('Native volume differs from recording in '+mid)
        captured=json.load(gzip.open(OUT/(expected_name+'.json.gz')))['volume']['spawns']
        if cells!=captured:raise ValueError('Scheduled gold spawns differ from recording in '+mid)
    expected=[(4096*(game['arena']+1)+x+.5,12,z+.5) for x,z in cells]
    replay=ROOT/'runtime/replays'/(mid+'.jsonl')
    stream=replay.open() if replay.exists() else gzip.open(str(replay)+'.gz','rt')
    frames=[]
    with stream:
        for line in stream:
            e=json.loads(line)
            if e.get('time',0)>=game['start']:break
            if e['type']=='tick':frames.append(e)
    if not frames or abs(frames[-1]['time']-game['start'])>200:raise ValueError('Missing countdown for '+mid)
    for e in frames:
        positions=[tuple(e[side][k] for k in ('posX','posY','posZ')) for side in 'ab']
        if positions!=expected:raise ValueError('Countdown spawn changed in '+mid)
    return dict(frames=len(frames),positions=expected,observed_volume=arena.get('observed_volume_sha256',arena.get('observed_floor_sha256')))

def native_plan(stage,folder,doc):
    all_trials={};sources_by_key={}
    for idx,name in enumerate(doc['maps']):
        for arm,foe,model,key in trials(folder,doc,name):
            actor=native_name(ROOT/model) if model.endswith('.net') else WT
            target=native_name(ROOT/foe) if foe.endswith('.net') else alias(foe)
            tag=f"map-{STAGES[stage]}-m{idx:02}-{key}"
            sub=fixtures(tag,[actor],[target],doc['native_blocks'],7)
            if name!='default':
                capture=json.load(gzip.open(OUT/(name+'.json.gz')))
                v=capture['volume']
                wire=gzip.decompress((OUT/'packed'/(name+'.bin.gz')).read_bytes())
                for r in sub.values():
                    r.update(map=name,diameter=max(v['width'],v['depth'])-2,start_gap=capture['gap'],volume_sha256=wire[68:100].hex())
                    r['command']+=' map='+name
            all_trials.update(sub)
            sources_by_key.update({q:(name,key,arm,foe) for q in sub})
    return all_trials,sources_by_key

def smoke(bridge):
    stage='baseline16k';folder,doc=register(stage)
    plan,labels=native_plan(stage,folder,doc)
    # Use four original tournament request IDs. The full cohort subsequently reuses their results.
    target=doc['contestants']['incumbent']['target']
    first={q:t for q,t in plan.items() if t['block']==0 and labels[q][0] in ('mmc-129','mmc-221')
           and labels[q][2] in ('incumbent','wtap') and labels[q][3]==target}
    if len(first)!=4:raise ValueError('Expected matched specialist and plain W-tap fixtures on two maps')
    dest=folder/'native'/'release-check'
    scores=cohort(dest,first,bridge)
    write_json(dest/'spawn-checks.json',{q:verify_spawn(s['mid'],labels[q][0]) for q,s in scores.items()},fixed=True)
    from report_transfer import report
    report(stage)

def native(stage):
    import random
    folder,doc=register(stage)
    while not (BUILD/'model-runtime.json').is_file() or not (OUT/'collision/live-verification.json').is_file():time.sleep(3)
    release=read(OUT/'collision/live-verification.json')
    all_trials,sources_by_key=native_plan(stage,folder,doc)
    records={};dest=folder/'native'
    write_json(dest/'design.json',dict(server=release['server'],blocks=doc['native_blocks'],schedule=all_trials,
              ordering='seeded shuffle across maps and matchups within each matched seat/RTT block; yield the main server between blocks'),fixed=True)
    for b in range(doc['native_blocks']):
        batch=dest/f'block-{b:02}';plan=[(q,t) for q,t in all_trials.items() if t['block']==b]
        random.Random(554119+b).shuffle(plan);plan=dict(plan)
        if (batch/'verified.json').exists():new=read(batch/'verified.json')['matches']
        else:new=fair_cohort(batch,plan,LBUILD/'bridge.jar',server=release['server'])
        checks=batch/'spawn-checks.json'
        if not checks.exists():write_json(checks,{o['mid']:verify_spawn(o['mid'],sources_by_key[q][0]) for q,o in new.items()},fixed=True)
        records.update(new)
        for name in doc['maps']:
            rows={}
            for arm,foe,_,key in trials(folder,doc,name):
                qs=[q for q in records if sources_by_key[q][:2]==(name,key)]
                def count(group):
                    vals=[records[q]['score'] for q in group]
                    return dict(wins=vals.count(1),draws=vals.count(.5),losses=vals.count(0))
                rows[key]=dict(arm=arm,foe=foe,**count(qs),
                    seat={side:count([q for q in qs if all_trials[q]['side']==side]) for side in 'ab'},
                    rtt={f'{a}/{bb}':count([q for q in qs if (all_trials[q]['rtt'],all_trials[q]['opponent_rtt'])==(a,bb)]) for a,bb in ((31,31),(109,109),(31,109),(109,31))})
            write_json(dest/name/(f'after-{b+1:02}-blocks.json' if b+1<doc['native_blocks'] else 'summary.json'),rows,fixed=True)
        print('Native map blocks audited',b+1,'of',doc['native_blocks'],flush=True)
        from report_transfer import report
        report(stage)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('stage',choices=STAGES);parser.add_argument('task',choices=['model','native','register'])
    a=parser.parse_args();{'model':simulate,'native':native,'register':register}[a.task](a.stage)
