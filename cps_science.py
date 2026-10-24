#!/usr/bin/env python3
"""Frozen one-tick W-tap click-rate comparison; native input and physics only."""
import argparse
import asyncio
import collections
import hashlib
import json
import time

from lab import ROOT,BUILD,RUNTIME
from lab_runtime import properties
from experiments import match,save_policy,status
from parity import archive
from science import OUT,client_events
from burst_science import diagnostic
from trade_science import analyze

RATES=(15,20,25,30)


def delivery(game):
    result={}
    for side in ('a','b'):
        events=client_events(game,side)
        clicks=[e for e in events if e['type']=='click']
        native=[e for e in events if e['type']=='science_click']
        by_tick=collections.Counter(e['tick'] for e in native)
        span=(clicks[-1]['monotonic_ns']-clicks[0]['monotonic_ns'])/1e9 if len(clicks)>1 else 0
        result[side]={'declared_cps':int(game['parameters_'+side].get('click_cps',10)),
            'physical_input_cps_during_click_span':(len(clicks)-1)/span if span else None,
            'inputs':len(clicks),'native_consumptions':len(native),
            'native_clicks_per_active_tick_histogram':dict(collections.Counter(by_tick.values())),
            'ticks_with_multiple_clicks':sum(v>1 for v in by_tick.values()),
            'maximum_clicks_consumed_in_one_tick':max(by_tick.values(),default=0)}
    return result


async def run(name,smoke=False):
    path=OUT/(name+'.json')
    if path.exists():raise ValueError('Choose a fresh evidence name')
    deadline=time.monotonic()+150
    while True:
        try:
            if sum(p.startswith('LabSlot') for p in status()['players'])==8:break
        except (FileNotFoundError,RuntimeError,json.JSONDecodeError):pass
        if time.monotonic()>deadline:raise RuntimeError('Server/clients not ready')
        await asyncio.sleep(.5)
    names={rate:f'trace-cps-wtap-{rate}' for rate in (10,*RATES)}
    for rate,name2 in names.items():
        save_policy(name2,{**properties(ROOT/'policies'/f'wtap-cps{rate}.properties'),
                           'science_trace':1,'science_network_trace':1})
    # Check the claimed one-variable comparison before launching any match.
    def normalized(p):return {k:v for k,v in p.items() if k not in ('click_cps','description')}
    policies={p:properties(ROOT/'policies'/f'{p}.properties') for p in names.values()}
    assert all(normalized(p)==normalized(policies[names[10]]) for p in policies.values())
    fixtures=[]
    if not smoke:fixtures.append({'cps':10,'reverse':False,'repeat':0})
    for repeat in range(1 if smoke else 4):
        for rate in RATES if repeat%2==0 else reversed(RATES):
            fixtures.append({'cps':rate,'reverse':bool(repeat%2),'repeat':repeat})
    if not smoke:fixtures.append({'cps':10,'reverse':True,'repeat':1})
    digest=lambda:hashlib.sha256((BUILD/'lab-agent.jar').read_bytes()).hexdigest()
    frame_sleep=json.loads((RUNTIME/'manager.json').read_text()).get('frame_sleep_ms',10)
    sources=['cps_science.py','experiments.py','burst_science.py','trade_science.py','lab.py','lab_runtime.py',
             'src/lab/Client.java','src/lab/ClickRate.java','src/lab/MomentumController.java','src/lab/Referee.java']
    plan={'created_unix':time.time(),'stage':'preregistered','agent_sha256':digest(),
        'source_sha256':{p:hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in sources},
        'policies':policies,'fixtures':fixtures,'concurrency':2,'rtt_ms':[20,20],'frame_sleep_ms':frame_sleep,
        'intervention':'Only click_cps changes: same zero-delay continuous-click one-tick W-tap, reset 150ms after the visible landed cue. Global physical pacing from completed input; no catch-up inputs. Default policies retain 10 CPS.',
        'primary_analysis':'Fresh native sword-hit margin, fresh-hit ratio and rate, with critical damage upgrades separate. No assumption that an upgrade is a fresh knockback contact.',
        'hypotheses':['Higher CPS may improve coverage of native input ticks and increase opportunities to reduce recoil, while also adding forward contact drag.',
                      'Above20 CPS the client can consume multiple queued clicks in one native tick; this does not create extra movement ticks. Benefit may saturate or depend on phase rather than increase monotonically.'],
        'stopping_rule':'Existing first-to-100 accepted attacks, unchanged to isolate CPS. Fresh counts are from those same variable-duration games, not a first-to-100-fresh tournament.',
        'design':'Four games per rate, two per starting side, reversing rate order every repeat; two 10-vs-10 controls bracket the cohort. 18 full games; smoke is four 12-second checks and is excluded.',
        'timing_gate':'Existing native input/reach/replication checks. Client/server movement p95<=80ms, at most1% gaps>100ms, max<=250ms. Physical input span rate >=90% of declared ceiling; report actual rates and native same-tick processing.',
        'results':[]}
    def checkpoint():
        temporary=path.with_suffix('.tmp');temporary.write_text(json.dumps(plan,indent=2)+'\n');temporary.replace(path)
    checkpoint()
    async def fixture(f):
        assert digest()==plan['agent_sha256']
        for source,h in plan['source_sha256'].items():assert hashlib.sha256((ROOT/source).read_bytes()).hexdigest()==h,source
        a,b=names[f['cps']],names[10]
        if f['reverse']:a,b=b,a
        for p in (a,b):assert properties(ROOT/'policies'/f'{p}.properties')==policies[p]
        game=await match(a,b,20,20,duration_ms=12000 if smoke else None)
        archive(game);diag=diagnostic(game);detail=analyze(game);inputs=delivery(game)
        for s in ('a','b'):
            if game['client_frame_sleep_ms'].get(s)!=frame_sleep:
                diag['issues'].append(s+': headless frame setting differs from frozen cohort');diag['passed']=False
            if inputs[s]['physical_input_cps_during_click_span']<.9*inputs[s]['declared_cps']:
                diag['issues'].append(s+': input delivery below declared 90% floor');diag['passed']=False
        row={'fixture':f,'game':game,'diagnostic':diag,'exchanges':detail,'delivery':inputs}
        plan['results'].append(row);plan['stage']='running';checkpoint()
        fs,os=('b','a') if f['reverse'] else ('a','b')
        print(json.dumps({'id':game['id'],'cps':f['cps'],
            'accepted':[game['hits_'+s] for s in (fs,os)],
            'fresh':[detail['sides'][s]['counts']['fresh'] for s in (fs,os)],
            'measured_cps':[round(inputs[s]['physical_input_cps_during_click_span'],2) for s in (fs,os)],
            'timing_passed':diag['passed']}),flush=True)
        if not diag['passed']:raise RuntimeError(game['id']+': '+str(diag['issues']))
    try:
        for i in range(0,len(fixtures),2):
            batch=await asyncio.gather(*(fixture(f) for f in fixtures[i:i+2]),return_exceptions=True)
            errors=[str(e) for e in batch if isinstance(e,BaseException)]
            if errors:raise RuntimeError('; '.join(errors))
    except BaseException as error:
        plan['stage']='incomplete';plan['error']=str(error);checkpoint();raise
    plan['stage']='complete';checkpoint()


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('name');parser.add_argument('--smoke',action='store_true')
    args=parser.parse_args();asyncio.run(run(args.name,args.smoke))
