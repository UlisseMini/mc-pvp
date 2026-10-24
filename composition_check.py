#!/usr/bin/env python3
"""Uncorrected forward prediction, using recorded A inputs and a known simple B.

Initial resting setup and clock phase are measured offline. No position, velocity,
hurt cue, server hit or packet payload after initialization corrects the rollout.
This separates correct per-transition equations from correct event composition.
"""
import argparse
import bisect
import json
import hashlib
import math
import subprocess
import tempfile
from pathlib import Path
from lab import ROOT, RUNTIME, JAVA, JAVAC
from science import jsonl, summary, client_events, server_events


def evaluate(result,classes):
    identifier=result['id'];raw=server_events(identifier)
    records={s:[e for e in client_events(result,s) if e.get('time',0)<result['start']+10000] for s in ('a','b')}
    moves={s:[e for e in records[s] if e['type']=='science_move'] for s in ('a','b')}
    if not all(moves.values()):return {'match':identifier,'excluded':'Missing native travel samples'}
    policy=result['parameters_b']
    if policy.get('kind') not in ('hold','standstill'):return {'match':identifier,'excluded':'This composition fixture requires known hold/standstill B'}
    base=moves['a'][0]['time'];initial={s:moves[s][0]['before'] for s in ('a','b')}
    if any(abs(initial[s]['posY']-4)>.001 or math.hypot(initial[s]['motionX'],initial[s]['motionZ'])>.001 for s in initial):
        return {'match':identifier,'excluded':'Initial setup is not at rest'}
    server=next(e['time'] for e in raw if e['type']=='tick' and e['time']>=base)
    b_first=moves['b'][0]['time'];b_phase=(b_first-base)%50
    first_moving=next((i for i,e in enumerate(moves['b']) if e['forward']>.01),None)
    # Map the declared onset onto its native tick index. Comparing a jittered
    # wall-time threshold to a nominal grid can spuriously defer it one tick.
    b_control=base+b_phase+50*(first_moving-int(b_first<base)) if first_moving is not None else base+100000
    b_click=next((e['time'] for e in records['b'] if e['type']=='click'),base+100000)
    offset=initial['a']['posX']
    header=[result['ping_a'],result['ping_b'],(server-base)%50,(b_first-base)%50,0,
            int(policy['kind']=='hold'),int(policy.get('click_mode','fixed')!='none'),b_control-base,b_click-base,
            0,initial['a']['posZ'],initial['b']['posX']-offset,initial['b']['posZ']]
    controls=[e for e in records['a'] if e['type']=='observation'];times=[e['time'] for e in controls]
    clicks=[e for e in records['a'] if e['type']=='click'];click_times=[e['time'] for e in clicks]
    inputs=[];last=base-50
    for m in moves['a']:
        now=m['time'];i=bisect.bisect_right(times,now)-1
        # In this fixed Wtap fixture Ctrl follows W. Native travel input avoids
        # undersampling a50ms release in the slower generic observation log.
        action='SPRINT' if m['forward']>.01 else 'RELEASE'
        consumed=bisect.bisect_left(click_times,now)>bisect.bisect_left(click_times,last)
        inputs.append(f'{action}\t{str(consumed).lower()}');last=now
    payload='\t'.join(map(str,header))+'\n'+'\n'.join(inputs)+'\n'
    run=subprocess.run([str(JAVA),'-cp',str(classes),'NativeRollout'],input=payload,capture_output=True,text=True,timeout=30)
    if run.returncode:raise RuntimeError(run.stderr)
    predictions=[[float(x) for x in line.split('\t')] for line in run.stdout.splitlines()]
    b_times=[e['time'] for e in moves['b']];rows=[]
    for a,p in zip(moves['a'],predictions):
        nominal_b=base+p[0]-50+header[3];j=bisect.bisect_left(b_times,nominal_b)
        choices=[k for k in (j-1,j) if 0<=k<len(b_times)]
        if not choices:continue
        j=min(choices,key=lambda k:abs(b_times[k]-nominal_b));b=moves['b'][j]
        if abs(b['time']-nominal_b)>30:continue
        def error(native,pred):return math.sqrt(sum((native['pos'+axis]-(pred[k]+(offset if axis=='X' else 4 if axis=='Y' else 0)))**2 for k,axis in enumerate(('X','Y','Z'))))
        rows.append({'elapsed_ms':a['time']-base,'predicted_a':[p[1]+offset,p[2]+4,p[3]],'predicted_b':[p[4]+offset,p[5]+4,p[6]],
                     'actual_a':[a['after']['pos'+x] for x in ('X','Y','Z')],'actual_b':[b['after']['pos'+x] for x in ('X','Y','Z')],
                     'a_position_error_m':error(a['after'],p[1:4]),'b_position_error_m':error(b['after'],p[4:7]),
                     'predicted_fresh_hits':[int(p[13]),int(p[14])],'predicted_upgrades':[int(p[15]),int(p[16])]})
    horizons={}
    hits=[e for e in raw if e['type']=='hit']
    for h in (150,500,1000,2000,5000,9000):
        eligible=[r for r in rows if abs(r['elapsed_ms']-h)<=60]
        if not eligible:continue
        row=min(eligible,key=lambda r:abs(r['elapsed_ms']-h));elapsed=row['elapsed_ms']
        fresh=[e for e in hits if e['time']<=base+elapsed+50 and e['target_state']['hurtResistantTime']==20]
        horizons[str(h)]={**row,'actual_fresh_hits':[sum(e['attacker']==result[s] for e in fresh) for s in ('a','b')]}
    return {'match':identifier,'initial_clock_parameters':header[:9],
            'method':'One initial resting state, measured initial clock phases, recorded A key/click INPUTS,105ms modeled B click clock. Zero state corrections or confirmed-hit injection after start. Fixed50ms scheduler; clock drift and forced tracking remain model errors.',
            'horizons':horizons,'rows':rows}


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('research_name');args=parser.parse_args()
    source=ROOT/'research'/f'{args.research_name}.json';data=json.loads(source.read_text())
    with tempfile.TemporaryDirectory(prefix='native-rollout-',dir=RUNTIME/'tmp') as directory:
        subprocess.run([str(JAVAC),'-d',directory,str(ROOT/'src/lab/ComboModel.java'),str(ROOT/'tests/NativeRollout.java')],check=True)
        output=[evaluate(r['game'],directory) for r in data['results'] if r['game']['policy_a']=='science-wtap']
    path=source.with_name(source.stem+'-composition.json');path.write_text(json.dumps({'stage':'diagnostic, no fitted corrections',
        'combo_model_sha256':hashlib.sha256((ROOT/'src/lab/ComboModel.java').read_bytes()).hexdigest(),
        'validator_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),'games':output},indent=2)+'\n')
    for g in output:
        print(g['match'],{h:{k:v for k,v in r.items() if k in ('a_position_error_m','b_position_error_m','predicted_fresh_hits','actual_fresh_hits')} for h,r in g.get('horizons',{}).items()},flush=True)
