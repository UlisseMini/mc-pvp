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

def register(players, pairs, seed, rtt, challenger=ARMS[1]):
    check_roster(players)
    if challenger==ARMS[0]:raise ValueError("Challenger must differ from W-tap")
    arms=(ARMS[0],challenger)
    if pairs<1 or rtt<0:raise ValueError('Positive pair count and nonnegative RTT required')
    if len({s.casefold() for s in players})!=len(players):raise ValueError('Duplicate player')
    rng=random.Random(seed);schedule={}
    for player in players:
        # Balance order within players; alternate the opening arm between blocks.
        first=rng.randrange(2)
        schedule[player]=[arms[(first+rep+idx)%2] for rep in range(pairs) for idx in (0,1)]
    bridge = ROOT/'build/practice-client/practice-bridge.jar'
    return dict(bridge_sha256=hashlib.sha256(bridge.read_bytes()).hexdigest(),frozen_at_ms=int(datetime.now(timezone.utc).timestamp()*1000),
                profile='mmc',rtt=rtt,pairs=pairs,seed=seed,arms=arms,duration_limit_ms=120000,
                policy_sha256={k:config_hash(k) for k in arms},schedule=schedule,
                competitive_parameters={k:dict(line.split('=',1) for line in (ROOT/'policies'/(k+'.properties')).read_text().splitlines()
                    if '=' in line and line.split('=',1)[0] in ('kind','sumo_jump_mask','duel_network','duel_network_landmark','click_mode','pause_ms')) for k in arms})

def grade(plan, games):
    check_roster(plan['schedule'])
    result={};registered=plan.get('arms',ARMS)
    for player,arms in plan['schedule'].items():
        attempts=[]
        for g in games:
            if g['start']<plan['frozen_at_ms'] or g['mode']!='sumo':continue
            human=next((s for s in 'ab' if g['policy_'+s]=='human' and g[s].casefold()==player.casefold()),None)
            if human is None:continue
            bot='b' if human=='a' else 'a'
            if g['policy_'+bot] in registered:attempts.append((g,bot))
        attempts.sort(key=lambda pair:(pair[0]['start'],pair[0]['id']))
        count=Counter();ids=[]
        for (g,b),expected in zip(attempts,arms):
            pars=g['parameters_'+b]
            if g['policy_'+b]!=expected:raise ValueError(f"{player}: {g['id']} played out of the registered order")
            if not (g['practice_preset']==plan['profile'] and g['combat_profile']['knockback_model']=='mmc_pre_attack_sprint_v2'
                and g['initial_ping_a']==g['initial_ping_b']==plan['rtt'] and g['click_cps_'+b]==20
                and g['sumo_arena']['diameter']==10 and g['sumo_arena']['start_gap']==6 and
                g['attack_reach_a']==g['attack_reach_b']==3 and g['reaction_ms_'+b]==0 and
                all(float(pars[k])==0 for k in ('aim_delay_ms','aim_noise_deg','aim_turn_rate_dps'))
                and all(str(pars.get(k))==v for k,v in plan.get('competitive_parameters',{}).get(expected,{}).items()) and
                ('competitive_parameters' in plan or (pars['kind']==('wtap' if expected==ARMS[0] else 'hold') and
                    int(pars.get('sumo_jump_mask',0))==(0 if expected==ARMS[0] else 5168))) and
                g['client_'+b]['backend']=='forge' and g['client_'+b]['bridge_sha256']==plan['bridge_sha256'] and
                g['duration_limit_ms']==plan.get('duration_limit_ms',120000)):
                raise ValueError(f"{player}: settings differ in {g['id']}")
            outcome='win' if g['winner']==g[b] else 'loss' if g['winner'] else 'draw'
            if g['reason'] not in ('ring_out','double_ring_out','timeout_draw'):outcome='interrupted'
            ids.append([g['id'],expected,outcome,g['reason']]);count[expected,outcome]+=1
        result[player]=dict(complete=len(ids)==len(arms),competitive=sum(row[2]!='interrupted' for row in ids),next_arm=arms[len(ids)] if len(ids)<len(arms) else None,
            rounds=ids,**{k:{v:count[k,v] for v in ('win','loss','draw','interrupted')} for k in registered})
    return result

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);subs=p.add_subparsers(dest='action',required=True)
    new=subs.add_parser('register');new.add_argument('plan',type=Path);new.add_argument('players',nargs='+')
    new.add_argument('--pairs',type=int,default=10);new.add_argument('--seed',type=int,required=True)
    new.add_argument('--rtt',type=int,default=0)
    new.add_argument('--challenger',default=ARMS[1])
    run=subs.add_parser('score');run.add_argument('plan',type=Path)
    a=p.parse_args()
    if a.action=='register':
        plan=register(a.players,a.pairs,a.seed,a.rtt,a.challenger)
        with a.plan.open('x') as f:json.dump(plan,f,indent=2)
        print(json.dumps(plan,indent=2))
    else:
        plan=json.loads(a.plan.read_text())
        if any(config_hash(k)!=v for k,v in plan['policy_sha256'].items()):p.error('Policy file has changed since registration')
        result=grade(plan,[json.loads(f.read_text()) for f in (ROOT/'runtime/results').glob('*.json')])
        print(json.dumps(result,indent=2))
