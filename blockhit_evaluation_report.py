#!/usr/bin/env python3
"""Summarize frozen real-engine trials without choosing a winner or hiding losses."""
import argparse
import collections
import json
from pathlib import Path
import statistics


def report(directories, aliases=None):
    aliases = aliases or {}
    groups = collections.defaultdict(list)
    configurations = {}
    unfinished = []
    for directory in directories:
        data = json.loads((directory/'report.json').read_text())
        if 'errors' not in data: unfinished.append(str(directory))
        for r in data['results']:
            s = r['blockhit_summary']['bot_side']; o = 'a' if s=='b' else 'b'
            original = r['policy_'+s]; name = aliases.get(original, original)
            configuration = {k:v for k,v in r['parameters'][original].items() if k!='description'}
            if name in configurations and configuration!=configurations[name]:
                raise ValueError('Cannot pool different configurations under '+name)
            configurations[name] = configuration
            groups[(name, r['policy_'+o], r['ping_'+s], r['ping_'+o])].append(r)
    rows = []
    for (policy, opponent, ping, opp_ping), games in sorted(groups.items()):
        won = lost = draw = blocked = received = guard_ms = duration = 0
        margins, guards, keys = [], [], []
        for r in games:
            s=r['blockhit_summary']['bot_side']; o='a' if s=='b' else 'b'
            won += r['winner']==r[s]; lost += r['winner']==r[o]
            draw += r['winner'] not in (r[s], r[o])
            margins.append((r['health_'+s]-r['health_'+o])/2)
            blocked += r['blocks_'+s]; received += r['stats_'+s]['melee_hits_taken']
            guard_ms += r['stats_'+s].get('blocking_time_ms',0)
            duration += r['finish']-r['start']
            guards.extend(r['block_accuracy_validation'][s].get('server_guard_lengths_ms',[]))
            keys.extend(r['blockhit_summary']['key_hold_lengths_ms'])
        rows.append({'policy':policy,'opponent':opponent,'bot_rtt':ping,'opponent_rtt':opp_ping,
                     'matches':[r['id'] for r in games], 'wins':won,'draws':draw,'losses':lost,
                     'mean_health_margin_hearts':statistics.mean(margins),'health_margins_hearts':margins,
                     'blocked_hits':blocked,'incoming_hits':received,'block_percent':100*blocked/received if received else None,
                     'server_guard_uptime_percent':100*guard_ms/duration,
                     'key_hold_median_ms':statistics.median(keys) if keys else None,
                     'key_hold_max_ms':max(keys,default=0),
                     'server_guard_max_ms':max(guards,default=None),
                     'short_guards_qualified':all(r['block_accuracy_validation'][r['blockhit_summary']['bot_side']].get('short_guards_qualified',False) for r in games)})
    return {'scope':'Exploratory real-engine samples; allocation reversed every second game. Raw outcomes retained. Small samples do not prove a general ranking.',
            'directories':[str(d) for d in directories],'aliases':aliases,'configurations':configurations,'unfinished':unfinished,'groups':rows}


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('directories',nargs='+',type=Path);p.add_argument('--output',required=True,type=Path)
    p.add_argument('--alias',action='append',default=[],help='old=new; pooling requires identical frozen parameters except description')
    a=p.parse_args();r=report(a.directories,dict(x.split('=',1) for x in a.alias));a.output.write_text(json.dumps(r,indent=2)+'\n')
    for g in r['groups']:
        print(g['policy'],g['opponent'],f"{g['bot_rtt']:g}/{g['opponent_rtt']:g}",f"{g['wins']}W {g['draws']}D {g['losses']}L",'margin',round(g['mean_health_margin_hearts'],2),'block%',round(g['block_percent'] or 0,1),'max guard',g['server_guard_max_ms'])
