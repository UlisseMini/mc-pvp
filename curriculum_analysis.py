#!/usr/bin/env python3
"""Offline contact-to-contact state transitions from all native views."""
import argparse
import bisect
import json
import math
from pathlib import Path
from science import server_events, client_events
from combo_survival import geometric_distance
from trade_return_windows import link_clicks

ROOT=Path(__file__).resolve().parent


def audit(game, side='a'):
    other='b' if side=='a' else 'a'
    raw=server_events(game['id'])
    native=client_events(game,side)
    opposite=client_events(game,other)
    _, linked, linkage=link_clicks(raw,native,game,side)
    probes=[p for p in opposite if p['type']=='reach_probe' and geometric_distance(p) is not None]
    pt=[p['time'] for p in probes]
    hits=[]
    for i,e in enumerate(raw):
        if e['type']!='science_server_attack' or e['attacker']!=game[side] or e['score_before']==e['score_after'] or e['target_before']['hurtResistantTime']>10:
            continue
        a,b=e['attacker_before'],e['target_before']
        j=bisect.bisect_right(pt,e['time'])-1
        p=probes[j] if j>=0 else None
        link=linked.get(i,{})
        hits.append(dict(t_ms=e['time']-game['start'], server_tick=e['server_tick'],
                         server_sprint=a['sprint'], target_height=b['posY']-4,
                         own_height=a['posY']-4, true_gap=math.hypot(a['posX']-b['posX'],a['posZ']-b['posZ']),
                         local_click_range=link.get('current_box_min_distance'),
                         opponent_range=None if p is None else geometric_distance(p),
                         opponent_sample_age_ms=None if p is None else e['time']-p['time'],
                         impulse=e.get('velocity_packet')))
    cycles=[]
    for a,b in zip(hits,hits[1:]):
        start,end=game['start']+a['t_ms'],game['start']+b['t_ms']
        samples=[p for p in probes if start<=p['time']<=end]
        cycles.append(dict(start_ms=a['t_ms'],end_ms=b['t_ms'],interval_ms=end-start,
                           gap_change=b['true_gap']-a['true_gap'],target_height_next=b['target_height'],
                           min_opponent_margin=min((geometric_distance(p)-p.get('attack_reach',3) for p in samples),default=None)))
    returns=[h['time']-game['start'] for h in raw if h['type']=='hit' and h['attacker']==game[other] and not h.get('rod')]
    plans=[p for p in native if p['type']=='orbit_plan']
    residual=[]
    for a,b in zip(plans,plans[1:]):
        if a.get('projected_range') is not None and 40<=b['time']-a['time']<=65:
            residual.append(b['local_range']-a['projected_range'])
    return dict(match=game['id'],candidate_side=side,linkage=linkage,fresh=hits,cycles=cycles,return_times_ms=returns,
                one_step_range_bias=sum(residual)/len(residual) if residual else None,
                one_step_range_mae=sum(map(abs,residual))/len(residual) if residual else None,
                limits='Post-hoc native state, not live controller inputs. Reach minima are sampled; geometry is not inferred from scores. Contact cycle is a transition, not a fitted periodic-orbit proof.')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('evidence');args=p.parse_args()
    path=ROOT/'experiments'/(args.evidence+'.json')
    d=json.loads(path.read_text());r=audit(d['game'],'b' if d.get('reverse') else 'a')
    out=ROOT/'research'/(args.evidence+'-cycles.json');out.write_text(json.dumps(r,indent=2)+'\n')
    print(out)
    print('linkage',r['linkage'],'first returns',r['return_times_ms'][:5])
    for row in r['fresh'][:12]:print(json.dumps(row))
