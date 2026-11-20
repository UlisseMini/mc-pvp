"""Evaluate continuous PPO controllers on the same fixed, nonadaptive opponents and test their memory."""
from collections import defaultdict
import hashlib
import os
import re
import sys
import time

import numpy as np

from assay import CP, gzdata, java_rows, lower_contrast
from learner import BUILD, ROOT, Worker, write_json
from remote_league import OUT,read
from stationary_field import DEST, GENERALIST, OPENER, plans


def controls(spec):
    return {'robust-opener':spec['field'][OPENER]['reference'],
            'broad-mlp':spec['field'][GENERALIST]['reference'],'wtap':'wtap'}


def register():
    write_json(DEST/'ppo-protocol.json',dict(roles=['parent','gru','gru-aux'],
        primary='Equal-weight mean of outright ring-outs over the 27 frozen nonadaptive opponents. Common action seeds, timings, seats and pings. Compare with both the strong broad MLP and the league-001 specialist. Report all opponents and both W-tap controls.',
        intervention='Decision 24. Preserve the live world, actor RNG, observation features and its most recent ten heads. Replay those same ten heads after inserting older memory from the same or a different opponent. Both donors come from another clock and match seat, both RTTs, ground states, and calipers on time, hurt ages, spacing and velocities. Restore own memory as a sham.',
        grade='On exposed development calendars require positive simultaneous recording-level lower bounds for both MLP contrasts, intact versus wrong history and same versus wrong history. Report coverage by opponent. Reserve fresh timing recordings for confirmation only after a full development pass. Treat transfer to adaptive opponents as a separate later grade.'),fixed=True)


def count(v):return [int(np.sum(v==x)) for x in (1,.5,0)]


def ordinary(folder,spec,role,ref,split):
    names=list(spec['field']);fixtures=list(plans(split,names,spec));path=folder/(role+'-outcomes.json')
    if path.exists():return np.asarray(read(path))
    w=Worker(current_runtime=True)
    try:
        batches=[]
        for _,rows in fixtures:
            tasks=[[ref,*r[1:]] for r in rows]
            batches.extend(r[0] for k in range(0,len(rows),64) for r in w.run(tasks[k:k+64],False))
    finally:w.close()
    scores=np.asarray(batches).reshape(-1,len(names))
    write_json(path,scores.tolist(),fixed=True)
    return scores


def matched_forks(records):
    bins=defaultdict(list)
    for idx,(key,raw,_) in enumerate(records):
        context=raw.get('context')
        if context:bins[(*key[3:],*context[6:])].append(idx)
    cols={}
    for k,vec in bins.items():
        cols[k]=(np.asarray(vec),np.asarray([records[v][1]['context'][:6] for v in vec]),
                 np.asarray([records[v][0][:2] for v in vec]))
    limit=np.array([1000,100,100,.25,.1,.1])
    eligible=defaultdict(int);found=[];rng=np.random.default_rng(317951)
    for idx,(key,raw,_) in enumerate(records):
        c=raw.get('context')
        if c is None:continue
        eligible[key[0]]+=1
        ids,contexts,orig=cols[(*key[3:],*c[6:])]
        close=(orig[:,1]!=key[1]) & np.all(abs(contexts-c[:6])<=limit,axis=1)
        same=ids[close & (orig[:,0]==key[0])]
        other=ids[close & (orig[:,0]!=key[0])]
        if len(same) and len(other):
            # Sample identities uniformly rather than weighting by how many donors a policy happens to have.
            styles=np.array([records[i][0][0] for i in other])
            style=rng.choice(np.unique(styles))
            found.append((idx,int(rng.choice(same)),int(rng.choice(other[styles==style]))))
    return eligible,found


def evaluate(role,split='dev'):
    register();spec=read(DEST/'protocol.json');names=list(spec['field']);field_size=len(names)
    successor=bool(re.fullmatch(r'g[0-9]{3,}-main',role))
    prospective=DEST/'successors'/role/'prospective' if successor else DEST/'ppo-prospective'
    where=DEST if split=='dev' else prospective
    folder=where/'continuous'/role
    baseline_folder=where/'continuous/controls'
    if split=='dev':schedule_spec=spec
    else:schedule_spec=dict(spec,prospective_manifest=str(prospective/'calendars/manifest.json'))
    baseline=controls(spec)
    if role=='controls':
        for k,ref in baseline.items():ordinary(baseline_folder,schedule_spec,k,ref,split)
        write_json(baseline_folder/'done.json',dict(arms=list(baseline)),fixed=True)
        return
    if successor:
        pair=read(DEST/'successors'/role/'pair.json')
        for model in pair.values():
            if hashlib.sha256((ROOT/model['reference']).read_bytes()).hexdigest()!=model['sha256']:raise ValueError('Frozen league checkpoint differs')
        actor=pair['gru']['reference']
    elif role=='parent':actor=read(OUT/'protocol.json')['initial']['gru']['reference']
    else:
        while not (OUT/role/'result.json').is_file():time.sleep(20)
        actor=read(OUT/role/'result.json')['reference']
    if not (successor and split=='prospective'):
        while not (baseline_folder/'done.json').is_file():time.sleep(10)
    controls_by_name={k:ordinary(baseline_folder,schedule_spec,k,v,split) for k,v in baseline.items()}
    if successor:
        controls_by_name['same-generation-mlp']=ordinary(folder,schedule_spec,'same-generation-mlp',pair['mlp']['reference'],split)
    fixtures=list(plans(split,names,schedule_spec));keys=[k for group,_ in fixtures for k in group]
    overlay=BUILD/read(BUILD/'head24-diagnostic.json')['directory']
    options=dict(java_opts=['-Dleague.commitHeads=24'],classpath=str(overlay)+os.pathsep+CP)
    records=[]
    for tape,rows in fixtures:
        batch=[[actor,*r[1:]] for r in rows]
        catalog=gzdata(folder/(f'catalog-{tape[0][0]:02}.json.gz'),lambda:java_rows('MemoryAssay',batch,mode='catalog',**options))
        index=[(i,*k) for k in tape for i in range(field_size)]
        records.extend(zip(index,catalog,batch,strict=True))
    outcomes=np.array([r['outcome'] for _,r,_ in records]).reshape(-1,field_size)
    nclocks=len(fixtures)
    effects={}
    benchmarks=[k for k in controls_by_name if k!='wtap']
    multiplicity=len(benchmarks)+2
    if split=='prospective' and not successor:multiplicity*=len(read(prospective/'assay.json')['roles'])
    for label in benchmarks:
        d=(outcomes==1).astype(float)-(controls_by_name[label]==1)
        v=d.reshape(nclocks,-1).mean(axis=1);effects[label]=dict(mean=float(v.mean()),bounds=lower_contrast(v,.05/multiplicity))
    eligibility,matched=matched_forks(records)
    counts=defaultdict(int);sums=defaultdict(list);changes=0
    tasks=[]
    for j,(idx,same,wrong) in enumerate(matched):
        key,rr,fixture=records[idx]
        tasks.append([*fixture,rr['context'][0],','.join(map(str,records[same][1]['donor'])),
                      ','.join(map(str,records[wrong][1]['donor'])),int(j<4)])
    for j in range(0,len(tasks),256):
        forks=gzdata(folder/f'forks-{j:05}.json.gz',lambda:java_rows('MemoryAssay',tasks[j:j+256],mode='fork',**options))
        for r,(idx,_,_) in zip(forks,matched[j:j+256],strict=True):
            k,original,_=records[idx]
            assert (r['intact']['score'],r['intact']['end'])==(original['outcome'],original['time'])
            counts[k[0]]+=1
            changes+=r['same']['actions']!=r['opposite']['actions']
            for label in ('intact','same'):
                sums[label,k[1],k[0]].append(int(r[label]['score']==1)-int(r['opposite']['score']==1))
    supported=[i for i in range(field_size) if all(sums['intact',c,i] for c in range(nclocks))]
    for label in ('intact','same'):
        vals=[np.mean([np.mean(sums[label,c,i]) for i in supported]) for c in range(nclocks)] if supported else []
        effects[label+'-wrong']=dict(mean=float(np.mean(vals)),bounds=lower_contrast(vals,.05/multiplicity)) if vals else None
    all_scores={role:outcomes,**controls_by_name}
    cells={label:{n:dict(wdl=count(v[:,i]),seat={s:count(v[[k[2]==s for k in keys],i]) for s in ('a','b')},
          rtt={f'{p}/{q}':count(v[[k[3:]==(p,q) for k in keys],i]) for p,q in sorted({k[3:] for k in keys})}) for i,n in enumerate(names)} for label,v in all_scores.items()}
    write_json(folder/'result.json',dict(effects=effects,cells=cells,totals={k:count(v) for k,v in all_scores.items()},
        matching={n:dict(reached=eligibility[i],matched=counts[i]) for i,n in enumerate(names)},
        matched_all_clocks=[names[i] for i in supported],altered_action_sequences=changes,
        stronger_than_feedforward_controls=all(effects[k]['bounds'][0]>0 for k in benchmarks),
        earlier_history_helped_matched_opponents=bool(supported) and changes>0 and all(effects[k]['bounds'][0]>0 for k in ('intact-wrong','same-wrong')),
        passed=len(supported)==field_size and changes>0 and all(r and r['bounds'][0]>0 for r in effects.values())),fixed=True)


if __name__=='__main__':evaluate(sys.argv[1],sys.argv[2] if len(sys.argv)>2 else 'dev')
