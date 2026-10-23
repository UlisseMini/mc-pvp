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
    # Preserve a spare slot for the user's challenges. Two matches fit with six bots.
    while True:
        with open(RUNTIME/'manager/scheduler.lock','a+') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX)
            data=status();occupied={m[k] for m in data['matches'] for k in ('a','b')}
            free=[p for p in data['players'] if p.startswith('LabSlot') and p not in occupied]
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
    deadline=time.monotonic()+220;issues=set();checks=0
    while time.monotonic()<deadline:
        for p in (RUNTIME/'results').glob('*.json'):
            result=json.loads(p.read_text())
            if result.get('request')==request:
                result['parameters']=parameters
                result['validation']={'passed':not issues and checks>0,'client_checks':checks,'issues':sorted(issues)}
                if not result['validation']['passed']:
                    raise RuntimeError(f'Invalid setup for {result["id"]}: {result["validation"]}')
                if result['reason']!='100_hits':
                    raise RuntimeError(f'{result["id"]} did not reach 100 hits: {result["reason"]}')
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
                    if game[other] not in heartbeat['visible_players']:issues.add(f'{side}: opponent missing from client world')
                    if not heartbeat['alive']:issues.add(f'{side}: client was dead')
                    if heartbeat.get('selected_target_dead'):issues.add(f'{side}: targeted a removed entity')
        await asyncio.sleep(.5)
    raise RuntimeError(f'Match timed out in manager: {request}')

async def batch(fixtures,parallel=2):
    sem=asyncio.Semaphore(parallel)
    async def one(fixture):
        async with sem:return await match(*fixture)
    return await asyncio.gather(*(one(f) for f in fixtures))

def record(name,results,metadata=None):
    OUT.mkdir(exist_ok=True)
    environment={'engine':'official Minecraft 1.8.9','reaction_ms':150,'click_cap_cps':10,'parallel_matches':2,
                 'agent_sha256':hashlib.sha256((ROOT/'build/lab-agent.jar').read_bytes()).hexdigest()}
    document={'name':name,'time':time.time(),'metadata':{**environment,**(metadata or {})},'results':results}
    (OUT/f'{name}.json').write_text(json.dumps(document,indent=2))
    stats=collections.defaultdict(lambda:{'wins':0,'losses':0,'draws':0,'hits_for':0,'hits_against':0})
    for r in results:
        if r['reason']!='100_hits' or not r.get('validation',{}).get('passed'):continue
        for side,other in [('a','b'),('b','a')]:
            s=stats[r['policy_'+side]];s['hits_for']+=r['hits_'+side];s['hits_against']+=r['hits_'+other]
            s['wins' if r['winner']==r[side] else 'draws' if not r['winner'] else 'losses']+=1
    lines=['# Experiment match results','', 'These are real vanilla 1.8.9 matches through basic HeadlessMC. Small samples do not establish a general ranking. Win totals summarize these fixtures; opponents and latency conditions may differ.','',
           '| Bot | Wins | Losses | Draws | Hits for | Hits against |','|---|---:|---:|---:|---:|---:|']
    for bot,s in sorted(stats.items(),key=lambda item:(-item[1]['wins'],item[1]['losses'])):
        lines.append(f'| {bot} | {s["wins"]} | {s["losses"]} | {s["draws"]} | {s["hits_for"]} | {s["hits_against"]} |')
    lines+=['','| Match | A | B | RTT A/B (ms) | Score | Finish |','|---|---|---|---|---|---|']
    for r in results:lines.append(f'| {r["id"]} | {r["policy_a"]} | {r["policy_b"]} | {r["ping_a"]}/{r["ping_b"]} | {r["hits_a"]}–{r["hits_b"]} | {r["reason"]} |')
    (OUT/'RESULTS.md').write_text('\n'.join(lines)+'\n')
    return document

async def pilot():
    fixtures=[('hold-w','wtap',20,20),('wtap','hold-w',20,20),
              ('hold-w','hit-reset',20,20),('hold-w','predictive',20,20),
              ('hold-w','phase-spacing',20,20),('spacing','burst',20,120)]
    results=await batch(fixtures)
    record('pilot',results,{'seed':1849,'goal':100,'reaction_ms':150,'engine':'official Minecraft 1.8.9','headless':'basic only'})
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
    champion=dict(parent);champion['description']='Small-pilot evolved best response to wtap; fixed 10 CPS; limited evidence'
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

def main():
    parser=argparse.ArgumentParser();parser.add_argument('command',choices=['pilot','evolve','league']);parser.add_argument('--population',type=int,default=4);parser.add_argument('--generations',type=int,default=1)
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
    asyncio.run(pilot() if args.command=='pilot' else evolve(args.population,args.generations) if args.command=='evolve' else league(args.bots,rtts,args.repeats))

if __name__=='__main__':main()
