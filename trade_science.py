#!/usr/bin/env python3
"""Predeclared native W-tap/hold-W exchange experiments; no parameter search."""
import argparse
import asyncio
import collections
import hashlib
import json
import time
from pathlib import Path
from lab import ROOT, BUILD
from lab_runtime import properties
from experiments import match, save_policy
from parity import archive
from science import OUT, server_events, client_events


def analyze(game):
    raw=server_events(game['id'])
    attacks=[e for e in raw if e['type']=='science_server_attack']
    accepted=[e for e in attacks if e['score_before']!=e['score_after']]
    fresh=[e for e in accepted if e['target_before']['hurtResistantTime']<=10]
    result={'match':game['id'],'scope':'Native server acceptance and fresh-damage classification. Closely spaced opposing arrivals are reported, not treated as a sustained combo.',
            'trade_return_window_ms':200,'sides':{},'fresh_events':[]}
    for e in fresh:
        result['fresh_events'].append({'t':e['time']-game['start'],'tick':e['server_tick'],
            'side':'a' if e['attacker']==game['a'] else 'b','sprint':e['attacker_before']['sprint'],
            'attacker_y':e['attacker_before']['posY'],'target_y':e['target_before']['posY']})
    for side,other in (('a','b'),('b','a')):
        player=game[side];ours=[e for e in fresh if e['attacker']==player]
        ups=[e for e in accepted if e['attacker']==player and e['target_before']['hurtResistantTime']>10]
        c=collections.Counter(accepted=len(ours)+len(ups),fresh=len(ours),upgrades=len(ups))
        c['fresh_sprint']=sum(e['attacker_before']['sprint'] for e in ours)
        clients=client_events(game,side)
        doubles=[];returns=[]
        for i,e in enumerate(fresh):
            if e['attacker']!=player:continue
            if i and fresh[i-1]['attacker']!=player and e['time']-fresh[i-1]['time']<=200 and i+1<len(fresh):
                following=fresh[i+1]
                returns.append({'first_incoming_ms':fresh[i-1]['time']-game['start'],
                    'return_ms':e['time']-game['start'],'return_lag_ms':e['time']-fresh[i-1]['time'],
                    'next_fresh_side':side if following['attacker']==player else other,
                    'next_fresh_ms':following['time']-game['start'],
                    'double':following['attacker']==player})
            if i and fresh[i-1]['attacker']==player:
                first=fresh[i-1];nxt=next((x for x in fresh[i+1:] if x['attacker']!=player),None)
                between=[x for x in accepted if first['time']<x['time']<e['time'] and x['attacker']!=player]
                doubles.append({'first_ms':first['time']-game['start'],'second_ms':e['time']-game['start'],
                    'fresh_gap_ms':e['time']-first['time'],'first_sprint':first['attacker_before']['sprint'],
                    'second_sprint':e['attacker_before']['sprint'],'intervening_damage_returns':len(between),
                    'next_return_ms':None if nxt is None else nxt['time']-e['time'],
                    'observed_after_second_ms':game['finish']-e['time'],
                    'second_height_advantage':e['target_before']['posY']-e['attacker_before']['posY']})
        c['fresh_double_transitions']=len(doubles)
        c['fresh_double_no_damage_return']=sum(d['intervening_damage_returns']==0 for d in doubles)
        c['fresh_double_return_delayed_150ms']=sum(
            d['next_return_ms']>=150 if d['next_return_ms'] is not None else d['observed_after_second_ms']>=150
            for d in doubles)
        c['return_trade_opportunities']=len(returns);c['return_trade_double_successes']=sum(d['double'] for d in returns)
        c['visible_wtaps']=sum(e['type']=='wtap' for e in clients)
        result['sides'][side]={'counts':dict(c),'doubles':doubles,'trade_returns':returns}
    return result


async def run(name,variants=None,pairs=None,repeats=1,duration=None,opponent='hold-w',opponents=None):
    path=OUT/(name+'.json')
    if path.exists():raise ValueError('Use a fresh evidence name')
    if variants is None:variants=['wtap']
    if pairs is None:pairs=[(20,20)]
    if opponents is None:opponents=[opponent]
    names={}
    for policy in [*variants,*opponents]:
        # Reserved research prefix: do not overwrite the public trade-wtap bot.
        name2='trace-exchange-'+policy
        save_policy(name2,{**properties(ROOT/'policies'/f'{policy}.properties'),'science_trace':1,'science_network_trace':1})
        names[policy]=name2
    conditions=[(v,o) for v in variants for o in opponents]
    fixtures=[(names[v],names[o],pa,pb,duration) for pa,pb in pairs for rep in range(repeats)
              for v,o in (conditions if rep%2==0 else list(reversed(conditions)))]
    digest=lambda:hashlib.sha256((BUILD/'lab-agent.jar').read_bytes()).hexdigest()
    plan={'created_unix':time.time(),'stage':'preregistered','agent_sha256':digest(),
          'fixtures':fixtures,'policies':{v:properties(ROOT/'policies'/f'{v}.properties') for v in names.values()},
          'definition':'Primary: consecutive fresh knockback-producing sword contacts, separate from within-immunity damage upgrades; count prompt opposing returns separately.',
          'hypotheses':['Identical policy and nominal RTT do not specify client click/tick phase and packet task-drain order.',
                        'A preceding critical damage upgrade can consume server sprint before the next fresh hit.',
                        'A fresh double requires opponent failure to deliver a fresh eligible contact before the second hit; classify immunity, range and miss-cooldown timing.',
                        'An initial click-phase intervention may change opening launch/landing timing; measure actual native phases, since independent OS clocks are not identical counterfactual schedules.'],
          'results':[]}
    def checkpoint():
        tmp=path.with_suffix('.tmp');tmp.write_text(json.dumps(plan,indent=2)+'\n');tmp.replace(path)
    checkpoint()
    for a,b,pa,pb,ms in fixtures:
        assert digest()==plan['agent_sha256']
        for p in (a,b):assert properties(ROOT/'policies'/f'{p}.properties')==plan['policies'][p]
        game=await match(a,b,pa,pb,duration_ms=ms);archive(game)
        detail=analyze(game);plan['results'].append({'game':game,'exchanges':detail});plan['stage']='running';checkpoint()
        print(json.dumps({'match':game['id'],'counts':{s:detail['sides'][s]['counts'] for s in ('a','b')}}),flush=True)
    plan['stage']='complete';checkpoint()


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('name');p.add_argument('--variants',nargs='+',default=['wtap']);p.add_argument('--pings',nargs='+',default=['20/20']);p.add_argument('--repeats',type=int,default=1);p.add_argument('--duration',type=int);p.add_argument('--opponent',default='hold-w');p.add_argument('--opponents',nargs='+')
    a=p.parse_args();pairs=[tuple(map(int,p.split('/'))) for p in a.pings]
    asyncio.run(run(a.name,a.variants,pairs,a.repeats,a.duration,a.opponent,a.opponents))
