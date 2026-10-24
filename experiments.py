#!/usr/bin/env python3
"""Small reproducible real-engine tournaments and parameter evolution."""
import argparse
import asyncio
import collections
import fcntl
import hashlib
import itertools
import json
import math
from pathlib import Path
import random
import re
import time
import uuid
from lab import ROOT,RUNTIME
from lab_runtime import send,properties

OUT=ROOT/'experiments'

def status():
    p=RUNTIME/'status.json'
    data=json.loads(p.read_text())
    if time.time()-data['time']/1000>5:raise RuntimeError('Server status is stale; start the lab first')
    return data

async def match(a,b,ping_a=20,ping_b=20):
    request='exp-'+uuid.uuid4().hex
    for name in (a,b):
        if not re.fullmatch('[a-z0-9_-]{1,64}',name) or not (ROOT/'policies'/f'{name}.properties').is_file():
            raise ValueError('Unknown bot: '+name)
    if any(not math.isfinite(p) or not 0<=p<=500 for p in (ping_a,ping_b)):raise ValueError('RTTs must be 0–500 ms')
    parameters={name:properties(ROOT/'policies'/f'{name}.properties') for name in (a,b)}
    expected_build=hashlib.sha256((ROOT/'build/lab-agent.jar').read_bytes()).hexdigest()
    # Preserve spare capacity for the user's challenges: three matches / eight bots.
    while True:
        with open(RUNTIME/'manager/scheduler.lock','a+') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX)
            data=status();occupied={m[k] for m in data['matches'] for k in ('a','b')}
            free=[]
            for name in data['players']:
                if not name.startswith('LabSlot') or name in occupied:continue
                try:heartbeat=json.loads((RUNTIME/'bots'/name.removeprefix('LabSlot')/'heartbeat.json').read_text())
                except (FileNotFoundError,json.JSONDecodeError):continue
                if time.time()-heartbeat['time']/1000<=4 and heartbeat.get('agent_sha256')==expected_build and not heartbeat.get('enabled'):free.append(name)
            reservation_path=RUNTIME/'manager/reservations.json'
            reservations=json.loads(reservation_path.read_text()) if reservation_path.exists() else {}
            active={m.get('request') for m in data['matches']}
            reservations={key:created for key,created in reservations.items() if key not in active and time.time()-created<15}
            if len(free)-len(reservations)*2>=3:
                reservations[request]=time.time();reservation_path.write_text(json.dumps(reservations))
                send(f'lab match {a} {b} {ping_a} {ping_b} {request}');break
            reservation_path.write_text(json.dumps(reservations))
        await asyncio.sleep(1)
    print(f'Started {a} vs {b}, RTT {ping_a}/{ping_b}',flush=True)
    deadline=time.monotonic()+220;issues=set();checks=0;client_builds={}
    while time.monotonic()<deadline:
        for p in (RUNTIME/'results').glob('*.json'):
            result=json.loads(p.read_text())
            if result.get('request')==request:
                result['parameters']=parameters
                intervals=[];resets=0
                for side in ('a','b'):
                    path=RUNTIME/'bots'/result[side].removeprefix('LabSlot')/'events.jsonl'
                    events=[e for line in path.read_text().splitlines() if (e:=json.loads(line)).get('match')==result['id']]
                    clicks=[e for e in events if e['type']=='click']
                    no_click=parameters[result['policy_'+side]].get('click_mode')=='none'
                    if no_click:
                        if clicks:issues.add(f'{side}: non-clicking probe clicked')
                        if not any(e['type']=='reach_probe' for e in events):issues.add(f'{side}: missing native reach probes')
                    elif not clicks or any('monotonic_ns' not in e for e in clicks):issues.add(f'{side}: no strict click-clock evidence')
                    else:
                        gaps=[y['monotonic_ns']-x['monotonic_ns'] for x,y in zip(clicks,clicks[1:])]
                        intervals.extend(gaps)
                        if min(gaps,default=100_000_000)<100_000_000:issues.add(f'{side}: click cap violated')
                    target_tick=None;target_advanced_at=None
                    for event in events:
                        if event['type']=='engine_attack' and 'native_raycast_distance' in event:
                            if event['native_raycast_distance']>3.00001:issues.add(f'{side}: native attack exceeded three blocks')
                            if not all(event.get(k) for k in ('target_ready','target_loaded_identity','ray_selected_target')):issues.add(f'{side}: attack used an invalid native view')
                            if event.get('target_name')!=result['b' if side=='a' else 'a']:issues.add(f'{side}: attacked outside assigned duel')
                        if event.get('target_is_canonical') is False or event.get('raycast_stale') is True:issues.add(f'{side}: stale native entity')
                        if event['type']=='observation' and event['time']>=result['start']+250:
                            if event.get('target_region_loaded') is False:issues.add(f'{side}: opponent simulation region was not loaded')
                            if 'target_ticks' in event:
                                if target_tick!=event['target_ticks']:target_tick=event['target_ticks'];target_advanced_at=event['time']
                                elif target_advanced_at is not None and event['time']-target_advanced_at>300:issues.add(f'{side}: opponent stopped ticking')
                        if event['type'] in ('wtap','zest_plan','zest_select'):
                            resets+=1
                            if event['time']-event['cue_time']<150:issues.add(f'{side}: W-tap reacted before 150 ms')
                if result.get('ping_changes',0):issues.add('latency changed during fixed-condition evaluation')
                from analyze_views import analyze
                replication=analyze(result['id'])
                result['replication']={s:{k:v for k,v in replication[s].items() if k!='fits'} for s in ('a','b')}
                for side in ('a','b'):
                    r=replication[side]
                    if r['frozen_remote']:issues.add(f'{side}: frozen opponent replication')
                    if r['best']['samples']>=20 and r['best']['median_squared_residual']>.25:issues.add(f'{side}: large opponent replication residual')
                result['client_builds']=client_builds
                result['validation']={'passed':not issues and checks>0,'client_checks':checks,'issues':sorted(issues),
                                      'click_intervals_checked':len(intervals),'min_click_gap_ns':min(intervals,default=None),'post_hit_resets':resets}
                if not result['validation']['passed']:
                    raise RuntimeError(f'Invalid setup for {result["id"]}: {result["validation"]}')
                if result['reason']!='100_hits':
                    raise RuntimeError(f'{result["id"]} did not reach 100 hits: {result["reason"]}')
                result['combat_metrics']=combat_metrics(result)
                print(f'{result["id"]}: {a} {result["hits_a"]}–{result["hits_b"]} {b} ({result["reason"]})',flush=True)
                return result
        data=status()
        game=next((m for m in data['matches'] if m.get('request')==request),None)
        if game and time.time()>game['start']/1000+2:
            checks+=1
            for side,other in [('a','b'),('b','a')]:
                path=RUNTIME/'bots'/game[side].removeprefix('LabSlot')/'heartbeat.json'
                try:heartbeat=json.loads(path.read_text())
                except (FileNotFoundError,json.JSONDecodeError):continue
                if time.time()-heartbeat['time']/1000>4:issues.add(f'{side}: client stopped ticking')
                if heartbeat.get('match')==game['id']:
                    client_builds[side]=heartbeat.get('agent_sha256','unknown')
                    if client_builds[side]!='unknown' and client_builds[side]!=hashlib.sha256((ROOT/'build/lab-agent.jar').read_bytes()).hexdigest():issues.add(f'{side}: old client build; reload idle bots')
                    if game[other] not in heartbeat['visible_players']:issues.add(f'{side}: opponent missing from client world')
                    if not heartbeat['alive']:issues.add(f'{side}: client was dead')
                    if heartbeat.get('selected_target_dead'):issues.add(f'{side}: targeted a removed entity')
        await asyncio.sleep(.5)
    raise RuntimeError(f'Match timed out in manager: {request}')

def combat_metrics(result):
    """Measure accepted-hit streaks, distinct from merely winning a race to 100."""
    hits=[e for line in (RUNTIME/'replays'/f'{result["id"]}.jsonl').read_text().splitlines()
          if (e:=json.loads(line))['type']=='hit']
    metrics={side:{'longest_unanswered_streak':0,'longest_fresh_hurt_streak':0,'sprint_contacts':0,'ordinary_contacts':0,'fresh_hurt_contacts':0,'damage_upgrades':0} for side in ('a','b')}
    previous=None;streak=0;fresh_previous=None;fresh_streak=0
    for hit in hits:
        side='a' if hit['attacker']==result['a'] else 'b'
        streak=streak+1 if side==previous else 1;previous=side
        metrics[side]['longest_unanswered_streak']=max(metrics[side]['longest_unanswered_streak'],streak)
        if 'attacker_sprint' in hit:metrics[side]['sprint_contacts' if hit['attacker_sprint'] else 'ordinary_contacts']+=1
        if 'target_state' in hit:
            fresh=hit['target_state']['hurtResistantTime']==20
            metrics[side]['fresh_hurt_contacts' if fresh else 'damage_upgrades']+=1
            if fresh:
                fresh_streak=fresh_streak+1 if side==fresh_previous else 1;fresh_previous=side
                metrics[side]['longest_fresh_hurt_streak']=max(metrics[side]['longest_fresh_hurt_streak'],fresh_streak)
    all_probes={}
    for side,other in [('a','b'),('b','a')]:
        path=RUNTIME/'bots'/result[side].removeprefix('LabSlot')/'events.jsonl'
        probes=[e for line in path.read_text().splitlines() if (e:=json.loads(line)).get('match')==result['id'] and e['type']=='reach_probe']
        all_probes[side]=probes
        received=[h for h in hits if h['target']==result[side] and h.get('target_state',{}).get('hurtResistantTime')==20]
        if len(received)>=3:
            probes=[p for p in probes if p['time']>=received[2]['time'] and p['time']<=result['finish']]
            if probes:
                longest=0;since=None;previous=None
                for p in probes:
                    if p['in_range']:since=None
                    else:
                        if since is None or previous is not None and p['time']-previous>150:since=p['time']
                        longest=max(longest,p['time']-since)
                    previous=p['time']
                metrics[side]['native_range_after_third_received_hit']={'samples':len(probes),'in_range_samples':sum(p['in_range'] for p in probes),
                    'in_range_fraction':sum(p['in_range'] for p in probes)/len(probes),'longest_denial_ms':longest,'duration_ms':probes[-1]['time']-probes[0]['time'],
                    'criterion':'native client raycast, ignoring damage immunity; entry excluded through third fresh hurt'}
    from native_windows import overlap,sustained_combo
    if all(all_probes.values()):
        native=overlap(all_probes['a'],all_probes['b'],result['start'],result['finish'],include_windows=True)
        for side,other in [('a','b'),('b','a')]:
            contacts=[h['time']for h in hits if h['attacker']==result[side]and h.get('target_state',{}).get('hurtResistantTime')==20]
            returns=[h['time']for h in hits if h['attacker']==result[other]]
            metrics[side]['sustained_native_combo']=sustained_combo(native[side].pop('defender_denial_windows'),contacts,returns)
        metrics['native_overlap']=native
    return metrics

async def batch(fixtures,parallel=3):
    sem=asyncio.Semaphore(parallel)
    async def one(fixture):
        async with sem:return await match(*fixture)
    return await asyncio.gather(*(one(f) for f in fixtures))

def record(name,results,metadata=None):
    OUT.mkdir(exist_ok=True)
    environment={'engine':'official Minecraft 1.8.9','reaction_ms':150,'click_cap_cps':10,'input_clock_version':2,'parallel_matches':3,
                 'replication_version':2,'server_view_distance':8,'client_render_distance':6,
                 'agent_sha256':hashlib.sha256((ROOT/'build/lab-agent.jar').read_bytes()).hexdigest()}
    document={'name':name,'time':time.time(),'metadata':{**environment,**(metadata or {})},'results':results}
    (OUT/f'{name}.json').write_text(json.dumps(document,indent=2))
    stats=collections.defaultdict(lambda:{'wins':0,'losses':0,'draws':0,'hits_for':0,'hits_against':0})
    for r in results:
        if r['reason']!='100_hits' or not r.get('validation',{}).get('passed'):continue
        if any(r.get('parameters',{}).get(r['policy_'+s],{}).get('click_mode')=='none' for s in ('a','b')):continue
        for side,other in [('a','b'),('b','a')]:
            s=stats[r['policy_'+side]];s['hits_for']+=r['hits_'+side];s['hits_against']+=r['hits_'+other]
            s['wins' if r['winner']==r[side] else 'draws' if not r['winner'] else 'losses']+=1
    lines=['# Experiment match results','', 'These are real vanilla 1.8.9 matches through basic HeadlessMC. Small samples do not establish a general ranking. Win totals cover clicking opponents only; non-clicking probes are diagnostic fixtures. Opponents and latency conditions may differ.','',
           '| Bot | Wins | Losses | Draws | Hits for | Hits against |','|---|---:|---:|---:|---:|---:|']
    for bot,s in sorted(stats.items(),key=lambda item:(-item[1]['wins'],item[1]['losses'])):
        lines.append(f'| {bot} | {s["wins"]} | {s["losses"]} | {s["draws"]} | {s["hits_for"]} | {s["hits_against"]} |')
    lines+=['','| Match | A | B | RTT A/B (ms) | Score | Finish |','|---|---|---|---|---|---|']
    for r in results:lines.append(f'| {r["id"]} | {r["policy_a"]} | {r["policy_b"]} | {r["ping_a"]}/{r["ping_b"]} | {r["hits_a"]}–{r["hits_b"]} | {r["reason"]} |')
    if any(r.get('combat_metrics',{}).get('native_overlap')for r in results):
        lines+=['','Actual PvP hit margin is the primary objective; sustained combo duration is secondary. The conditional reach percentage below is diagnostic, measured from both native client POVs. Damage immunity does not remove reach.','',
                '| Match | Side | One-sided opportunity | Able to hit (s) | Sustained combo (s) | Longest one-sided window (s) | Paired coverage | Validation |',
                '|---|---|---:|---:|---:|---:|---:|---|']
        for r in results:
            n=r.get('combat_metrics',{}).get('native_overlap')
            if not n:continue
            for side in ('a','b'):
                v=n[side];ratio=v['one_sided_fraction_given_attacker_can_hit'];percentage=f'{ratio:.1%}'if ratio is not None else '—'
                lines.append(f'| {r["id"]} | {r["policy_"+side]} | {percentage} | {v["attacker_can_hit_ms"]/1000:.1f} | {r['combat_metrics'][side].get('sustained_native_combo',{}).get('duration_ms',0)/1000:.2f} | {v["longest_one_sided_window_ms"]/1000:.2f} | {n.get("paired_coverage_fraction",n["paired_time_ms"]/(r["finish"]-r["start"])):.1%} | {"passed"if r.get("validation",{}).get("passed")else "provisional"} |')
    (OUT/'RESULTS.md').write_text('\n'.join(lines)+'\n')
    return document

async def pilot():
    fixtures=[('hold-w','wtap',20,20),('wtap','hold-w',20,20),
              ('hold-w','hit-reset',20,20),('hold-w','predictive',20,20),
              ('hold-w','phase-spacing',20,20),('spacing','burst',20,120)]
    results=await batch(fixtures)
    record('pilot',results,{'seed':1849,'goal':100,'reaction_ms':150,'engine':'official Minecraft 1.8.9','headless':'basic only'})
    return results


async def latency_pilot():
    fixtures=[('wtap','hold-w',0,0),('hold-w','wtap',0,0)]
    for a,b in [('wtap','hit-reset'),('wtap','trade-wtap')]:
        fixtures.extend([(a,b,100,20),(b,a,20,100),(a,b,20,100),(b,a,100,20)])
    results=await batch(fixtures)
    record('latency-pilot',results,{'scope':'Corrected post-hit W-tap and trade-aware variant; unequal ping with role and latency assignments swapped',
                                  'rtts_ms':[[0,0],[100,20],[20,100]],'seed':1849})
    return results

async def integrity_load():
    results=await batch([('mpc-ensemble','hold-w-probe',20,100),('mpc-visible','hold-w-probe',100,20),('wtap','hold-w',20,100)])
    record('integrity-and-load',results,{'scope':'Three concurrent native matches; live target watchdog, staged vanilla tracker refresh and per-attack ray evidence'})
    return results


async def league(bots,rtts,repeats=1):
    if len(set(bots))!=len(bots) or len(bots)<2:raise ValueError('Choose at least two distinct bot IDs')
    fixtures=[]
    for a,b in itertools.combinations(bots,2):
        for pa,pb in rtts:
            cases={(a,b,pa,pb),(b,a,pb,pa),(a,b,pb,pa),(b,a,pa,pb)}
            fixtures.extend(sorted(cases)*repeats)
    random.Random(1849).shuffle(fixtures)
    print(f'League: {len(fixtures)} games, all pairings with starting sides and ping assignments swapped',flush=True)
    results=await batch(fixtures)
    record('league',results,{'bots':bots,'rtts':rtts,'repeats':repeats,'seed':1849})
    return results


def save_policy(identifier,params):
    text='\n'.join(f'{k}={v}' for k,v in params.items())+'\n'
    (ROOT/'policies'/f'{identifier}.properties').write_text(text)

async def evolve(population=4,generations=1):
    rng=random.Random(1849)
    parent=dict(kind='predictive',click_mode='fixed',gap=2.9,margin=.1,horizon_ms=80)
    all_results=[];history=[]
    for generation in range(generations):
        candidates={}
        for i in range(population):
            identifier=f'evolved-g{generation+1}-{i+1:02d}'
            p=dict(parent)
            if i:
                p['gap']=round(min(3.35,max(2.3,float(parent['gap'])+rng.gauss(0,.22))),3)
                p['margin']=round(min(.25,max(.02,float(parent['margin'])+rng.gauss(0,.035))),3)
                p['horizon_ms']=round(min(250,max(0,float(parent['horizon_ms'])+rng.gauss(0,45))))
            p['description']=f'Generation {generation+1} candidate {i+1}, fixed 10 CPS; parameters saved'
            save_policy(identifier,p);candidates[identifier]=p
        fixtures=[]
        for identifier in candidates:fixtures += [(identifier,'wtap',20,20),('wtap',identifier,20,20)]
        results=await batch(fixtures);all_results+=results
        scores={}
        for identifier in candidates:
            games=[r for r in results if identifier in [r['policy_a'],r['policy_b']]]
            value=0
            for r in games:
                side='a' if r['policy_a']==identifier else 'b';other='b' if side=='a' else 'a'
                value+=(1 if r['winner']==r[side] else .5 if not r['winner'] else 0)+(r['hits_'+side]-r['hits_'+other])*.001
            scores[identifier]=value
        winner=max(scores,key=scores.get);parent=candidates[winner]
        history.append({'generation':generation+1,'scores':scores,'selected':winner,'parameters':parent})
        print('Selected '+winner+' '+json.dumps(parent),flush=True)
    champion=dict(parent);champion['description']='Selected spacing candidate from small search; fixed 10 CPS; may lose to baselines'
    save_policy('evolved-champion',champion)
    # Fresh latency conditions and a different opponent, outside the selection fixtures.
    holdout=await batch([('evolved-champion','wtap',20,120),('wtap','evolved-champion',120,20),
                         ('evolved-champion','wtap',120,20),('wtap','evolved-champion',20,120),
                         ('evolved-champion','hold-w',20,20),('hold-w','evolved-champion',20,20)])
    all_results+=holdout
    record('evolution',all_results,{'seed':1849,'population':population,'generations':generations,'history':history,'holdout_ids':[r['id'] for r in holdout],
                                    'scope':'small evolutionary best-response pilot against wtap; no global optimality claim'})
    pilot_file=OUT/'pilot.json'
    combined=(json.loads(pilot_file.read_text())['results'] if pilot_file.exists() else [])+all_results
    record('combined',combined)
    return all_results


def lock_fitness(result,side='a'):
    """Primary conditional reach ratio; retain a reasonable rate of fresh hits."""
    measured=result['combat_metrics']['native_overlap'];v=measured[side]
    ratio=v['one_sided_fraction_given_attacker_can_hit'] or 0
    seconds=(result['finish']-result['start'])/1000
    return ratio-.15*max(0,seconds/65-1)+.02*min(1,v['longest_one_sided_window_ms']/5000)


async def evolve_locks(population=4,generations=1):
    """Small native-engine search; the deployed bot still sees only its own delayed POV."""
    rng=random.Random(1849);parent=properties(ROOT/'policies/mpc-replay.properties');results=[];history=[]
    bounds={'opportunity_penalty':(80,600,110),'penetration_penalty':(0,500,100),
            'anchor_velocity_gain':(0,.9,.2),'goal_source_shift_ms':(-40,40,15),
            'anchor_fit_weight':(0,2,.5)}
    for generation in range(generations):
        candidates={}
        for index in range(population):
            name=f'lock-search-g{generation+1}-{index+1:02d}';p=dict(parent)
            if generation==0 and index==1:p=properties(ROOT/'policies/mpc-edge-tight.properties')
            elif index:
                for key,(low,high,sigma)in bounds.items():p[key]=round(min(high,max(low,float(parent.get(key,0))+rng.gauss(0,sigma))),3)
                if generation==0:p['anchor_fit_weight']=min(2,index-1)
            p['description']='Combo-window search candidate; fixed normal clicks, delayed visible observations'
            save_policy(name,p);candidates[name]=p
        fixtures=[]
        for name in candidates:fixtures.extend([(name,'hold-w-probe',20,100),('hold-w-probe',name,100,20)])
        evaluated=await batch(fixtures);results.extend(evaluated)
        scores={name:sum(lock_fitness(r,'a'if r['policy_a']==name else'b')for r in evaluated if name in(r['policy_a'],r['policy_b']))/2 for name in candidates}
        selected=max(scores,key=scores.get);parent=candidates[selected]
        history.append({'generation':generation+1,'scores':scores,'selected':selected,'parameters':parent})
        # Save each completed generation so an interruption never loses it.
        record('lock-search-progress',results,{'seed':1849,'history':history,'selection_rtt_ms':[20,100],'status':'exploratory; holdouts pending'})
        print('Selected '+selected+' '+str(scores[selected]),flush=True)
    champion={**parent,'description':'Selected combo-window candidate; exploratory selection, see fresh holdout results'}
    save_policy('lock-candidate',champion)
    holdout=await batch([('lock-candidate','hold-w-probe',20,100),('hold-w-probe','lock-candidate',100,20),
                         ('lock-candidate','hold-w-probe',0,0),('lock-candidate','hold-w-probe',100,20)])
    results.extend(holdout)
    record('lock-search',results,{'seed':1849,'population':population,'generations':generations,'history':history,'selection_rtt_ms':[20,100],
          'holdout_ids':[r['id']for r in holdout],'fitness':'conditional native one-sided ratio; penalty for round duration above65s, small continuous-window bonus; total attack opportunity reported separately',
          'scope':'Small exploratory native search, two selection games per candidate with starting sides swapped; fresh repeats and ping holdouts are reported separately'})
    return results


def pvp_fitness(result,side):
    """Lexicographic: actual hit margin first, sustained lock duration second."""
    other='b'if side=='a'else'a'
    return (result['hits_'+side]-result['hits_'+other],result['combat_metrics'][side]['sustained_native_combo']['duration_ms'])


async def evolve_pvp(population=4,generations=1):
    rng=random.Random(1849);parent_path=ROOT/'policies/duel-search-parent.properties'
    parent=properties(parent_path if parent_path.exists() else ROOT/'policies/mpc-score.properties');results=[];history=[]
    baseline=await batch([('wtap','hold-w',20,100),('hold-w','wtap',100,20)])
    results.extend(baseline)
    bounds={'received_hit_penalty':(600,1600,250),'opportunity_penalty':(0,50,10),
            'anchor_velocity_gain':(0,.9,.15),'goal_source_shift_ms':(-30,30,10),
            'own_fit_weight':(0,6,1),'model_horizon_ticks':(40,60,5)}
    for generation in range(generations):
        candidates={}
        for index in range(population):
            name=f'pvp-search-g{generation+1}-{index+1:02d}';p=dict(parent)
            if index:
                for key,(low,high,sigma)in bounds.items():p[key]=round(min(high,max(low,float(parent.get(key,0))+rng.gauss(0,sigma))),3)
                if index==1:p['opportunity_penalty']=0
            p['contact_edge_penalty']=0
            p['model_horizon_ticks']=int(round(float(p['model_horizon_ticks'])))
            p['description']='Duel search candidate; ordinary keys and clicks, model of clicking hold-W'
            save_policy(name,p);candidates[name]=p
        fixtures=[]
        for name in candidates:fixtures.extend([(name,'hold-w',20,100),('hold-w',name,100,20)])
        evaluated=await batch(fixtures);results.extend(evaluated)
        scores={}
        for name in candidates:
            values=[pvp_fitness(r,'a'if r['policy_a']==name else'b')for r in evaluated if name in(r['policy_a'],r['policy_b'])]
            scores[name]=tuple(sum(x[i]for x in values)/len(values)for i in range(2))
        selected=max(scores,key=scores.get);parent=candidates[selected]
        history.append({'generation':generation+1,'scores_hit_margin_then_lock_ms':scores,'selected':selected,'parameters':parent})
        record('pvp-search-progress',results,{'seed':1849,'history':history,'status':'paired selection; fresh tests pending','objective_priority':['hit margin against clicking hold-W','sustained native combo duration']})
        print('Selected '+selected+' '+str(scores[selected]),flush=True)
    save_policy('pvp-candidate',{**parent,'description':'Selected duel candidate; check fresh holdout scores before treating it as a champion'})
    holdout=await batch([('pvp-candidate','hold-w',0,0),('hold-w','pvp-candidate',0,0),
                         ('pvp-candidate','hold-w',20,100),('pvp-candidate','hold-w',100,20)])
    results.extend(holdout)
    record('pvp-search',results,{'seed':1849,'population':population,'generations':generations,'history':history,'holdout_ids':[r['id']for r in holdout],
          'objective_priority':['actual hit margin against clicking hold-W','sustained native combo duration'],'baseline_ids':[r['id']for r in baseline],
          'scope':'Small paired native search, with a W-tap control and fresh ping/side tests; conditional reach percentage is diagnostic only'})
    return results

def main():
    parser=argparse.ArgumentParser();parser.add_argument('command',choices=['pilot','latency-pilot','integrity-load','evolve','evolve-locks','evolve-pvp','league']);parser.add_argument('--population',type=int,default=4);parser.add_argument('--generations',type=int,default=1)
    parser.add_argument('--bots',nargs='+',default=['hold-w','wtap','hit-reset','predictive'])
    parser.add_argument('--rtts',nargs='+',default=['20,20']);parser.add_argument('--repeats',type=int,default=1)
    args=parser.parse_args()
    if args.population<2 or args.generations<1:parser.error('population >= 2 and generations >= 1 required')
    if args.repeats<1:parser.error('repeats >= 1 required')
    rtts=[]
    for value in args.rtts:
        try:
            pair=tuple(map(float,value.split(',')))
            if len(pair)!=2 or any(not math.isfinite(p) or not 0<=p<=500 for p in pair):raise ValueError()
            rtts.append(pair)
        except ValueError:parser.error('RTTs must be pairs of 0–500 ms, e.g. --rtts 20,20 20,120')
    asyncio.run(pilot() if args.command=='pilot' else latency_pilot() if args.command=='latency-pilot' else integrity_load() if args.command=='integrity-load' else evolve(args.population,args.generations) if args.command=='evolve' else evolve_locks(args.population,args.generations) if args.command=='evolve-locks' else evolve_pvp(args.population,args.generations) if args.command=='evolve-pvp' else league(args.bots,rtts,args.repeats))

if __name__=='__main__':main()
