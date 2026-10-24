#!/usr/bin/env python3
"""Predeclared native mechanism experiments and offline model falsification.

Private server state is used ONLY after a trial for differential testing.
No diagnostics are available to controllers. Java queries call ComboModel itself.
"""
import argparse
import asyncio
import collections
import hashlib
import gzip
import json
import math
import statistics
import subprocess
import time
from pathlib import Path
from lab import ROOT, RUNTIME, BUILD, JAVA
from lab_runtime import properties
from experiments import match, save_policy

OUT = ROOT / 'research'
AXES = ('X', 'Y', 'Z')
GATES = {'movement_position_m': 1e-4, 'movement_velocity_blocks_per_tick': 1e-5,
         'click_drag': 1e-12, 'interpolation_m': 1e-9,
         'velocity_packet_blocks_per_tick': 2 / 8000,
         'reach_boundary_exclusion_m': .0005}


def jsonl(path):
    with (gzip.open(path,'rt') if path.suffix=='.gz' else path.open()) as stream:
        for line in stream:
            try:
                yield json.loads(line)
            except json.JSONDecodeError:
                raise ValueError(f'Incomplete trace: {path}')


def client_events(result,side):
    archive=OUT/'traces'/f'{result["id"]}-{side}.jsonl.gz'
    if archive.exists():return list(jsonl(archive))
    source=RUNTIME/'bots'/result[side].removeprefix('LabSlot')/'events.jsonl'
    if not source.exists():return [] # Human input streams are not instrumented.
    from lab_runtime import match_events
    records=[e for e in match_events(source,result['id'])
             if result['start']<=e.get('time',0)<=result['finish']]
    archive.parent.mkdir(parents=True,exist_ok=True)
    with gzip.open(archive,'wt') as stream:
        for e in records:stream.write(json.dumps(e,separators=(',',':'))+'\n')
    return records


def server_events(identifier):
    archive=OUT/'traces'/f'{identifier}-server.jsonl.gz'
    return list(jsonl(archive if archive.exists() else RUNTIME/'replays'/f'{identifier}.jsonl'))


def summary(values, tolerance=None):
    values = sorted(values)
    result = {'n': len(values)}
    if values:
        result.update(median=statistics.median(values), p95=values[min(len(values)-1, int(.95*len(values)))], max=max(values))
        if tolerance is not None:
            result.update(tolerance=tolerance, failures=sum(v > tolerance for v in values), passed=all(v <= tolerance for v in values))
    return result


def body(s):
    return [s['posX'], s['posY']-4, s['posZ'], s['motionX'], s['motionY'], s['motionZ'], int(s['ground']), int(s['sprint'])]


def query_model(queries):
    payload='\n'.join('\t'.join(map(str, q)) for q in queries)+'\n'
    run=subprocess.run([str(JAVA), '-cp', str(BUILD/'classes'), 'lab.NativeModelCheck'],
                       input=payload, capture_output=True, text=True, timeout=60)
    if run.returncode:
        raise RuntimeError(run.stderr)
    predictions=[[float(v) for v in line.split('\t')] for line in run.stdout.splitlines()]
    if len(predictions)!=len(queries):
        raise ValueError('Missing model predictions')
    return predictions


def validate(result):
    identifier=result['id'];archive=OUT/'traces'/f'{identifier}-server.jsonl.gz'
    raw=server_events(identifier)
    events={s:client_events(result,s) for s in ('a','b')}
    archive=OUT/'traces'/f'{identifier}-server.jsonl.gz'
    if not archive.exists():
        with gzip.open(archive,'wt') as stream:
            for e in raw:stream.write(json.dumps(e,separators=(',',':'))+'\n')
    queries=[];references=[];errors=collections.defaultdict(list);examples=collections.defaultdict(list)
    counts=collections.Counter();latency={s:[] for s in ('a','b')};cadence={s:[] for s in ('a','b')}
    for side, records in events.items():
        updates=[e for e in records if e['type']=='science_remote_update']
        cadence[side]=[b['time']-a['time'] for a,b in zip(updates,updates[1:])]
        for e in records:
            if e['type']=='science_move':
                counts['movement_samples']+=1
                if any(e[k] for k in ('water','lava','ladder')):
                    counts['movement_out_of_scope']+=1;continue
                before=e['before'];queries.append(['move',*body(before),e['ground_acceleration'],e['air_acceleration'],before['rotationYaw'],e['strafe'],e['forward']]);references.append(('move',e,side))
            elif e['type']=='science_interpolation':
                n=e['steps']
                if n<=0:continue
                residual=max(abs(e['before']['pos'+a]+(e['goal'][i]-e['before']['pos'+a])/n-e['after']['pos'+a]) for i,a in enumerate(AXES))
                errors['interpolation'].append(residual)
            elif e['type']=='engine_attack':
                factor=.6 if e['sprint_before'] else 1
                residual=max(abs(e['v'+a+'_before']*factor-e['v'+a+'_after']) for a in ('x','z'))
                errors['click_drag'].append(residual)
                if e['target_visible_hurt']>0:
                    errors['click_drag_during_visible_hurt'].append(residual)
            elif e['type']=='velocity_packet' and e.get('own_entity') and 'applied_motion' in e:
                errors['velocity_overwrite'].append(max(abs(x-e[k]) for x,k in zip(e['applied_motion'],('vx','vy','vz'))))
            elif e['type']=='reach_probe' and 'own' in e:
                a,b=e['own'],e['target'];queries.append(['ray',a['posX'],a['posY']-4,a['posZ'],b['posX'],b['posY']-4,b['posZ']]);references.append(('ray',e,side))
                if 'native_look' in e:
                    eye=list(e['native_eye']);eye[1]-=4
                    queries.append(['ray-look',*eye,*e['native_look'],b['posX'],b['posY']-4,b['posZ']]);references.append(('ray-look',e,side))
        created=[e for e in raw if e['type']=='science_velocity_created' and e['target']==result[side] and e['time']>=result['start']]
        received=[e for e in records if e['type']=='velocity_packet' and e['own_entity']]
        cursor=0
        for sent in created:
            found=None
            for i in range(cursor,len(received)):
                r=received[i]
                if r['time']>=sent['time'] and [r[k] for k in ('vx','vy','vz')]==sent['velocity']:
                    found=i;break
            if found is not None:
                latency[side].append(received[found]['time']-sent['time']);cursor=found+1
            elif sent['time']<result['finish']-result['ping_'+side]/2-150:
                counts['missing_velocity_receipts']+=1
    server_attacks=[e for e in raw if e['type']=='science_server_attack']
    for e in server_attacks:
        a,b=e['attacker_before'],e['target_before']
        damage=12 if a['fallDistance']>0 and not a['ground'] else 8
        fresh=b['hurtResistantTime']<=10;accepted=fresh or damage>b['lastDamage']
        actual=e['score_before']!=e['score_after']
        errors['hit_acceptance'].append(int(accepted!=actual));counts['server_attack_attempts']+=1
        if a['sprint'] and damage==12 and actual:counts['sprinting_critical_accepts']+=1
        if not fresh and actual:counts['damage_upgrades']+=1
        if not fresh and not actual:counts['immune_rejections']+=1
        if fresh and actual:
            counts['fresh_hits']+=1
            if 'velocity_packet' in e:
                queries.append(['kb',*body(a),*body(b),a['rotationYaw']]);references.append(('kb',e,None))
                errors['server_motion_restore'].append(max(abs(e['target_after']['motion'+axis]-b['motion'+axis]) for axis in AXES))
            else:counts['fresh_hits_missing_packet']+=1
    predictions=query_model(queries) if queries else []
    for (kind,e,side),prediction in zip(references,predictions):
        if kind=='move':
            actual=body(e['after']);pos=max(abs(prediction[i]-actual[i]) for i in range(3));vel=max(abs(prediction[i]-actual[i]) for i in range(3,6))
            errors['movement_position'].append(pos);errors['movement_velocity'].append(vel)
            if (pos>GATES['movement_position_m'] or vel>GATES['movement_velocity_blocks_per_tick']) and len(examples['movement'])<4:
                examples['movement'].append({'side':side,'native':e,'predicted':prediction,'position_error':pos,'velocity_error':vel})
        elif kind in ('ray','ray-look'):
            ray=prediction[0]
            key='reach_classification' if kind=='ray' else 'native_look_reach_classification'
            if abs(ray-3)<=GATES['reach_boundary_exclusion_m']:counts[key+'_boundary_excluded']+=1;continue
            mismatch=(ray<=3)!=e['in_range'];errors[key].append(int(mismatch))
            if mismatch and len(examples[key])<4:examples[key].append({'side':side,'native':e,'predicted_ray':ray if math.isfinite(ray) else None})
        elif kind=='kb':
            residual=max(abs(x-y) for x,y in zip(prediction,e['velocity_packet']));errors['knockback_packet'].append(residual)
            if residual>GATES['velocity_packet_blocks_per_tick'] and len(examples['knockback'])<4:examples['knockback'].append({'native':e,'predicted':prediction,'error':residual})
    tolerances={'movement_position':GATES['movement_position_m'],'movement_velocity':GATES['movement_velocity_blocks_per_tick'],
                'click_drag':GATES['click_drag'],'click_drag_during_visible_hurt':GATES['click_drag'],'interpolation':GATES['interpolation_m'],
                'hit_acceptance':0,'reach_classification':0,'native_look_reach_classification':0,'knockback_packet':GATES['velocity_packet_blocks_per_tick'],'server_motion_restore':1e-12,'velocity_overwrite':0}
    checks={key:summary(values,tolerances[key]) for key,values in errors.items()}
    return {'match':identifier,'checks':checks,'counts':counts,'examples':examples,
            'velocity_creation_to_application_ms':{s:summary(v) for s,v in latency.items()},
            'remote_position_update_gap_ms':{s:summary(v) for s,v in cadence.items()},
            'scope':'One-step conditional model tests with recorded pre-state, native movement inputs and native packet payloads. Not an end-to-end rollout or proof of optimal play.',
            'passed_all_observed_checks':bool(checks) and all(c.get('passed',False) for c in checks.values())
                and not any(counts[k] for k in ('movement_out_of_scope','fresh_hits_missing_packet','missing_velocity_receipts'))}


def make_policies():
    variants={'science-still-probe':{'kind':'standstill','click_mode':'none'},
              'science-motion':{'kind':'scripted','click_mode':'none','input_script':'0:1:0:0|1500:0:0:0|2500:1:1:0|4000:0:0:0|5000:1:1:-1|6500:1:1:1'},
              'science-hold-probe':{'kind':'hold','click_mode':'none'},
              'science-still-click':properties(ROOT/'policies/standstill.properties'),
              'science-hold-click':properties(ROOT/'policies/hold-w.properties'),
              'science-hold-opening':{**properties(ROOT/'policies/hold-w.properties'),'click_start_ms':1800},
              'science-wake-probe':{'kind':'scripted','click_mode':'none','input_script':'0:0:0:0|8000:1:1:0'},
              'science-wake-click':{'kind':'scripted','click_mode':'fixed','input_script':'0:0:0:0|8000:1:1:0'},
              'science-wtap':properties(ROOT/'policies/wtap.properties')}
    for name,p in variants.items():
        save_policy(name,{**p,'science_trace':1,'description':'Mechanism experiment: '+name.removeprefix('science-')})
    return list(variants)


async def run(name,extended=False,holdout=False,wake=False):
    OUT.mkdir(exist_ok=True);path=OUT/(name+'.json')
    if path.exists():raise ValueError('Use a new research name; prior evidence is immutable')
    bots=make_policies()
    fixtures=[('science-motion','science-still-probe',0,0,8000,'walking, sprint transitions and two strafe directions')]
    opponents=['science-still-probe','science-still-click','science-hold-probe','science-hold-opening','science-hold-click']
    for rtt in ([(0,0),(100,100),(20,100)] if extended else [(0,0),(100,100)]):
        for opponent in opponents:
            fixtures.append(('science-wtap',opponent,*rtt,30000,'Wtap chase; only defender movement/click behavior changes'))
    if holdout:
        fixtures=[('science-wtap',opponent,*rtt,30000,'Fresh asymmetric RTT holdout; only defender opening click behavior changes')
                  for rtt in ((20,100),(100,20)) for opponent in ('science-hold-probe','science-hold-opening','science-hold-click')]
    if wake:
        fixtures=[('science-hold-probe','science-hold-probe',*rtt,8000,'No-click crossing control: isolate aim, movement and view timing without combat impulses') for rtt in ((0,0),(100,100))]
        fixtures += [('science-wtap',opponent,*rtt,30000,'Acquire against stationary B for8s, then enable its ordinary W+Ctrl; test lock persistence')
                  for rtt in ((0,0),(100,100)) for opponent in ('science-wake-probe','science-wake-click')]
    plan={'created_unix':time.time(),'stage':'preregistered','gates':GATES,'fixtures':fixtures,
          'hypotheses':[
              'The same ComboModel travel function predicts one native movement step within declared tolerances.',
              'A locally sprinting entity contact multiplies horizontal velocity by0.6 even during visible hurt; a miss is not a contact.',
              'Fresh S12 equals the equation using pre-hit SERVER motion, not current client motion; receipt overwrites local motion.',
              'Remote interpolation divides remaining error by the recorded remaining steps.',
              'A stationary no-click control is easiest to deny reach; adding W tests approach momentum; enabling clicks additionally tests local drag.',
              'A defender that withholds opening clicks1800ms allows testing acquisition separately from persistence after clicks resume.'
              ,'The native eye/look ray resolves ideal-aim classification failures; ideal-aim failures remain reported separately.'
              ,'Enabling B movement after8s of stationary acquisition breaks sustained reach denial, even when B does not click; measure first return opportunity after movement starts.'
          ],
          'limits':'30s is a finite maintenance test, never proof of an infinite combo. Fixed trials screen mechanisms, not tournament ranking. No fitted constants after seeing outcomes.',
          'policies':{bot:properties(ROOT/'policies'/f'{bot}.properties') for bot in bots},
          'agent_sha256':hashlib.sha256((BUILD/'lab-agent.jar').read_bytes()).hexdigest(),
          'results':[]}
    def checkpoint():
        tmp=path.with_suffix('.json.tmp');tmp.write_text(json.dumps(plan,indent=2)+'\n');tmp.replace(path)
    checkpoint()
    # Sequential to make exact native transition timing easiest to interpret.
    for a,b,pa,pb,duration,purpose in fixtures:
        if hashlib.sha256((BUILD/'lab-agent.jar').read_bytes()).hexdigest()!=plan['agent_sha256']:raise RuntimeError('Build changed')
        for bot in (a,b):
            if properties(ROOT/'policies'/f'{bot}.properties')!=plan['policies'][bot]:raise RuntimeError('Policy changed')
        result=await match(a,b,pa,pb,duration_ms=duration)
        checks=validate(result)
        plan['results'].append({'purpose':purpose,'game':result,'model_validation':checks});plan['stage']='running';checkpoint()
        print(json.dumps({'match':result['id'],'score':[result['hits_a'],result['hits_b']],
                         'model_checks_pass':checks['passed_all_observed_checks'],
                         'failed_checks':{k:v for k,v in checks['checks'].items() if v.get('failures')}}),flush=True)
    plan['stage']='complete';checkpoint();report(path)


def report(path):
    data=json.loads(path.read_text());lines=['# Native mechanism validation','',data['limits'],'',
        '| Match | Wtap opponent / scenario | RTT A/B | Score A–B | Fresh hits A/B | Longest continuous denial with ≥3 fresh hits | Model checks |',
        '|---|---|---|---|---|---|---|']
    aggregate=collections.defaultdict(lambda:collections.Counter())
    for row in data['results']:
        g=row['game'];v=row['model_validation'];m=g['combat_metrics'];seq=m.get('a',{}).get('native_denial_contact_sequence',{})
        lines.append(f'| {g["id"]} | {g["policy_a"]} / {g["policy_b"]} | {g["ping_a"]:g}/{g["ping_b"]:g} | {g["hits_a"]}–{g["hits_b"]} | {m["a"]["fresh_hurt_contacts"]}/{m["b"]["fresh_hurt_contacts"]} | {seq.get("duration_ms",0)/1000:.2f}s, {seq.get("fresh_contacts",0)} hits | {"pass" if v["passed_all_observed_checks"] else "FAIL / incomplete"} |')
        for k,c in v['checks'].items():aggregate[k].update({'samples':c['n'],'failures':c.get('failures',0)})
    lines+=['','These checks condition on measured native pre-state. A full duel prediction remains unvalidated.','','| Equation/check | Samples | Outside tolerance |','|---|---:|---:|']
    for k,c in aggregate.items():lines.append(f'| {k} | {c["samples"]} | {c["failures"]} |')
    path.with_suffix('.md').write_text('\n'.join(lines)+'\n');print('\n'.join(lines),flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('command',choices=['run','validate','report']);parser.add_argument('name');group=parser.add_mutually_exclusive_group();group.add_argument('--extended',action='store_true');group.add_argument('--holdout',action='store_true');group.add_argument('--wake',action='store_true');args=parser.parse_args()
    if args.command=='run':asyncio.run(run(args.name,args.extended,args.holdout,args.wake))
    elif args.command=='report':report(OUT/(args.name+'.json'))
    else:
        r=json.loads((RUNTIME/'results'/f'{args.name}.json').read_text());output=validate(r)
        (OUT/(args.name+'-validation.json')).write_text(json.dumps(output,indent=2)+'\n');print(json.dumps(output,indent=2))
