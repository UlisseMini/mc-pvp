"""Retain every attempt and replace any boxing regulation fixture with an actual input disagreement."""
import argparse
import json
import math
import subprocess
import sys
from pathlib import Path

from adaptation import dump, freeze, sha
from audit import captures, collect
from boxing_regulation import publish
from native_trials import run_batch
from results import native
from run import HERE, ROOT


WORK=ROOT/'build/elite-league/mmc13'
DEST=HERE/'evidence/boxing100'
SCORES=WORK/'boxing100-scores'
RAW=WORK/'boxing100-native.json'


def legacy(mid, seat, record):
    path,=(ROOT/'runtime/pov'/mid).glob(seat+'-client-*-events.jsonl.gz')
    actions={}; moves={}; problems=[]
    for e in captures.events(path,{'legacy_head','science_move'}):
        if e.get('match')!=mid or not record['start']<=e['time']<=record['finish']:continue
        tick=e['tick']
        if e['type']=='legacy_head':
            w,_,side,_=e['keys']
            if tick in actions:raise ValueError(f'Duplicate input in {mid} {seat} {tick}')
            actions[tick]=(.98*int(w),-.98*side)
        else:
            if tick in moves:raise ValueError(f'Duplicate move in {mid} {seat} {tick}')
            moves[tick]=(e['forward'],e['strafe'])
    shared=sorted(actions.keys() & moves.keys())
    if not shared:raise ValueError(f'Missing legacy movement in {mid} {seat}')
    # A move and its input can straddle the referee's start or finish.
    interior=set(range(shared[0],shared[-1]+1))
    if not interior<=actions.keys() or not interior<=moves.keys():
        raise ValueError(f'Missing interior movement in {mid} {seat}')
    for t in shared:
        if not all(math.isclose(a,b,abs_tol=1e-6) for a,b in zip(actions[t],moves[t])):
            problems.append(dict(tick=t,expected=actions[t],actual=moves[t]))
    return dict(method='physical forward and strafe against issued legacy keys',heads=len(shared),
                boundary_only_inputs=sorted(actions.keys()-moves.keys()),boundary_only_moves=sorted(moves.keys()-actions.keys()),
                disagreements=problems)


def scan(manifest):
    entries=json.loads(manifest.read_text())
    out=DEST/(manifest.stem+'-movement.json')
    if out.is_file():
        previous=json.loads(out.read_text())
        if {k:r['mid'] for k,r in previous.items()}!=entries['matches']:
            raise ValueError('Movement scan contains different fixture IDs')
        return
    check={}
    for req,mid in entries['matches'].items():
        record=json.loads((ROOT/'runtime/results'/(mid+'.json')).read_text())
        if record['reason']=='invalid_client_view':
            check[req]=dict(mid=mid,sides={},valid=False,reason=record['reason'])
            continue
        row=json.loads((SCORES/(mid+'.json')).read_text());sides={}
        for side in 'ab':
            if side in row['bindings']:
                v=row['bindings'][side]
                sides[side]=dict(method='replayed neural decisions and actual controls',heads=v['verified_move_heads'],
                                 disagreements=v['mismatches'])
            elif record['policy_'+side] in ('research-wtap20','research-hold20'):
                sides[side]=legacy(mid,side,record)
            else:raise ValueError(f'Unknown controller {mid} {side}')
        check[req]=dict(mid=mid,sides=sides,valid=not any(s['disagreements'] for s in sides.values()))
    dump(out,check)


def run():
    original=json.loads(RAW.read_text()); manifest=RAW
    base_plan=original['plan']
    if len(base_plan)!=64 or original['matches'].keys()!=base_plan.keys():
        raise ValueError('Missing original 64-game field')
    selected={}; lineage={}
    for attempt in range(4):
        report=DEST/(manifest.stem+'-movement.json')
        if not report.exists():
            subprocess.run([sys.executable,str(HERE/'cpu_limit.py'),'--audit',sys.executable,__file__,'scan',str(manifest)],check=True)
        rows=json.loads(report.read_text()); schedule=json.loads(manifest.read_text())
        if rows.keys()!=schedule['plan'].keys():raise ValueError('Incomplete binding scan')
        retries={}
        for req,r in rows.items():
            base=req.split('-binding-retry')[0]
            lineage.setdefault(base,[]).append(dict(request=req,mid=r['mid'],valid=r['valid']))
            if r['valid']:selected[base]=(req,r['mid'],schedule['plan'][req]);continue
            next_id=base+f'-binding-retry{attempt+1}'
            words=base_plan[base]['command'].split();words[words.index(base)]=next_id
            retries[next_id]=dict(base_plan[base],command=' '.join(words))
        if not retries:break
        if attempt==3:raise ValueError('Repeated physical control disagreement on the same boxing fixture')
        manifest=WORK/f'boxing100-native-binding-retry{attempt+1}.json'
        pinned=RAW.with_suffix('.build.json').read_bytes()
        freeze(manifest.with_suffix('.build.json'),pinned)
        dump(DEST/(manifest.stem+'-schedule.json'),dict(original=sha(RAW),original_bundle=sha(DEST/'native.json.gz'),
            rule='Both players must pass the movement check, including the W-tap and Hold-W controls. Replace each failed cell under its original settings before reading the new result. Retain every attempt and its original score.',
            failed={k:v for k,v in lineage.items() if not v[-1]['valid']},plan=retries))
        run_batch(retries,manifest)
        completed=json.loads(manifest.read_text())
        valid={q:m for q,m in completed['matches'].items() if json.loads((ROOT/'runtime/results'/(m+'.json')).read_text())['reason']!='invalid_client_view'}
        if valid:
            view=manifest.with_name(manifest.stem+'-input-audit.json')
            dump(view,dict(plan={q:retries[q] for q in valid},matches=valid))
            collect(view,SCORES,False)
    if selected.keys()!=base_plan.keys() or len({m for _,m,_ in selected.values()})!=len(base_plan):
        raise ValueError('Unbalanced repaired field')
    final=WORK/'boxing100-eligible.json'
    freeze(final.with_suffix('.build.json'),RAW.with_suffix('.build.json').read_bytes())
    dump(final,dict(plan={q:p for q,_,p in selected.values()},matches={q:m for q,m,_ in selected.values()}))
    repaired=DEST/'native-eligible.json.gz'
    if not repaired.exists():native(final,SCORES,repaired)
    dump(DEST/'attempts.json',dict(original=sha(DEST/'native.json.gz'),scored=sha(repaired),lineage=lineage))
    publish(DEST)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('stage',choices=('repair','scan'))
    p.add_argument('manifest',nargs='?',type=Path);a=p.parse_args()
    if a.stage=='scan':scan(a.manifest)
    else:run()
