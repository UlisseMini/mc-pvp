#!/usr/bin/env python3
"""Retrospective native damage-window readiness and per-client reach.

Readiness is bracketed from actual target hurtResistantTime, not a policy's
click gate or an assumed 500ms wall clock. Local-send deadlines remain intervals:
observed input-to-server delays are not counterfactual exact packet schedules.
"""
import argparse
import bisect
import json
import statistics
from pathlib import Path
from science import client_events, server_events
from trade_return_windows import link_clicks
from combo_survival import geometric_distance

ROOT=Path(__file__).resolve().parent


def reach_window(probes,start,end,max_age=100):
    """Piecewise sampled reach, retaining unknown time and avoiding future data."""
    points=sorted((p['time'],geometric_distance(p),float(p.get('attack_reach',3))) for p in probes)
    times=[p[0] for p in points]
    durations={'in':0.,'out':0.,'unknown':0.};distances=[]
    cursor=start
    while cursor<end:
        i=bisect.bisect_right(times,cursor)-1
        next_sample=times[i+1] if i+1<len(times) else end
        if i<0 or points[i][1] is None or cursor>=times[i]+max_age:
            right=min(end,next_sample);kind='unknown'
        else:
            right=min(end,next_sample,times[i]+max_age)
            kind='in' if points[i][1]<=points[i][2] else 'out'
            distances.append(points[i][1])
        if right<=cursor:raise ValueError('Non-advancing sample integration')
        durations[kind]+=right-cursor;cursor=right
    classification='unknown' if durations['unknown'] else 'in' if not durations['out'] else 'out' if not durations['in'] else 'mixed'
    return dict(classification=classification,duration_ms=durations,
                min_range=min(distances,default=None),max_range=max(distances,default=None))


def ready_bracket(ticks,start,end,target,fresh_ticks=10):
    last_immune=start
    for e in ticks:
        if not start<e['time']<end:continue
        if e[target]['hurtResistantTime']>fresh_ticks:last_immune=e['time']
        else:return [last_immune,e['time']]
    return None


def analyze(game,side='a'):
    other='b' if side=='a' else 'a'
    return analyze_events(game,side,server_events(game['id']),client_events(game,side),client_events(game,other))


def analyze_events(game,side,raw,native,opposite):
    """Score supplied, match-specific streams, including isolated servers with overlapping IDs."""
    other='b' if side=='a' else 'a'
    fresh_ticks=game.get('combat_profile',{}).get('fresh_hit_ticks',10)
    rows,links,linkage=link_clicks(raw,native,game,side)
    if linkage['errors']:raise ValueError(linkage)
    delays=[r['server_receive_ms']-r['consumption_ms'] for r in rows if r['server_receive_ms'] is not None]
    if not delays:raise ValueError('No native local-contact/server-input clock linkage')
    # Full observed envelope, not a selected favorable percentile. This includes
    # proxy delay and actual server scheduling for recorded contacts.
    delay_lo,delay_hi=min(delays),max(delays)
    own_probes=[p for p in native if p['type']=='reach_probe']
    other_probes=[p for p in opposite if p['type']=='reach_probe']
    ticks=[p for p in raw if p['type']=='tick']
    fresh=[(i,e) for i,e in enumerate(raw) if e['type']=='science_server_attack' and e['attacker']==game[side]
           and e['score_before']!=e['score_after'] and e['target_before']['hurtResistantTime']<=fresh_ticks]
    # Ordered accepted returns (including damage upgrades). Prefer authoritative science_server_attack
    # when present for the opponent so hit/science duplicates on the same attack are not double-counted.
    opp_attacks=[(i,e['time']) for i,e in enumerate(raw)
                 if e['type']=='science_server_attack' and e['attacker']==game[other] and e['score_before']!=e['score_after']]
    returns=opp_attacks if opp_attacks else [
        (i,e['time']) for i,e in enumerate(raw)
        if e['type']=='hit' and e['attacker']==game[other] and not e.get('rod')]
    windows=[]
    streak=0
    prev_i=-1
    for index,((i,hit),(j,next_hit)) in enumerate(zip(fresh,fresh[1:])):
        had_return_before=any(prev_i<ri<i for ri,_ in returns)
        streak=1 if (index==0 or had_return_before) else streak+1
        prev_i=i
        left,end=hit['time'],next_hit['time']
        bracket=ready_bracket(ticks,left,end,other,fresh_ticks)
        if bracket is None:
            # The next accepted fresh attack proves readiness no later than
            # that event; previous native snapshots still give a lower bound.
            immune=[t['time'] for t in ticks if left<t['time']<end and t[other]['hurtResistantTime']>fresh_ticks]
            bracket=[max(immune,default=left),end]
        local=[bracket[0]-delay_hi,bracket[1]-delay_lo]
        own=reach_window(own_probes,*local)
        defender=reach_window(other_probes,left,end)
        received=sum(i<ri<j for ri,_ in returns)
        windows.append(dict(after_fresh_hit=index+1,unanswered_fresh_streak=streak,
                            hit_ms=left-game['start'],next_hit_ms=end-game['start'],
                            ready_server_bracket_ms=[t-game['start'] for t in bracket],
                            corresponding_local_send_interval_ms=[t-game['start'] for t in local],
                            own_reach_at_ready=own,defender_reach_until_next_hit=defender,
                            accepted_returns=received,
                            window_to_next_hit_delay_ms=[end-bracket[1],end-bracket[0]],
                            own_range_at_actual_next_click=links.get(j,{}).get('current_box_min_distance')))
    def summarize_landmark(k):
        entry_idx=next((idx for idx,w in enumerate(windows) if w['unanswered_fresh_streak']>=k),None)
        cohort=[] if entry_idx is None else windows[entry_idx:]
        ok_list=[w['own_reach_at_ready']['classification']=='in'
                 and w['defender_reach_until_next_hit']['classification']=='out'
                 and not w['accepted_returns'] and w['window_to_next_hit_delay_ms'][1]<=100 for w in cohort]
        best_run=cur_run=0
        for ok in ok_list:
            cur_run=cur_run+1 if ok else 0
            best_run=max(best_run,cur_run)
        return dict(
            established=entry_idx is not None,
            entry_hit_ms=None if entry_idx is None else windows[entry_idx]['hit_ms'],
            windows=len(cohort),
            certified_ready_windows=sum(ok_list),
            longest_certified_ready_window_run=best_run,
            readiness_class_counts={c:sum(w['own_reach_at_ready']['classification']==c for w in cohort)
                                    for c in ('in','out','mixed','unknown')},
        )
    landmarks={str(k):summarize_landmark(k) for k in (3,5,10)}
    k3=landmarks['3']
    terminal=None
    if fresh:
        bracket=ready_bracket(ticks,fresh[-1][1]['time'],game['finish'],other,fresh_ticks)
        if bracket is not None:
            terminal=dict(ready_server_bracket_ms=[t-game['start'] for t in bracket],
                          unfinished_ready_duration_ms=[game['finish']-bracket[1],game['finish']-bracket[0]],
                          definitely_over_two_ticks=game['finish']-bracket[1]>100)
    return dict(match=game['id'],candidate_side=side,linkage=linkage,fresh_hit_ticks=fresh_ticks,
                contact_to_server_delay_envelope_ms=[delay_lo,delay_hi],contact_delay_median_ms=statistics.median(delays),
                windows=windows,post_third_hit_windows=k3['windows'],
                certified_ready_windows=k3['certified_ready_windows'],
                longest_certified_ready_window_run=k3['longest_certified_ready_window_run'],
                readiness_class_counts=k3['readiness_class_counts'],
                landmarks=landmarks,
                terminal_unfinished_window=terminal,
                objective='Opponent denied throughout; own reach needed at native fresh window. Own out-of-reach while immune is allowed. Gate delays never shift readiness.',
                limits='Observed readiness brackets and finite sampled geometry. Send interval uses full observed contact-delay envelope, not exact counterfactual packet timing. A certificate is conservative; failed/mixed classification is not an impossibility proof. The final censored immunity window is not scored.')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('matches',nargs='+');args=p.parse_args()
    result=[]
    for mid in args.matches:
        g=json.loads((ROOT/'runtime/results'/(mid+'.json')).read_text());d=analyze(g);result.append(d)
        print(mid,'ready classes',d['readiness_class_counts'],'certified windows',d['certified_ready_windows'],'longest run',d['longest_certified_ready_window_run'])
    path=ROOT/'research/fresh-window-audit-20261101.json';path.write_text(json.dumps(result,indent=2)+'\n');print(path)
