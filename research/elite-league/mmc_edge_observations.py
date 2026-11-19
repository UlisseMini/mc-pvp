"""Measure the same local edge cues on recorded Minecraft input heads and untouched simulated bouts."""
import argparse
from collections import defaultdict
import gzip
import json
import math
from pathlib import Path
from statistics import median
import subprocess
import sys
import tempfile
import time

from adaptation import dump, freeze, properties, sha
from audit import captures
from mmc_league import EVIDENCE, FROZEN, WORK, WT, native_name
from mmc_scorecard import record
from run import HERE, ROOT, calendars, compile_model, java
from tournament import roster

DEST = EVIDENCE / 'edge-observations'
RADII = (3.5,5.0)


def read(path):
    return json.loads(path.read_text())


def packed(path, obj):
    freeze(path,gzip.compress(json.dumps(obj,separators=(',',':'),allow_nan=False).encode(),mtime=0))


def unpacked(path):
    with gzip.open(path,'rt') as f:return json.load(f)


def register():
    DEST.mkdir(exist_ok=True)
    dump(DEST/'protocol.json',dict(
        design='Audit every already certified opening fight as a retrospective control. Separately score every edge-confirm fixture from the predeclared edge training and confirmation plan against its own registered regulation score and plain W-tap. No extra match admissions or alternate input during these observations.',
        frozen_fit=sha(FROZEN/'mmc13-edge-fit/protocol.json'), opening_schedule=sha(EVIDENCE/'opening/schedule.json'),
        condition='From the first own controller observation use the midpoint of the fighters at spawn as center. At the first input with any prior local fresh hurt cue, radius at least 3.5 or 5.0 blocks and own velocity projecting outward by at least 0.02 block/tick, record this and subsequent inputs through the first sample 500 ms later. Include bouts where neither condition is reached, short observations and a local body already below the platform at entry. Use the same projected ground-plane geometry for W-tap.',
        samples=['elapsed bout ms','input tick','own x relative to center','own y','own z relative to center','own vx','own vy','own vz','observed target x relative to center','observed target z relative to center','grounded','forward/sprint key','strafe key (positive left)','click gate','jump key'],
        analysis='Compare unconditional win/draw/loss, trigger incidence, outward speed, and actual target-relative centerward strafe on the first six available inputs. Treat the end of a fight before 500 ms as censored for 500-ms displacement. Provide all seat/RTT cells and raw rounds. These two surfaces have independent starts. A conditional native outcome does not establish a causal effect of steering.'))


def model(stage):
    dest=DEST/stage; dest.mkdir(exist_ok=True)
    spec=read(EVIDENCE/stage/'schedule.json')
    clocks=calendars('development_selection_clocks') if stage=='opening' else sorted((WORK/'mmc13-edge-confirm-clocks').glob('*.txt.gz'))
    if len(clocks)!=8:raise ValueError('Incomplete donor panel')
    compile_model()
    with tempfile.TemporaryDirectory(dir=WORK) as d:
        tmp=Path(d)
        for label in ('arms','opponents'):
            field={}
            for name,ident in spec[label].items():
                if name=='wtap' and ident=='builtin-wtap':field[name]=None;continue
                data=properties(ROOT/'policies'/(native_name(name)+'.properties'))
                p=tmp/(native_name(name)+'.net')
                p.write_text(data['duel_network']+'\n')
                if sha(p)!=ident:raise ValueError('Controller fingerprint mismatch for '+name)
                field[name]=p
            roster(tmp/label,field)
        with (tmp/'rows').open('w') as out:subprocess.run(java('EdgeObservationStudy',tmp/'arms',tmp/'opponents',*clocks),stdout=out,check=True)
        rows=list(map(json.loads,(tmp/'rows').read_text().splitlines()))
    registered=EVIDENCE/stage/'model.jsonl.gz'
    def key(r):return tuple(r[k] for k in ('arm','opponent','clock','side','rtt'))
    with gzip.open(registered,'rt') as f:old={key(r):r for r in map(json.loads,f)}
    if len(rows)!=len(old) or set(map(key,rows))!=set(old):raise ValueError('Different model schedule')
    for r in rows:
        ref=old[key(r)]
        if [r['score'],r['end'],*r['fresh']]!=[ref[n] for n in ('score','end_ms','fresh','incoming')]:
            raise ValueError('Observed trajectory differs from original')
        for n in ('arm','opponent'):r[n]=native_name(r[n])
    packed(dest/'model.json.gz',rows)


def physical(row):
    mid=row['mid'];seat=row['side'];g=read(ROOT/'runtime/results'/(mid+'.json'))
    path,=(ROOT/'runtime/pov'/mid).glob(seat+'-client-*-events.jsonl.gz')
    bouts=[[],[]];center=None;last=None;seen=0;cue=False
    for e in captures.events(path,{'duel_network','legacy_head','fresh_hurt_status','fresh_target_hurt_status'}):
        if e.get('match')!=mid:continue
        if e['type'] in ('fresh_hurt_status','fresh_target_hurt_status'):
            if e['time']>=g['start']:cue=True
            continue
        if e['type']=='duel_network':
            v=e['observation'];x,y,z,tx,_,tz,vx,vy,vz,ground=v[:10]
            a=e['action'];key=a%4;side=a//4%3-1;click=bool(a//12%2);jump=a>=24
            hurt=any(vv!=-1 for vv in v[11:13])
            if not e['released']:raise ValueError('Gated network in regulation '+mid)
        else:
            x,y,z,vx,vy,vz,ground,_=e['own'];tx,_,tz=e['target'];w,sp,right,click=e['keys']
            key=2 if w and sp else 1 if w else 0;side=-right;jump=False;hurt=cue
            if g['policy_'+seat]!=WT:raise ValueError('Unexpected ordinary input policy')
        if center is None:
            center=((x+tx)/2,(z+tz)/2)
            if center!=((g['arena']+1)*4096+.5,.5):raise ValueError('First own observation did not contain regulation spawns '+mid)
        if not g['start']<=e['time']<=g['finish']:continue
        if last is not None and e['tick']!=last+1:raise ValueError('Missing native input head '+mid)
        seen+=1;last=e['tick'];dt=e['time']-g['start']
        x-=center[0];z-=center[1];tx-=center[0];tz-=center[1]
        radius=math.hypot(x,z)
        outward=(x*vx+z*vz)/radius if radius else 0
        for limit,lst in zip(RADII,bouts,strict=True):
            if lst and lst[-1][0]>=lst[0][0]+500:continue
            if not lst and (not hurt or radius<limit or outward<.02):continue
            lst.append([dt,e['tick'],x,y,z,vx,vy,vz,tx,tz,ground,key,side,click,jump])
        if all(v and v[-1][0]>=v[0][0]+500 for v in bouts):break
    if not seen:raise ValueError('No native input record '+mid)
    return {**row,'score':1 if row['win'] else .5 if row['draw'] else 0,'center':center,'observations':bouts}


def native(stage):
    dest=DEST/stage;dest.mkdir(exist_ok=True)
    base=EVIDENCE/stage; games=read(base/'outcomes.json')['records']
    with gzip.open(base/'native.json.gz','rt') as f:bundle=json.load(f)
    if bundle['summary']['binding_disagreements'] or {r['mid'] for r in games}!={r['mid'] for r in bundle['matches']}:
        raise ValueError('Tournament outcomes missing physical audits')
    cache=WORK/('edge-obs-'+stage);cache.mkdir(exist_ok=True)
    for r in games:
        path=cache/(r['mid']+'.json')
        if not path.exists():dump(path,physical(r))
    rows=[read(cache/(r['mid']+'.json')) for r in games]
    packed(dest/'native.json.gz',rows)


def measure(samples):
    if not samples:return None
    def radius(h):return math.hypot(h[2],h[4])
    def direction(h):
        x,z=h[2],h[4];tx,tz=h[8]-x,h[9]-z
        p=-x*tz+z*tx
        return (p>0)-(p<0)
    h=samples[0];r=radius(h);last=samples[-1]
    comparable=[s for s in samples[:6] if direction(s)!=0]
    return dict(radius=r,outward=(h[2]*h[5]+h[4]*h[7])/r,ms=h[0],ground=h[10],
                centerable=len(comparable),center=sum(s[12]==direction(s) for s in comparable),
                away=sum(s[12]==-direction(s) for s in comparable),n=len(samples[:6]),
                dx_after_500=radius(last)-r if last[0]>=h[0]+500 else None)


def summarize(stage):
    dest=DEST/stage;sources={}
    for mode in ('model','native'):
        rows=unpacked(dest/(mode+'.json.gz'));cells=defaultdict(list)
        for r in rows:
            for split in ('all',f"{r['rtt']}/{r['side']}"):
                for radius,samples in zip(RADII,r['observations'],strict=True):
                    cells[r['arm'],r['opponent'],str(radius),split].append((r['score'],measure(samples)))
        data={}
        for k,items in cells.items():
            hits=[v for _,v in items if v];ds=[v['dx_after_500'] for v in hits if v['dx_after_500'] is not None]
            data[k]=dict(games=record([s for s,_ in items]),reached=record([s for s,v in items if v]),
                reached_grounded=sum(v['ground'] for v in hits),median_outward=median(v['outward'] for v in hits) if hits else None,
                strafe_opportunities=sum(v['centerable'] for v in hits),centerward=sum(v['center'] for v in hits),
                away=sum(v['away'] for v in hits),observed_500ms=len(ds),median_radius_change=median(ds) if ds else None)
        sources[mode]={'/'.join(k):{'controller':v,'plain_wtap':data[(WT,*k[1:])]} for k,v in data.items()}
    dump(dest/'summary.json',dict(protocol=sha(DEST/'protocol.json'),sources={m:sha(dest/(m+'.json.gz')) for m in sources},metrics=sources))


def run(stage):
    register()
    dest=DEST/stage;dest.mkdir(exist_ok=True)
    stop=read(WORK/'admission-deadline.json')['unix_time']+600
    for mode,condition,func in [('model','model.jsonl.gz',model),('native','outcomes.json',native)]:
        while not (EVIDENCE/stage/condition).exists():
            if time.time()>stop:return
            time.sleep(12)
        if (dest/(mode+'.json.gz')).exists():continue
        if mode=='native':
            subprocess.run([sys.executable,str(HERE/'cpu_limit.py'),'--audit',sys.executable,__file__,stage,'--native-only'],check=True)
        else:func(stage)
    summarize(stage)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('stage',choices=('opening','edge-confirm'))
    p.add_argument('--native-only',action='store_true');args=p.parse_args()
    if args.native_only:native(args.stage)
    else:run(args.stage)
