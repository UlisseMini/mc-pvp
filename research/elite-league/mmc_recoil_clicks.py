"""Pair four-head click schedules at the first close-range outgoing recoil and audit actual contacts."""
from collections import defaultdict
import argparse
import gzip
import json
import math
from pathlib import Path
import subprocess
import tempfile

import focus
from adaptation import dump, freeze, sha
from audit import captures
from mmc_click_bias import PARENT
from mmc_edge_fit import BASE, CONTACT
from mmc_league import EVIDENCE, FROZEN, WORK, WT
from mmc_scorecard import record
from mmc_trade_fit import ACTOR, PREVIOUS, pool
from run import ROOT, calendars, compile_model, java
from tournament import roster

DEST=EVIDENCE/'recoil-clicks'
ARCHIVE=FROZEN/'mmc13-hidden-strafe/development.jsonl.gz'


def write_rows(path,rows):
    freeze(path,gzip.compress((''.join(json.dumps(r,separators=(',',':'),allow_nan=False)+'\n' for r in rows)).encode(),mtime=0))


def protocol():
    DEST.mkdir(exist_ok=True)
    dump(DEST/'protocol.json',dict(
        motivation='The first extra opening click scored the next fresh hit in all 32 passive-opponent forks, but late versus immediate clicks changed the eventual ring-out in both directions. Test the next mechanical question in an adverse exchange. How many legal gated presses actually brake recoil and when does that improve the outcome?',
        parent=sha(CONTACT/(BASE+'.net')),source=sha(EVIDENCE/'contact-timing/forks.jsonl.gz'),
        model_clocks=[p.name for p in calendars('development_selection_clocks')],model_arms=['wtap',BASE,PARENT],
        model_opponents=['wtap',PREVIOUS,ACTOR],native_opening=sha(EVIDENCE/'opening/outcomes.json'),
        rule='First own input at most 175 ms after a visible incoming fresh hurt cue, with self moving away from the observed target at least 0.12 blocks/tick and horizontal center distance in (0,3.6].',
        experiment='Clone all model clocks, queues, controllers and primed W-tap at that input. Compare unchanged, primed W-tap, gate open, closed, open on even/odd heads, or wait one then open. Modifications last four input heads. All preserve the 20-CPS physical cap. Count admissions, actual consumption, target contacts, actual sprint braking, motion and accepted hits. Preserve bouts without a trigger. Native opening compares naturally executed inputs; it does not compare counterfactual native branches.',
        ledger='Model contacts: milliseconds since cut, own input tick, sprint before, VX/VZ before, VX/VZ after, own X/Z, admission ID. Moves: milliseconds, X/Y/Z, post-step VX/VZ, sprint. Measure this local process for 350 ms and accepted server hits for 750 ms. Native uses reported hook order, checks the vector transition on each contact, and retains short end-censored windows.'))


def model(opening=False):
    protocol();compile_model()
    field=pool()|{BASE:CONTACT/(BASE+'.net'),PARENT:FROZEN/'mmc13-trades'/(PARENT+'.net')}
    arms={n:field[n] for n in ('wtap',BASE,PARENT)};foes={n:field[n] for n in ('wtap',PREVIOUS,ACTOR)}
    archive=ARCHIVE; modes=('original','wtap','open','closed','open-even','open-odd','delay-one')
    prefix=''
    if opening:
        spec=json.loads((EVIDENCE/'opening/schedule.json').read_text())
        field.update(focus.BASE)
        arms={n:field[n] for n in spec['arms']};foes={n:field[n] for n in spec['opponents']}
        if any(sha(path)!=spec[group][n] for group,cast in [('arms',arms),('opponents',foes)] for n,path in cast.items() if path):
            raise ValueError('Different opening network weights')
        archive=EVIDENCE/'opening/model.jsonl.gz';modes=('original',);prefix='opening-'
        dump(DEST/'paired-opening-protocol.json',dict(native=sha(EVIDENCE/'opening/outcomes.json'),model=sha(archive),
            scope='Apply the published recoil landmark to all six original opening matchups in both environments. Collect unmodified controls for the local physics comparison. The two panels have independent clocks.'))
    with tempfile.TemporaryDirectory(dir=WORK) as d:
        d=Path(d);roster(d/'arms',arms);roster(d/'foes',foes)
        with (d/'rows').open('w') as out:subprocess.run(java('RecoilClickStudy',d/'arms',d/'foes',*(['--observe'] if opening else []),*calendars('development_selection_clocks')),stdout=out,check=True)
        rows=list(map(json.loads,(d/'rows').read_text().splitlines()))
    def key(r):return tuple(r[k] for k in ('arm','opponent','clock','rtt','side'))
    with gzip.open(archive,'rt') as f:
        old={key(r):r for r in map(json.loads,f) if r['arm'] in arms and r['opponent'] in foes}
    if len(rows)!=len(arms)*len(foes)*32 or len(old)!=len(rows) or set(map(key,rows))!=set(old):raise ValueError('Different model schedule')
    cells=defaultdict(list)
    for r in rows:
        a,o,c,t,s=key(r);canon=old[key(r)]
        if [r['base'],r['end'],*r['fresh']]!=[canon[k] for k in ('score','end_ms','fresh','incoming')]:
            raise ValueError('Instrumented game differs from registered bout')
        r['plain_wtap_spawn']=old['wtap',o,c,t,s]['score']
        for cut in ('all',f'{t}/{s}',c):cells[a,o,cut].append(r)
    raw=DEST/(prefix+'forks.jsonl.gz');write_rows(raw,rows)
    results={}
    for k,rs in cells.items():
        entries=[r for r in rs if 'branches' in r]
        stats={}
        for m in modes:
            branches=[r['branches'][m] for r in entries]
            stats[m]=dict(from_trigger=record([b['score'] for b in branches]),
                from_spawn=record([r['branches'][m]['score'] if 'branches' in r else r['base'] for r in rs]),
                contacts=sum(len(b['contacts']) for b in branches),brakes=sum(sum(c[2] for c in b['contacts']) for b in branches),
                bouts_with_changed_gate=sum(b['changed_gate'] for b in branches),
                admissions=sum(b['admissions'] for b in branches),consumes=sum(b['consumes'] for b in branches))
        results['/'.join(k)]=dict(games=len(rs),reached=len(entries),wtap_from_spawn=record([r['plain_wtap_spawn'] for r in rs]),modes=stats)
    dump(DEST/(prefix+'model-summary.json'),dict(source=sha(raw),cells=results))


def native_bout(r,sprint=False):
    mid=r['mid'];seat=r['side'];g=json.loads((ROOT/'runtime/results'/(mid+'.json')).read_text())
    path,=(ROOT/'runtime/pov'/mid).glob(seat+'-client-*-events.jsonl.gz')
    first=None;hurt=None;clicks=[];consumes=[];contacts=[];moves=[];last_tick=None;local=None
    cue='fresh_target_hurt_status' if sprint else 'fresh_hurt_status'
    kinds={'duel_network','legacy_head',cue,'click','science_click','engine_attack','science_move'}
    inputs=[]
    for e in captures.events(path,kinds):
        if e.get('match')!=mid or not g['start']<=e['time']<=g['finish']:continue
        t=e['time'];typ=e['type']
        if typ==cue:hurt=t
        if typ in ('duel_network','legacy_head'):
            last_tick=e['tick']
            if typ=='duel_network':
                v=e['observation'];x,y,z,tx,_,tz,vx,vy,vz=v[:9];age=v[12 if sprint else 11]
                key=e['action']%4
            else:
                x,y,z,vx,vy,vz=e['own'][:6];tx,_,tz=e['target'];age=-1 if hurt is None else t-hurt
                w,ctrl,*_=e['keys'];key=2 if w and ctrl else int(w)
            dx,dz=tx-x,tz-z;gap=math.hypot(dx,dz);away=-(vx*dx+vz*dz)/gap if gap else 0
            landmark=0<=age<100 if sprint else 0<=age<=175 and away>=.12 and 0<gap<=3.6
            if first is None and landmark:
                first=t;local=[age,gap,away,x,y,z,vx,vy,vz,tx,tz]
            if sprint and first is not None and 0<=t-first<=350:inputs.append([t-first,last_tick,key])
        if first is None or not 0<=t-first<=350:continue
        if typ=='click':clicks.append([t-first,e['monotonic_ns']])
        if typ=='science_click':consumes.append([t-first,e['tick'],e['sequence']])
        if typ=='science_move':
            a=e['after'];moves.append([t-first,a['posX'],a['posY'],a['posZ'],a['motionX'],a['motionZ'],a['sprint']])
        if typ=='engine_attack':
            if not e['target_is_canonical'] or not e['ray_selected_target']:raise ValueError('Non-target contact '+mid)
            was=e['sprint_before'];vx,vz=e['vx_before'],e['vz_before'];scale=.6 if was else 1.
            if abs(e['vx_after']-vx*scale)>1e-10 or abs(e['vz_after']-vz*scale)>1e-10 or e['sprint_after']:
                raise ValueError('Unexpected horizontal contact law '+mid)
            contacts.append([t-first,last_tick,was,vx,vz,e['vx_after'],e['vz_after'],e['own_x'],e['own_z'],e['click_attempt']])
    out=dict(r,score=1 if r['win'] else .5 if r['draw'] else 0,local=local)
    if first is not None:out.update(at=first-g['start'],censored=first+350>g['finish'],admissions=clicks,consumes=consumes,contacts=contacts,moves=moves)
    if sprint and first is not None:out.update(inputs=inputs,censored_1500=first+1500>g['finish'])
    return out


def native():
    protocol();out=EVIDENCE/'opening';schedule=json.loads((out/'outcomes.json').read_text());rows=schedule['records']
    with gzip.open(out/'native.json.gz','rt') as f:audit=json.load(f)
    if audit['summary']['binding_disagreements'] or {r['mid'] for r in rows}!={r['mid'] for r in audit['matches']}:raise ValueError('Unverified eligible field')
    vals=[native_bout(r) for r in rows];write_rows(DEST/'native.jsonl.gz',vals);groups=defaultdict(list)
    for r in vals:
        for cut in ('all',f"{r['rtt']}/{r['side']}"):groups[r['arm'],r['opponent'],cut].append(r)
    cells={}
    for k,rr in groups.items():
        ent=[r for r in rr if r['local']];allc=[c for r in ent for c in r['contacts']]
        cells[k]=dict(all=record([r['score'] for r in rr]),reached=record([r['score'] for r in ent]),end_censored=sum(r['censored'] for r in ent),
            admissions=sum(len(r['admissions']) for r in ent),consumes=sum(len(r['consumes']) for r in ent),
            contacts=len(allc),brakes=sum(c[2] for c in allc),two_or_more_brakes=sum(sum(c[2] for c in r['contacts'])>=2 for r in ent))
    dump(DEST/'native-summary.json',dict(source=sha(DEST/'native.jsonl.gz'),cells={'/'.join(k):dict(
        controller=v,plain_wtap=cells[WT,k[1],k[2]]) for k,v in cells.items()}))


if __name__=='__main__':
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('mode',choices=('register','native','model','opening'))
    {'register':protocol,'native':native,'model':model,'opening':lambda:model(True)}[ap.parse_args().mode]()
