"""Measure the room for exploitation after a common strong opening using training recordings only."""
import hashlib
import sys

import numpy as np

from assay import java_rows,lower_contrast
from learner import ROOT,Worker,write_json
from remote_league import read
from stationary_field import DEST,OPENER,plans

FOLDER=DEST/'opening-timing'
HEADS=(8,24,48)


def register():
    spec=read(DEST/'protocol.json')
    controls=read(DEST/'frontier-protocol.json')['controls']
    experts={'keep':controls['original-mixture'],'other-generalist':controls['weakest-mixture'],
             'old-opener':spec['field'][OPENER],**{n:spec['field'][n] for n in spec['responses']}}
    write_json(FOLDER/'protocol.json',dict(opener=experts['keep'],experts=experts,heads=HEADS,
        field=spec['field'],split='Sixteen training recordings only. Pick the lookup table and the best fixed response on even-indexed recordings, score them on odd; reverse and pool. No development or prospective clocks.',
        comparisons='Same strong generalist opening and identical world/queues/controls through decision 8, 24 or 48. Compare a single global continuation with one selected by true opponent identity. W-tap and both generalists also play from spawn. The per-fight best branch is a perfect-foresight ceiling. None of the identity or future-informed scores is a playable policy.',
        scoring='Outright wins, full denominator including fights that end before the switch. Best fixed continuation and opponent-specific responses are chosen only on the other half.'),fixed=True)


def run():
    spec=read(DEST/'protocol.json');study=read(FOLDER/'protocol.json')
    names=list(spec['field']);experts=study['experts'];order=['keep',*[n for n in experts if n!='keep']]
    refs=[experts[n]['reference'] for n in order]
    for ref in experts.values():
        if hashlib.sha256((ROOT/ref['reference']).read_bytes()).hexdigest()!=ref['sha256']:raise ValueError('Expert changed')
    worker=Worker(current_runtime=True)
    try:
        for keys,rows in plans('screen',names,spec):
            i=keys[0][0]
            rows=[[refs[0],*row[1:]] for row in rows]
            for head in HEADS:
                path=FOLDER/'fixtures'/f'{i:02}-{head}.json'
                if path.exists():continue
                forks=java_rows('ResponseReversal',rows,[','.join(refs[1:])],java_opts=[f'-Dleague.commitHeads={head}'])
                value=[[r['full'],*[r.get(ref,r['full']) for ref in refs[1:]]] for r in forks]
                write_json(path,dict(payoffs=value,ended_before_cut=sum(r['cut'] is None for r in forks)),fixed=True)
            path=FOLDER/'fixtures'/f'{i:02}-spawn.json'
            if not path.exists():
                arms={'keep':refs[0],'other-generalist':experts['other-generalist']['reference'],'wtap':'wtap'}
                start={}
                for n,ref in arms.items():
                    played=[[ref,*r[1:]] for r in rows]
                    start[n]=[r[0] for j in range(0,len(played),64) for r in worker.run(played[j:j+64],False)]
                write_json(path,start,fixed=True)
    finally:worker.close()
    outputs=sorted((FOLDER/'fixtures').glob('*-spawn.json'))
    nclock=len(outputs)
    spawn={k:np.array([read(p)[k] for p in outputs]).reshape(nclock,-1,len(names)) for k in ('wtap','keep','other-generalist')}
    def count(v):return [int(np.sum(v==x)) for x in (1,.5,0)]
    summary={}
    for head in HEADS:
        files=[read(p.with_name(p.name.replace('spawn',str(head)))) for p in outputs]
        values=np.array([v['payoffs'] for v in files]).reshape(nclock,-1,len(names),len(refs))
        if not np.array_equal(values[:,:,:,0],spawn['keep']):raise ValueError('Opening changed the ordinary generalist outcome')
        by_id=np.empty_like(spawn['keep']);fixed=by_id.copy();lookups=[]
        for parity in (0,1):
            test=np.arange(nclock)%2==parity
            train=(values[~test]==1).mean(axis=(0,1))
            by_foe=train.argmax(axis=1);global_choice=train.mean(axis=0).argmax()
            fixed[test]=values[test][...,global_choice]
            by_id[test]=np.take_along_axis(values[test],by_foe[None,None,:,None],axis=3)[:,:,:,0]
            lookups.append(dict(test_parity=parity,fixed=order[global_choice],by_opponent=dict(zip(names,[order[j] for j in by_foe]))))
        actors={**spawn,'one-fixed-branch':fixed,'known-id':by_id,'perfect-foresight':values.max(axis=3)}
        effects={}
        for n in ('keep','other-generalist','one-fixed-branch'):
            delta=((by_id==1).astype(float)-(actors[n]==1)).mean(axis=(1,2))
            effects[n]=dict(mean=float(delta.mean()),recording_bounds=lower_contrast(delta,.05/(len(HEADS)*3)))
        summary[str(head)]=dict(totals={n:count(v) for n,v in actors.items()},gains_for_known_id=effects,
            cells={n:{foe:count(v[:,:,j]) for j,foe in enumerate(names)} for n,v in actors.items()},
            policies=lookups,ended_before_switch=sum(v['ended_before_cut'] for v in files))
    write_json(FOLDER/'result.json',dict(expert_order=order,summary=summary,recordings=nclock),fixed=True)


if __name__=='__main__':
    if sys.argv[1:]==['register']:register()
    else:run()
