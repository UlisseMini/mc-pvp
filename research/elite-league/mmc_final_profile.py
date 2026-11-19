"""Evaluate the MMC champion unchanged on the alternative knockback preset, reusing existing controls."""
import argparse
import gzip
import json
from pathlib import Path
import tempfile
import time

from adaptation import dump, freeze, sha
from history_cup import mirrors
from mmc_final import common_field
from mmc_league import EVIDENCE, FROZEN, WORK, WT, contest_plan, native_name, read_results, regulation_batch, setup
from final_inputs import eligible
from results import native as bundle
from run import compile_model
from tournament import grid

DEST=EVIDENCE/'final-profile'
BASE=EVIDENCE/'transfer'
FINAL=EVIDENCE/'final'
NAME='mmc13-final-profile'


def read(p):return json.loads(p.read_text())


def key(t):return (t['arm'],t['opponent'],t['rtt'],t['side'],t['rep'])


def native_schedule(winner):
    final=read(FINAL/'schedule.json')
    arms={n:None for n in final['arms'] if native_name(n) in (WT,winner)}
    if winner not in {native_name(n) for n in arms}:raise ValueError('Unknown frozen finalist')
    base=read(BASE/'schedule.json')
    shared=contest_plan('transfer',base['arms'],base['opponents'],base['repeats'],'hypixel')
    existing={key(v):q for q,v in shared.items()}
    repeats={n:3 for n in final['opponents']}
    full=contest_plan('final-profile',arms,final['opponents'],repeats,'hypixel')
    reuse={existing[key(v)]:shared[existing[key(v)]] for v in full.values() if key(v) in existing}
    additional={q:v for q,v in full.items() if key(v) not in existing}
    if len({key(v) for v in (reuse|additional).values()})!=len(full):raise ValueError('Incomplete transfer design')
    return reuse,additional


def score(rows):
    cells={}
    for r in rows:
        out=r['score'] if 'score' in r else float(r['win'])+.5*int(r['draw'])
        for cut in ('all',f"{r['rtt']}/{r['side']}"):
            label=f"{r['arm']}/{r['opponent']}/{cut}"
            cell=cells.setdefault(label,dict(wins=0,draws=0,losses=0))
            cell['wins' if out==1 else 'draws' if out==.5 else 'losses']+=1
    return cells


def model():
    setup();arms,foes=common_field();DEST.mkdir(exist_ok=True)
    clocks=mirrors('mmc13-final',WORK)
    names=[native_name(n) for n in arms]
    dump(DEST/'protocol.json',dict(entrants=sha(FROZEN/'final-entrants.json'),mmc=sha(FINAL/'schedule.json'),
         prior_transfer=sha(BASE/'schedule.json'),clocks={p.name:sha(p) for p in clocks},weights='No tuning or weight changes between presets.',
         model='All common MMC finalists against the same six opponents on the same eight calendar files, both seats, 0/80 ms. Physics uses the local alternative preset and its registered profile-specific randomness.',
         native='After the audited MMC title, compare its winner and W-tap on all six original final opponents. Use repeats 0, 1 and 2 per seat/RTT on each preset. Reuse the original eligible MMC final cells and overlapping alternative-preset transfer fixtures. Issue only missing alternative-preset fixtures, audit all selected controls and retain every invalid attempt. This measures transfer of the MMC selection. It never chooses an MMC or secondary winner.',
         extra_fixtures_by_possible_winner={n:len(native_schedule(n)[1]) for n in names}))
    dump(DEST/'input-protocol.json',dict(rule='Both players must pass the same actual movement and network binding audit. Repeat only an invalid cell under its original parameters, preserve all attempts. Apply symmetrically to W-tap and the chosen MMC champion.',preset='hypixel',protocol=sha(DEST/'protocol.json')))
    port=DEST/'model.jsonl.gz'
    if not port.exists():
        compile_model()
        with tempfile.TemporaryDirectory(dir=WORK) as d:
            freeze(port,grid(Path(d),'profile',arms,foes,clocks,mode='mouse',profile='hypixel').read_bytes())
    lookup={}
    for profile,p in [('mmc',FINAL/'model.jsonl.gz'),('hypixel',port)]:
        with gzip.open(p,'rt') as f:rows=list(map(json.loads,f))
        lookup[profile]={}
        for r in rows:
            if r['profile']!=profile or r['diameter']!=13 or r['start_gap']!=8:raise ValueError('Different regulations')
            cell=(r['arm'],r['opponent'],r['side'],r['rtt'],r['clock'])
            if cell in lookup[profile]:raise ValueError('Duplicate mirrored fixture')
            lookup[profile][cell]=r
    if lookup['mmc'].keys()!=lookup['hypixel'].keys() or len(lookup['mmc'])!=len(arms)*len(foes)*len(clocks)*4:
        raise ValueError('Unmatched model transfer exposures')
    dump(DEST/'model-score.json',dict(mmc=score(lookup['mmc'].values()),hypixel=score(lookup['hypixel'].values()),
        fixtures_per_profile=len(lookup['mmc']),mmc_sha256=sha(FINAL/'model.jsonl.gz'),hypixel_sha256=sha(port)))


def await_file(path):
    deadline=read(WORK/'admission-deadline.json')['unix_time']
    while not path.exists():
        if time.time()>deadline+180:return False
        time.sleep(5)
    return True


def native():
    if not await_file(FINAL/'title.json'):return
    winner=read(FINAL/'title.json')['winner']
    reuse,extra=native_schedule(winner)
    dump(DEST/'native-design.json',dict(winner=winner,source=sha(FINAL/'title.json'),reused=list(reuse),additional=extra))
    base_manifest=WORK/(NAME+'.json')
    if extra:
        extra_eligible=regulation_batch(extra,base_manifest,DEST)
        extra_games=read(extra_eligible)
    else:extra_games=dict(matches={},plan={})
    if not await_file(BASE/'outcomes.json'):return
    base_file=WORK/'mmc13-transfer.json'
    if not extra:freeze(base_manifest.with_suffix('.build.json'),base_file.with_suffix('.build.json').read_bytes())
    if read(base_manifest.with_suffix('.build.json'))!=read(base_file.with_suffix('.build.json')):raise ValueError('Different native runtime for reused fixtures')
    trials,matched=extra_games['plan'].copy(),extra_games['matches'].copy()
    by_cell={key(r):r for r in read(BASE/'outcomes.json')['records']}
    for req,t in reuse.items():
        mid=by_cell[key(t)]['mid']
        actual=read(WORK.parents[2]/'runtime/results'/(mid+'.json'))['request']
        if actual.split('-input-retry')[0].split('-view-retry')[0]!=req:raise ValueError('Wrong reused fixture')
        words=t['command'].split();words[words.index(req)]=actual
        matched[actual]=mid;trials[actual]=dict(t,command=' '.join(words))
    joined=WORK/(NAME+'-eligible.json')
    dump(joined,dict(plan=trials,matches=matched))
    scores=WORK/(NAME+'-scores')
    freeze(joined.with_suffix('.build.json'),base_manifest.with_suffix('.build.json').read_bytes())
    checked=eligible(joined,joined,DEST,scores)
    port=read_results(checked)
    mmc=[r for r in read(FINAL/'outcomes.json')['records'] if r['arm'] in (WT,winner) and r['rep']<3]
    if {key(r) for r in port}!={key(r) for r in mmc} or len(port)!=len(mmc):raise ValueError('Unmatched native exposures')
    out=DEST/'native.json.gz'
    if not out.exists():bundle(checked,scores,out)
    dump(DEST/'native-score.json',dict(winner=winner,mmc=score(mmc),hypixel=score(port),
         fixtures_per_profile=len(port),controls=sha(DEST/'input-audit.json'),native_bundle=sha(out),mmc_records=mmc,hypixel_records=port))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('stage',choices=['model','native','both']);a=p.parse_args()
    if a.stage in ('model','both'):model()
    if a.stage in ('native','both'):native()
