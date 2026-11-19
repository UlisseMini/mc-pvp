"""Measure Space timing and actual takeoff after the first incoming hurt cue in MMC sumo."""
import argparse
from collections import defaultdict
import gzip
import json
import math
from pathlib import Path
from statistics import median
import subprocess
import tempfile

from adaptation import dump,sha
from audit import captures
import focus
from mmc_click_bias import PARENT
from mmc_league import EVIDENCE,FROZEN,WORK,WT,native_name
from mmc_recoil_clicks import write_rows
from mmc_scorecard import record
from mmc_trade_fit import pool
from run import ROOT,calendars,compile_model,java
from tournament import roster

DEST=EVIDENCE/'jump-reset-window'
OPENING=EVIDENCE/'opening'
MODES=('original','wtap','pulse-now','first-ground','second-ground','suppress')
WINDOW=850
JUMP_VY=0.41999998688697815


def read(p):
    return json.loads(p.read_text())


def register():
    DEST.mkdir(exist_ok=True)
    dump(DEST/'protocol.json',dict(
        impetus='The low-CPS development audit observed 141 Space decisions. The outgoing-sprint study found comparable native ring-out records despite very different rates of bonus knockback. Isolate the vertical response to receiving damage in regulation sumo. Separate key decisions, successful takeoffs, the next fresh hit and the eventual ring-out.',
        source=sha(EVIDENCE/'nomophobia-development/five-cps-m5546-m5583.json'),
        opening_native=sha(OPENING/'outcomes.json'),opening_model=sha(OPENING/'model.jsonl.gz'),
        hard_model=sha(FROZEN/'mmc13-trades/development.jsonl.gz'),
        clocks=[p.name for p in calendars('development_selection_clocks')],
        fixture='137-block MMC floor, gold spawns eight blocks apart, ordinary controls including integer mouse, three-block reach, 20 CPS maximum, both seats at symmetric 0 and 80 ms added RTT.',
        trigger='First input with local incoming fresh-hurt age in [0,100) ms. Retain untriggered bouts, body-on-ground and own velocity at entry. Compare interventions at one identical complete state with all queues and both primed controllers.',
        modes=list(MODES),window='At entry, pulse Space for one input, or pulse on the first or second grounded input among the next ten including entry. The second grounded input may be consecutive or after landing. All other inputs follow the original controller on the new trajectory. Suppress clears Space on those ten inputs. Compare unchanged and primed W-tap from this checkpoint, and plain W-tap from the same spawn. No alternative here anticipates a future hit.',
        steps=['ms since entry','space held','ground before move','actual vanilla takeoff','ground after move','x','y','z','vx','vy','vz','post-travel sprint'],
        metrics='Track actual travel and accepted hits for 850 ms. Report actual takeoffs and landings, which side landed the next fresh hit after the cutoff millisecond, and delay to the first landing. Measure outward movement against own horizontal recoil direction at entry. Report W/D/L and changed outcomes from the identical starts, including each calendar and seat/latency cell.',
        native='All 96 published physically verified opening games. Check every recorded Space decision at the window against vanilla movement_input.jump. Detect actual takeoff from ground and the native 0.42f pre-travel vertical impulse, and verify lift height. These are independent unaltered trajectories. No causal native conclusion follows from differing voluntary jumps. The separate hard model panel tests the previous trade learner against plain W-tap and its earlier weakest opponent continuation-02. No champion is selected.'))


def stats(rows,mode):
    entered=[r for r in rows if 'branches' in r]
    improve=ties=worse=lift=land=pressed=next_ours=next_theirs=0
    landing_delays=[];displacements=[];scores=[]
    for r in entered:
        b=r['branches'][mode];steps=b['steps'];score=b['score'];scores.append(score)
        delta=score-r['branches']['original']['score']
        improve+=delta>0;ties+=delta==0;worse+=delta<0
        lift+=any(s[3] for s in steps);pressed+=any(s[1] for s in steps)
        contact=next((s for s in steps if not s[2] and s[4]),None)
        if contact is not None:land+=1;landing_delays.append(contact[0])
        fresh=next((h for h in b['accepted'] if h[0]>0 and not h[2]),None)
        if fresh:next_ours+=fresh[1];next_theirs+=not fresh[1]
        # Freeze the unit vector away from the locally seen opponent at entry.
        v=r['view'];dx,dz=v[2]-v[8],v[4]-v[10];gap=math.hypot(dx,dz)
        sample=next((s for s in steps if s[0]>=500),None)
        if sample and gap:displacements.append(((sample[5]-v[2])*dx+(sample[7]-v[4])*dz)/gap)
    return dict(from_spawn=record([r['branches'][mode]['score'] if 'branches' in r else r['base'] for r in rows]),
        from_cut=record(scores),at_cut_grounded=sum(bool(r['view'][1]) for r in entered),
        outcomes_changed=dict(improved=improve,tied=ties,worsened=worse),bouts_with_space=pressed,
        bouts_with_takeoff=lift,bouts_with_landing=land,median_first_landing_ms=median(landing_delays) if land else None,
        next_fresh_own=next_ours,next_fresh_theirs=next_theirs,no_next_fresh=len(entered)-next_ours-next_theirs,
        end_censored=sum(r['branches'][mode]['end']-r['at']<WINDOW for r in entered),
        samples_at_500ms=len(displacements),median_outward_displacement=median(displacements) if displacements else None)


def summarize(rows,modes):
    groups=defaultdict(list)
    for r in rows:
        for cut in ('all',f"{r['rtt']}/{r['side']}",r.get('clock') or f"repeat-{r['rep']}"):
            groups[r['arm'],r['opponent'],cut].append(r)
    out={}
    for (a,o,cell),rr in groups.items():
        wt=groups[WT,o,cell]
        if len(wt)!=len(rr):raise ValueError('Different W-tap coverage')
        out['/'.join((a,o,cell))]=dict(games=len(rr),plain_wtap=stats(wt,'original'),branches={m:stats(rr,m) for m in modes})
    return out


def model(panel):
    field=pool()|focus.BASE|{PARENT:FROZEN/'mmc13-trades'/(PARENT+'.net')}
    if panel=='opening':
        spec=read(OPENING/'schedule.json');arms={n:field[n] for n in spec['arms']};foes={n:field[n] for n in spec['opponents']}
        archive=OPENING/'model.jsonl.gz'
    else:
        arms={n:field[n] for n in ('wtap',PARENT)};foes={n:field[n] for n in ('wtap','continuation-02-fit')}
        archive=FROZEN/'mmc13-trades/development.jsonl.gz'
    compile_model()
    with tempfile.TemporaryDirectory(dir=WORK) as t:
        d=Path(t);roster(d/'arms',arms);roster(d/'opponents',foes)
        with (d/'rows').open('w') as f:subprocess.run(java('JumpResetWindow',d/'arms',d/'opponents',*calendars('development_selection_clocks')),stdout=f,check=True)
        rows=list(map(json.loads,(d/'rows').read_text().splitlines()))
    def key(r):return tuple(r[k] for k in ('arm','opponent','clock','rtt','side'))
    with gzip.open(archive,'rt') as f:old={key(r):r for r in map(json.loads,f) if r['arm'] in arms and r['opponent'] in foes}
    if len(rows)!=len(arms)*len(foes)*32 or len(old)!=len(rows) or set(old)!=set(map(key,rows)):raise ValueError('Wrong model schedule')
    for r in rows:
        prior=old[key(r)]
        if [r['base'],r['end'],*r['fresh']]!=[prior[k] for k in ('score','end_ms','fresh','incoming')]:
            raise ValueError('Unchanged fight diverged')
        r.update(arm=native_name(r['arm']),opponent=native_name(r['opponent']))
    write_rows(DEST/(panel+'-model.jsonl.gz'),rows);dump(DEST/(panel+'-model-summary.json'),summarize(rows,MODES))


def native_bout(r,ref):
    mid=r['mid'];seat=r['side'];g=read(ROOT/'runtime/results'/(mid+'.json'));start=g['start']
    path,=(ROOT/'runtime/pov'/mid).glob(seat+'-client-*-events.jsonl.gz')
    cue=None;first=None;view=None;issued={};applied={};moves=[]
    for e in captures.events(path,{'legacy_head','duel_network','fresh_hurt_status','science_move','tick_end'}):
        if e.get('match')!=mid:continue
        typ=e['type'];t=e.get('time',e.get('time_ms'))
        if t is None or t<start or t>g['finish']:continue
        if first is not None and t>first+WINDOW+100:continue
        if typ=='fresh_hurt_status':cue=t
        elif typ in ('legacy_head','duel_network'):
            if typ=='legacy_head':
                x,y,z,vx,vy,vz,gr,sp=e['own'];tx,ty,tz=e['target'];w,ctrl,right,click=e['keys']
                key=2 if w and ctrl else int(w);side=-right;space=False;age=-1 if cue is None else t-cue
                if r['arm']!=WT:raise ValueError('Unknown ordinary controls '+mid)
            else:
                v=e['observation'];x,y,z,tx,ty,tz,vx,vy,vz,gr,sp,age=v[:12];a=e['action']
                key=a%4;side=a//4%3-1;click=bool(a//12%2);space=a>=24
            if first is None and 0<=age<100:
                first=t;view=[age,gr,x,y,z,vx,vy,vz,tx,ty,tz,sp,key,side*.98,click,space]
            issued[e['tick']]=space
        elif typ=='tick_end':
            body=e['data']['self']
            if body['name']!=g[seat]:raise ValueError('Wrong POV '+mid)
            applied[e['client_tick']]=body['movement_input']['jump']
        elif typ=='science_move' and first is not None and first<=t<=first+WINDOW:
            before=e['before'];after=e['after']
            took_off=bool(before['ground']) and before['motionY']==JUMP_VY
            if took_off and abs(after['posY']-before['posY']-JUMP_VY)>1e-9:raise ValueError('Unexpected native takeoff '+mid)
            moves.append((e['tick'],[t-first,None,before['ground'],took_off,after['ground'],
                *[after[n] for n in ('posX','posY','posZ','motionX','motionY','motionZ','sprint')]]))
    score=.5 if r['draw'] else int(r['win']);out=dict(r,base=score,end=g['finish']-start)
    if first is not None:
        for tick,step in moves:
            if tick not in issued or tick not in applied or issued[tick]!=applied[tick] or step[3] and not applied[tick]:
                raise ValueError(f'Native Space/travel disagreement {mid} tick {tick}')
            step[1]=applied[tick]
        if not moves or any(b[0]!=a[0]+1 for a,b in zip(moves,moves[1:])):raise ValueError('Missing observed travel '+mid)
        offset=first-start
        hits=[[h['t']-offset,h['own'],h['upgrade']] for h in ref['sequence'] if offset<=h['t']<=offset+WINDOW]
        out.update(at=offset,view=view,branches={'original':dict(score=score,end=out['end'],accepted=hits,steps=[v for _,v in moves])})
    return out


def native():
    games=read(OPENING/'outcomes.json')['records']
    with gzip.open(OPENING/'actual-movement.json.gz','rt') as f:valid={r['mid'] for r in json.load(f)['fixtures'].values() if r['valid']}
    with gzip.open(OPENING/'exchanges.json.gz','rt') as f:prior={r['mid']:r for r in json.load(f)['bouts']}
    if len(games)!=96 or {g['mid'] for g in games}!=valid or valid!=set(prior):raise ValueError('Different eligible opening')
    rows=[]
    for g in games:
        p=prior[g['mid']]
        if any(p[k]!=g[k] for k in ('arm','opponent','rtt','side','win','draw')):raise ValueError('Native exchange record changed')
        rows.append(native_bout(g,p))
    write_rows(DEST/'opening-native.jsonl.gz',rows);dump(DEST/'opening-native-summary.json',summarize(rows,('original',)))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('mode',choices=('register','native','opening','hard'))
    tag=p.parse_args().mode;register()
    if tag=='native':native()
    elif tag!='register':model(tag)
