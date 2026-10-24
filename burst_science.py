#!/usr/bin/env python3
"""Preregistered, counterbalanced burst-Wtap comparisons using native clients."""
import argparse
import asyncio
import collections
import hashlib
import json
import math
import statistics
import time

from lab import ROOT, BUILD
from lab_runtime import properties
from experiments import match, save_policy, status
from parity import archive
from science import OUT, client_events, server_events
from trade_science import analyze

PAIRS=[('wtap-tick-burst','hold-w'),('wtap-tick-burst','wtap-tick'),
       ('wtap-tick','wtap'),('wtap-tick','hold-w')]
MOTION_PAIRS=[('wtap-tick-motion','hold-w-fast'),('wtap-tick-motion','wtap-tick-fast'),
              ('wtap-tick-fast','wtap-fast'),('wtap-tick-window','hold-w-fast'),
              ('wtap-tick-window','wtap-tick-fast'),('wtap-tick-fast','hold-w-fast')]


def cadence(times):
    gaps=[b-a for a,b in zip(times,times[1:])];ordered=sorted(gaps)
    return {'intervals':len(gaps),'median_ms':statistics.median(gaps) if gaps else None,
            'p95_ms':ordered[min(len(ordered)-1,int(.95*len(ordered)))] if gaps else None,
            'max_ms':max(gaps,default=None),'fraction_over_100ms':sum(v>100 for v in gaps)/len(gaps) if gaps else 1}


def diagnostic(game):
    sides={};issues=[]
    raw=server_events(game['id'])
    server_cadence=cadence([e['time'] for e in raw if e['type']=='tick' and game['start']<=e['time']<=game['finish']])
    for side in ('a','b'):
        events=client_events(game,side);injections=[e for e in events if e['type']=='click']
        consumed=[e for e in events if e['type']=='science_click']
        contacts=[e for e in events if e['type']=='engine_attack']
        linked={c['sequence']:i for i,c in zip(injections,consumed)}
        valid=len(consumed)<=len(injections) and all(0<=c['monotonic_ns']-i['monotonic_ns']<=1_000_000_000 for i,c in zip(injections,consumed))
        if not valid:issues.append(side+': physical/native click FIFO invalid')
        c=collections.Counter();reason_counts=collections.defaultdict(collections.Counter);recoil=collections.Counter()
        recoil['issued']=sum(e.get('burst_recoil',False) for e in injections)
        for e in contacts:
            dx=e['target_x']-e['own_x'];dz=e['target_z']-e['own_z'];d=math.hypot(dx,dz)
            if d==0:continue
            speed=(e['vx_before']*dx+e['vz_before']*dz)/d
            category='no_sprint_drag' if not e['sprint_before'] else 'backward_drag' if speed<-.005 else 'forward_drag' if speed>.005 else 'near_stationary_drag'
            c[category]+=1
            inj=linked.get(e['click_attempt'],{})
            reasons=[k for k in ('opening','defensive','attack_window','recoil') if inj.get('burst_'+k)]
            reason_counts['+'.join(reasons) or 'fixed'][category]+=1
            if inj.get('burst_recoil'):
                recoil['contacts']+=1
                if speed>=-.005 or not e['sprint_before']:recoil['wrong_phase']+=1
                elif abs(e['vx_after']-.6*e['vx_before'])>1e-12 or abs(e['vz_after']-.6*e['vz_before'])>1e-12:
                    recoil['missing_native_drag']+=1
                else:recoil['backward_braked']+=1
        gaps=[(b['monotonic_ns']-a['monotonic_ns'])/1e6 for a,b in zip(injections,injections[1:])]
        moves=sorted((e for e in events if e['type']=='science_move'),key=lambda e:e['monotonic_ns'])
        ticks=cadence([e['monotonic_ns']/1e6 for e in moves])
        plans=[e for e in events if e['type'] in ('burst_plan','motion_click_plan')]
        sides[side]={'physical_clicks':len(injections),'native_clicks':len(consumed),'entity_contacts':len(contacts),
            'average_cps':len(injections)/((game['finish']-game['start'])/1000),
            'click_gaps_over_150ms':sum(g>150 for g in gaps),'minimum_click_gap_ms':min(gaps,default=None),
            'consumption_fifo_valid':valid,'unconsumed_terminal_inputs':len(injections)-len(consumed),
            'contact_drag_counts':dict(c),'contact_drag_by_click_reason':{k:dict(v) for k,v in reason_counts.items()},
            'recoil_audit':dict(recoil),
            'quiet_plan_fraction':sum(not p['click_gate'] for p in plans)/len(plans) if plans else None,
            'travel_cadence':ticks}
        if recoil['wrong_phase'] or recoil['missing_native_drag']:issues.append(side+': optional recoil input failed intended native effect')
    for label,c in [('server',server_cadence),*[(s,r['travel_cadence']) for s,r in sides.items()]]:
        if c['intervals']<20 or c['p95_ms']>80 or c['fraction_over_100ms']>.01 or c['max_ms']>250:
            issues.append(label+': native tick cadence outside preregistered gate')
    return {'sides':sides,'server_cadence':server_cadence,'passed':not issues,'issues':issues,
            'drag_meaning':'Per-contact local motion projected toward the rendered opponent. Backward drag reduces current retreat; forward drag reduces pursuit. Counts are not net displacement or a causal performance attribution.'}


async def run(name,smoke=False,motion=False):
    path=OUT/(name+'.json')
    if path.exists():raise ValueError('Choose a fresh evidence name')
    deadline=time.monotonic()+150
    while True:
        try:
            ready=status()
            if sum(p.startswith('LabSlot') for p in ready['players'])>=4:break
        except (FileNotFoundError,RuntimeError,json.JSONDecodeError):pass
        if time.monotonic()>=deadline:raise RuntimeError('Native server/clients did not become ready')
        await asyncio.sleep(.5)
    pairs=MOTION_PAIRS if motion else PAIRS
    names={p:('trace-motion-' if motion else 'trace-burst-')+p for pair in pairs for p in pair}
    for original,traced in names.items():
        save_policy(traced,{**properties(ROOT/'policies'/f'{original}.properties'),'science_trace':1,'science_network_trace':1})
    fixtures=[]
    if smoke:
        for focus,opponent in pairs[:2]:
            fixtures.append(dict(focus=focus,opponent=opponent,focus_rtt=20,opponent_rtt=20,reverse=False,repeat=0,duration_ms=20000 if motion else 12000))
    elif motion:
        for repeat in range(2):
            for focus,opponent in (pairs if repeat==0 else list(reversed(pairs))):
                fixtures.append(dict(focus=focus,opponent=opponent,focus_rtt=20,opponent_rtt=20,reverse=bool(repeat),repeat=repeat,duration_ms=None))
        for pa,pb in ((20,100),(100,20)):
            for focus,opponent in (pairs[0],pairs[1],pairs[3]):
                fixtures.append(dict(focus=focus,opponent=opponent,focus_rtt=pa,opponent_rtt=pb,reverse=pa>pb,repeat=0,duration_ms=None))
    else:
        for pa,pb in ((20,20),(20,100),(100,20)):
            for repeat in range(3):
                for focus,opponent in (PAIRS if repeat%2==0 else list(reversed(PAIRS))):
                    fixtures.append(dict(focus=focus,opponent=opponent,focus_rtt=pa,opponent_rtt=pb,reverse=bool(repeat%2),repeat=repeat,duration_ms=None))
    digest=lambda:hashlib.sha256((BUILD/'lab-agent.jar').read_bytes()).hexdigest()
    sources=['burst_science.py','src/lab/BurstClickController.java','src/lab/MotionClickController.java',
             'src/lab/MomentumController.java','src/lab/Client.java','src/lab/Referee.java','experiments.py']
    plan={'created_unix':time.time(),'stage':'preregistered','agent_sha256':digest(),
          'source_sha256':{s:hashlib.sha256((ROOT/s).read_bytes()).hexdigest() for s in sources},
          'policies':{p:properties(ROOT/'policies'/f'{p}.properties') for p in names.values()},
          'fixtures':fixtures,'concurrency':2,
          'primary_metric':'First to100 accepted native sword attacks, with fresh hits and damage upgrades also reported separately.',
          'intervention':'Only click scheduling changes between wtap-tick-burst and wtap-tick. Same one-movement-update W release, aim, reaction delay and physical100ms input limiter.',
          'hypothesis':'Defensive bursts may retain backward contact drag while reducing unnecessary forward contact drag; effects on critical upgrades, sprint state and missed fresh windows can offset that benefit.',
          'timing_gate':'Server/client travel p95<=80ms, <=1% gaps>100ms, max<=250ms. Native input/reach/reaction/replication gates also required.',
          'results':[]}
    if motion:
        plan.update({'reaction_ms':0,'cohort':'current-motion burst and window-only ablation',
            'intervention':'Window-only and motion-burst differ only by optional current-backward-motion recoil clicks. All fast controls use the same frame plus native-tick input polling and global100ms physical limiter. A planned150ms post-cue W reset preserves the original movement timing while observations and recoil decisions have zero artificial delay.',
            'hypothesis':'Recoil clicks should actually multiply backward native motion by0.6. Any score benefit is tested rather than assumed; recoil cancellation can preserve an opponent return opportunity.',
            'timing_choice':'50ms attack lead compensates for removing frame-to-consumption delay; frozen before the motion cohort, based on old trace timing. No hidden server immunity input.',
            'design':'Two side-swapped games per each of six pairings at20/20. One game per oriented asymmetric ping for motion-v-hold, motion-v-fixed, and window-v-hold. Exploratory18-game cohort, no parameter search.'})
    def checkpoint():
        temp=path.with_suffix('.tmp');temp.write_text(json.dumps(plan,indent=2)+'\n');temp.replace(path)
    checkpoint()
    async def fixture(f):
        assert digest()==plan['agent_sha256'],'Build changed during suite'
        a,b=names[f['focus']],names[f['opponent']];pa,pb=f['focus_rtt'],f['opponent_rtt']
        if f['reverse']:a,b,pa,pb=b,a,pb,pa
        for p in (a,b):assert properties(ROOT/'policies'/f'{p}.properties')==plan['policies'][p]
        game=await match(a,b,pa,pb,duration_ms=f['duration_ms']);archive(game)
        diag=diagnostic(game);detail=analyze(game)
        row={'fixture':f,'game':game,'diagnostic':diag,'exchanges':detail}
        plan['results'].append(row);plan['stage']='running';checkpoint()
        fs='b' if f['reverse'] else 'a';os='a' if f['reverse'] else 'b'
        print(json.dumps({'id':game['id'],'focus':f['focus'],'opponent':f['opponent'],
            'rtt':[f['focus_rtt'],f['opponent_rtt']],'score':[game['hits_'+fs],game['hits_'+os]],
            'cps':[round(diag['sides'][s]['average_cps'],2) for s in (fs,os)],'timing_passed':diag['passed']}),flush=True)
        if not diag['passed']:raise RuntimeError('Timing/data quality failed: '+game['id']+' '+str(diag['issues']))
    try:
        for i in range(0,len(fixtures),2):
            batch=await asyncio.gather(*(fixture(f) for f in fixtures[i:i+2]),return_exceptions=True)
            failures=[str(x) for x in batch if isinstance(x,BaseException)]
            if failures:raise RuntimeError('; '.join(failures))
    except BaseException as e:
        plan['stage']='incomplete';plan['error']=str(e);checkpoint();raise
    plan['stage']='complete';checkpoint()


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('name');p.add_argument('--smoke',action='store_true');p.add_argument('--motion',action='store_true')
    a=p.parse_args();asyncio.run(run(a.name,a.smoke,a.motion))
