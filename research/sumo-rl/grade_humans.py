"""Register a balanced live human benchmark, then score the recorded rounds in chronological order."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import random
from run import ROOT

ARMS = ('research-wtap20','sumo-rl-pressure')
DEVELOPMENT_PLAYERS = {'nomophobia'}

def check_roster(players):
    if any(n.strip().casefold() in DEVELOPMENT_PLAYERS for n in players):
        raise ValueError('Nomophobia is registered as development only')
    if not players or any(not n.strip() for n in players):
        raise ValueError('At least one named benchmark player is required')

def config_hash(name):
    return hashlib.sha256((ROOT/'policies'/(name+'.properties')).read_bytes()).hexdigest()

def bridge_hash():
    manager=ROOT/'runtime/manager.json'
    if not manager.exists():
        return hashlib.sha256((ROOT/'build/practice-client/practice-bridge.jar').read_bytes()).hexdigest()
    hashes=set()
    for i in range(json.loads(manager.read_text())['bot_slots']):
        directory=ROOT/'runtime/bots'/str(i)
        beat=json.loads((directory/'heartbeat.json').read_text())
        actual=hashlib.sha256((directory/'mods/practice-bridge.jar').read_bytes()).hexdigest()
        if beat.get('bridge_sha256')!=actual:raise ValueError('Running Forge client differs from its slot artifact')
        hashes.add(actual)
    if len(hashes)!=1:raise ValueError('Benchmark slots have different client builds')
    return hashes.pop()

def register(players, pairs, seed, rtt, challenger=ARMS[1], mode="sumo", aim_input="mouse"):
    check_roster(players)
    if aim_input not in ("mouse","ideal"):raise ValueError("Unknown mouse input mode")
    if challenger==ARMS[0]:raise ValueError("Challenger must differ from W-tap")
    if mode not in ("boxing","sumo"):raise ValueError("Expected sumo or boxing")
    arms=(ARMS[0],challenger)
    if pairs<1 or rtt<0:raise ValueError('Positive pair count and nonnegative RTT required')
    if len({s.casefold() for s in players})!=len(players):raise ValueError('Duplicate player')
    rng=random.Random(seed);schedule={}
    for player in players:
        # Balance order within players; alternate the opening arm between blocks.
        first=rng.randrange(2)
        schedule[player]=[arms[(first+rep+idx)%2] for rep in range(pairs) for idx in (0,1)]
    return dict(bridge_sha256=bridge_hash(),frozen_at_ms=int(datetime.now(timezone.utc).timestamp()*1000),
                sumo_arena={'diameter':13,'start_gap':8,'shape':'mmc_recorded_13_v1'} if mode=='sumo' else None,
                profile='mmc',mode=mode,aim_input=aim_input,rtt=rtt,pairs=pairs,seed=seed,arms=arms,duration_limit_ms=120000 if mode=='sumo' else 180000,
                policy_sha256={k:config_hash(k) for k in arms},schedule=schedule,
                duel_commands={k:f'/duel {k} {mode} {rtt} {rtt} preset=mmc reach=3 cps=20 tapdelay=0 aimdelay=0 aimnoise=0 aimrate=0 aiminput={aim_input}' for k in arms},
                competitive_parameters={k:dict(line.split('=',1) for line in (ROOT/'policies'/(k+'.properties')).read_text().splitlines()
                    if '=' in line and line.split('=',1)[0] in ('kind','sumo_jump_mask','duel_network','duel_network_landmark','click_mode','pause_ms')) for k in arms})

def grade(plan, games):
    check_roster(plan['schedule'])
    result={};registered=plan.get('arms',ARMS);mode=plan.get('mode','sumo')
    endings=('100_hits','timeout_draw') if mode=='boxing' else ('ring_out','double_ring_out','timeout_draw')
    for player,arms in plan['schedule'].items():
        attempts=[]
        for g in games:
            if g['start']<plan['frozen_at_ms'] or g['mode']!=mode:continue
            human=next((s for s in 'ab' if g['policy_'+s]=='human' and g[s].casefold()==player.casefold()),None)
            if human is None:continue
            bot='b' if human=='a' else 'a'
            if g['policy_'+bot] in registered:attempts.append((g,bot))
        attempts.sort(key=lambda pair:(pair[0]['start'],pair[0]['id']))
        count=Counter();ids=[]
        for (g,b),expected in zip(attempts,arms):
            pars=g['parameters_'+b]
            if g['policy_'+b]!=expected:raise ValueError(f"{player}: {g['id']} played out of the registered order")
            regulation=(g.get('goal')==100 and not g.get('scenario')) if mode=='boxing' else (
                all(g['sumo_arena'].get(k)==v for k,v in plan.get('sumo_arena',{'diameter':10,'start_gap':6}).items()))
            if not (g['practice_preset']==plan['profile'] and g['combat_profile']['knockback_model']=='mmc_pre_attack_sprint_v2'
                and g['initial_ping_a']==g['initial_ping_b']==plan['rtt'] and not g.get('ping_changes',0) and g['click_cps_'+b]==20
                and regulation and pars.get('aim_input','mouse' if pars.get('league_stage') else 'ideal')==plan.get('aim_input','ideal') and
                g['attack_reach_a']==g['attack_reach_b']==3 and g['reaction_ms_'+b]==0 and
                all(float(pars[k])==0 for k in ('aim_delay_ms','aim_noise_deg','aim_turn_rate_dps'))
                and all(str(pars.get(k))==v for k,v in plan.get('competitive_parameters',{}).get(expected,{}).items()) and
                ('competitive_parameters' in plan or (pars['kind']==('wtap' if expected==ARMS[0] else 'hold') and
                    int(pars.get('sumo_jump_mask',0))==(0 if expected==ARMS[0] else 5168))) and
                g['client_'+b]['backend']=='forge' and g['client_'+b]['bridge_sha256']==plan['bridge_sha256'] and
                g['duration_limit_ms']==plan.get('duration_limit_ms',120000)):
                raise ValueError(f"{player}: settings differ in {g['id']}")
            outcome='win' if g['winner']==g[b] else 'loss' if g['winner'] else 'draw'
            if g['reason'] not in endings:outcome='interrupted'
            ids.append([g['id'],expected,outcome,g['reason']]);count[expected,outcome]+=1
        result[player]=dict(complete=len(ids)==len(arms),competitive=sum(row[2]!='interrupted' for row in ids),next_arm=arms[len(ids)] if len(ids)<len(arms) else None,
            rounds=ids,**{k:{v:count[k,v] for v in ('win','loss','draw','interrupted')} for k in registered})
    return result

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);subs=p.add_subparsers(dest='action',required=True)
    new=subs.add_parser('register');new.add_argument('plan',type=Path);new.add_argument('players',nargs='+')
    new.add_argument('--pairs',type=int,default=10);new.add_argument('--seed',type=int,required=True)
    new.add_argument('--rtt',type=int,default=0)
    new.add_argument('--aim-input',choices=('mouse','ideal'),default='mouse')
    new.add_argument('--challenger',default=ARMS[1])
    new.add_argument('--mode',choices=('sumo','boxing'),default='sumo')
    run=subs.add_parser('score');run.add_argument('plan',type=Path)
    a=p.parse_args()
    if a.action=='register':
        plan=register(a.players,a.pairs,a.seed,a.rtt,a.challenger,a.mode,a.aim_input)
        with a.plan.open('x') as f:json.dump(plan,f,indent=2)
        print(json.dumps(plan,indent=2))
    else:
        plan=json.loads(a.plan.read_text())
        if any(config_hash(k)!=v for k,v in plan['policy_sha256'].items()):p.error('Policy file has changed since registration')
        result=grade(plan,[json.loads(f.read_text()) for f in (ROOT/'runtime/results').glob('*.json')])
        print(json.dumps(result,indent=2))
