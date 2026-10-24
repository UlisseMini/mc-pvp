#!/usr/bin/env python3
"""Replay both recorded input streams and measured clocks, with no later state correction."""
import argparse
import bisect
import hashlib
import json
import math
import subprocess
import tempfile
from pathlib import Path
from lab import ROOT, RUNTIME, JAVA, JAVAC
from science import client_events, jsonl, summary, server_events


def evaluate(result,classes,server_shift=0,head_yaw=False,fall_damage=False,monotonic_clicks=False):
    raw=server_events(result['id'])
    records={s:client_events(result,s) for s in ('a','b')}
    moves={s:[e for e in records[s] if e['type']=='science_move'] for s in records}
    if not all(moves.values()):return {'match':result['id'],'excluded':'Missing travel trace'}
    initial={s:moves[s][0]['before'] for s in moves}
    if any(abs(s['posY']-4)>.001 or math.hypot(s['motionX'],s['motionZ'])>.001 for s in initial.values()):
        return {'match':result['id'],'excluded':'Initial state is not settled'}
    server=[e for e in raw if e['type']=='tick' and e['time']>=result['start']]
    base=min(moves['a'][0]['time'],moves['b'][0]['time'],server[0]['time']+server_shift)
    offset=initial['a']['posX']
    def pos(s):return [s['posX']-offset,s['posY']-4,s['posZ']]
    views={s:next((e['before'] for e in records[s] if e['type']=='science_interpolation'),initial['b' if s=='a' else 'a']) for s in records}
    header=['INIT',0,result['ping_a'],result['ping_b'],0,*pos(initial['a']),*pos(initial['b']),*pos(views['a']),*pos(views['b'])]
    events=[];input_differences=[]
    for side in ('a','b'):
        click_times=[e['time'] for e in records[side] if e['type']=='click']
        click_ns=[e['monotonic_ns'] for e in records[side] if e['type']=='click'] if monotonic_clicks else []
        previous=base-50
        previous_ns=moves[side][0]['monotonic_ns']-50_000_000 if monotonic_clicks else None
        for e in moves[side]:
            if e['time']-base>10000:break
            if abs(e['strafe'])>.01:raise ValueError('Clock replay currently covers W-only scripts')
            action='SPRINT' if e['forward']>.01 else 'RELEASE'
            count=bisect.bisect_left(click_times,e['time'])-bisect.bisect_left(click_times,previous)
            if monotonic_clicks:
                precise_count=bisect.bisect_left(click_ns,e['monotonic_ns'])-bisect.bisect_left(click_ns,previous_ns)
                if precise_count!=count:input_differences.append({'side':side,'time_ms':e['time']-base,'wall_ms_count':count,'monotonic_count':precise_count})
                count=precise_count;previous_ns=e['monotonic_ns']
            if count>1:raise ValueError('Multiple physical clicks per native tick need explicit queue replay')
            events.append((e['time']-base,1 if side=='a' else 2,side.upper(),action,int(count>0),e))
            previous=e['time']
    for e in server:
        t=e['time']+server_shift-base
        if 0<=t<=10000:events.append((t,0,'SERVER','-',0,e))
    events.sort(key=lambda e:e[:2])
    payload='\t'.join(map(str,header))+'\n'+'\n'.join(f'{t}\t{k}\t{a}\t{c}' for t,_,k,a,c,_ in events)+'\n'
    run=subprocess.run([str(JAVA),'-Dlab.model_head_yaw='+str(head_yaw).lower(),'-Dlab.model_fall_damage='+str(fall_damage).lower(),'-cp',str(classes),'NativeClockReplay'],input=payload,capture_output=True,text=True,timeout=30)
    if run.returncode:raise RuntimeError(run.stderr)
    lines=run.stdout.splitlines();names=lines[0].split('\t');outputs=[dict(zip(names,l.split('\t'))) for l in lines[1:]]
    if len(outputs)!=len(events):raise ValueError('Clock replay row count mismatch')
    errors={s:[] for s in ('a','b')};rows=[];combat_events=[];previous_combat=None
    for event,out in zip(events,outputs):
        t,_,kind,_,_,native=event
        combat={k:int(float(out[k])) for k in ('accepted_fresh_hits','received_fresh_hits','accepted_upgrades','received_upgrades','own_fall_events','target_fall_events')}
        if combat!=previous_combat:combat_events.append({'time_ms':t,**combat});previous_combat=combat
        if kind=='SERVER':continue
        side=kind.lower();actual=pos(native['after']);pred=[float(out[side+'_'+axis]) for axis in ('x','y','z')]
        error=math.dist(actual,pred);errors[side].append(error)
        rows.append({'time_ms':t,'side':side,'error_m':error,'actual_relative':actual,'predicted_relative':pred})
    horizons={}
    for h in (150,500,1000,2000,5000,9000):
        horizons[str(h)]={}
        for side in ('a','b'):
            choices=[r for r in rows if r['side']==side and abs(r['time_ms']-h)<=60]
            if choices:horizons[str(h)][side]=min(choices,key=lambda r:abs(r['time_ms']-h))['error_m']
    return {'match':result['id'],'server_timestamp_shift_ms':server_shift,'model_head_yaw':head_yaw,'model_fall_damage':fall_damage,
            'monotonic_clicks':monotonic_clicks,'click_assignment_differences':input_differences,'errors':{s:summary(v) for s,v in errors.items()},
            'horizons':horizons,'rows':rows,'predicted_combat_changes':combat_events,
            'limits':'Actual recorded input consumption and client travel/server-end timestamps; server±5ms is declared sensitivity, not fitted correction. Per-connection dispatch still approximated by tick events. No future native state/impulse/hit is injected.'}


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('research_name');parser.add_argument('--matches',nargs='+');parser.add_argument('--head-yaw',action='store_true');parser.add_argument('--fall-damage',action='store_true');parser.add_argument('--monotonic-clicks',action='store_true');args=parser.parse_args()
    source=ROOT/'research'/f'{args.research_name}.json';data=json.loads(source.read_text())
    with tempfile.TemporaryDirectory(prefix='native-clock-',dir=RUNTIME/'tmp') as classes:
        subprocess.run([str(JAVAC),'-d',classes,str(ROOT/'src/lab/ComboModel.java'),str(ROOT/'tests/NativeClockReplay.java')],check=True)
        output=[evaluate(row['game'],classes,shift,args.head_yaw,args.fall_damage,args.monotonic_clicks) for row in data['results']
                if row['game']['policy_a']=='science-wtap' and (not args.matches or row['game']['id'] in args.matches)
                for shift in (-5,0,5)]
    path=source.with_name(source.stem+'-clock-replay'+('-head-yaw' if args.head_yaw else '')+('-fall-damage' if args.fall_damage else '')+('-monotonic-clicks' if args.monotonic_clicks else '')+'.json')
    path.write_text(json.dumps({'model_sha256':hashlib.sha256((ROOT/'src/lab/ComboModel.java').read_bytes()).hexdigest(),
                               'adapter_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                               'games':output},indent=2)+'\n')
    for g in output:print(g['match'],g.get('server_timestamp_shift_ms'),g.get('horizons',g.get('excluded')),flush=True)
