#!/usr/bin/env python3
"""One declared, reduced-reach capability test, never a competitive ladder row.

Advance difficulty only after reviewing native failure cycles and confirmation.
There is deliberately no parameter grid or automatic best-score selection here.
"""
import argparse
import asyncio
import hashlib
import json
import time
from pathlib import Path

from tools.experiments import ROOT, match, save_policy
from lab_runtime import properties
from tools.runtime_provenance import cohort_provenance
from tools.parity import archive
from tools.science import server_events, client_events
from tools.combo_survival import analyze, geometric_distance
from tools.fresh_window_analysis import analyze as fresh_windows


def checkpoint(path, doc):
    tmp = path.with_suffix(path.suffix + '.tmp')
    tmp.write_text(json.dumps(doc, indent=2) + '\n', encoding='utf-8')
    tmp.replace(path)


def maintenance(game, side='a'):
    other = 'b' if side == 'a' else 'a'
    hits = [h for h in server_events(game['id']) if h['type'] == 'hit' and not h.get('rod')]
    own = [h for h in hits if h['attacker'] == game[side] and not h.get('damage_upgrade', False)]
    # Entry is declared before looking at the result: the third fresh hit.
    # No later favorable streak is substituted when this entry fails.
    if len(own) < 3:
        return {'passed': False, 'reason': 'Fewer than three fresh entry hits'}
    start = own[2]['time']
    contacts = [h['time'] for h in own if h['time'] >= start]
    returns = [h['time'] for h in hits if h['attacker'] == game[other] and h['time'] >= start]
    probes = [e for e in client_events(game, other) if e['type'] == 'reach_probe' and start <= e['time'] <= game['finish']]
    geometry = [(e['time'], geometric_distance(e), e.get('attack_reach', 3)) for e in probes]
    geometry = [(t, d, r) for t, d, r in geometry if d is not None]
    gaps = [y-x for x, y in zip(contacts, contacts[1:])]
    sample_gaps = [y[0]-x[0] for x, y in zip(geometry, geometry[1:])]
    unknown_boundary = not geometry or geometry[0][0]-start > 100 or game['finish']-geometry[-1][0] > 100
    within = sum(d <= r for _, d, r in geometry)
    own_probes=[e for e in client_events(game,side) if e['type']=='reach_probe' and start<=e['time']<=game['finish']]
    own_geometry=[(e['time'],geometric_distance(e),e.get('attack_reach',3)) for e in own_probes]
    own_geometry=[(t,d,r) for t,d,r in own_geometry if d is not None]
    outside=sum(d>r for _,d,r in own_geometry)
    own_gaps=[y[0]-x[0] for x,y in zip(own_geometry,own_geometry[1:])]
    own_unknown=not own_geometry or own_geometry[0][0]-start>100 or game['finish']-own_geometry[-1][0]>100 or max(own_gaps,default=9999)>100
    duration = game['finish']-start
    gap = max(gaps+[game['finish']-contacts[-1]], default=0)
    defender_regular = duration >= 20_000 and len(contacts) >= 20 and not returns and gap <= 1000 and not within and not unknown_boundary and max(sample_gaps, default=9999) <= 100
    passed = defender_regular and not outside and not own_unknown
    return dict(passed=passed, entry_ms=start-game['start'], duration_ms=duration,
                fresh_hits=len(contacts), accepted_returns=len(returns),
                first_return_ms=None if not returns else returns[0]-start,
                max_fresh_contact_gap_including_terminal_ms=gap,
                opponent_geometry_samples=len(geometry), opponent_in_reach_samples=within,
                minimum_sampled_reach_margin=min((d-r for _, d, r in geometry), default=None),
                max_geometry_sample_gap_ms=max(sample_gaps, default=None),
                missing_boundary_samples=unknown_boundary,
                own_geometry_samples=len(own_geometry), own_out_of_reach_samples=outside,
                own_unknown_geometry=own_unknown,
                defender_denied_with_regular_hits=defender_regular,
                note='Finite sampled evidence, not a proof of infinite or continuous denial. Entry fixed at third fresh hit; no cherry-picked later streak.')


async def run(args):
    path = ROOT/'experiments'/(args.name+'.json')
    if path.exists():
        raise ValueError('Use a new evidence name; existing results cannot be overwritten')
    if not 1 <= args.opponent_reach <= 3:
        raise ValueError('Opponent reach must be in [1, 3]')
    candidate = properties(ROOT/'policies'/(args.candidate+'.properties'))
    target = properties(ROOT/'policies/combo-hold-w20.properties')
    target['attack_reach'] = str(args.opponent_reach)
    target['description'] = 'Declared reduced-reach hold-W curriculum target; never a vanilla ladder reference'
    if float(candidate.get('attack_reach',3)) != 3 or int(candidate.get('click_cps',10)) != 20:
        raise ValueError('Candidate must have native 3.0 reach and 20 CPS ceiling')
    for p in (candidate, target):
        if any(float(p.get(k, 0)) != 0 for k in ('aim_delay_ms','aim_noise_deg','aim_turn_rate_dps')):
            raise ValueError('This curriculum freezes perfect aim')
    target_name = args.name+'-target'
    save_policy(target_name, target)
    sources = ['combo_curriculum.py','experiments.py','combo_survival.py','burst_science.py','native_windows.py','fresh_window_analysis.py']
    frozen = {p: hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in sources}
    doc = dict(name=args.name, created=time.time(), state='running', hypothesis=args.hypothesis,
               scope='Reduced-opponent-reach maintenance capability; excluded from vanilla ladder.',
               protocol='One serial native scenario; fixed six-block resting start, three-second setup, perfect aim, no jumps/strafes, both 20 CPS. JVM/network clock phases are measured, not seeded.',
               entry='Third fresh candidate hit; >=20s and >=20 fresh contacts, defender always outside reach. Own reach at each native readiness/send-time bracket, accepted next hit within two nominal ticks of the readiness bracket; no artificial click-gate deadline. Continuous own reach is secondary.',
               parameters={'candidate': candidate,'target': target}, candidate=args.candidate,
               opponent=target_name, rtt=[args.ping,args.ping], duration_ms=args.duration_ms,
               reverse=args.reverse, provenance=cohort_provenance(), sources=frozen)
    checkpoint(path, doc)
    try:
        a,b = (target_name,args.candidate) if args.reverse else (args.candidate,target_name)
        game = await match(a,b,args.ping,args.ping,duration_ms=args.duration_ms,reach_curriculum=True)
        doc['game'] = game
        checkpoint(path,doc)
        archive(game)
        quality = diagnostic(game)
        if cohort_provenance() != doc['provenance'] or any(hashlib.sha256((ROOT/p).read_bytes()).hexdigest()!=h for p,h in frozen.items()):
            quality['passed']=False;quality['issues'].append('Frozen runtime/source changed')
        doc['quality']=quality
        doc['combo_analysis']=analyze(game)
        side='b' if args.reverse else 'a'
        doc['continuous_reach_diagnostic']=maintenance(game,side)
        doc['fresh_window_analysis']=fresh_windows(game,side)
        ready=doc['fresh_window_analysis'];terminal=ready['terminal_unfinished_window']
        doc['capability_passed']=quality['passed'] and doc['continuous_reach_diagnostic'].get('defender_denied_with_regular_hits',False) and ready['post_third_hit_windows']>=20 and ready['certified_ready_windows']==ready['post_third_hit_windows'] and not (terminal and terminal['definitely_over_two_ticks'])
        doc['objective_version']='native-readiness-v1; own excursions during immunity allowed'
        doc['state']='complete'
        checkpoint(path,doc)
        print(json.dumps({'file':str(path),'match':game['id'],'score':[game['hits_a'],game['hits_b']], 'quality':quality['passed'],'issues':quality['issues'],'capability_passed':doc['capability_passed'],'ready_class_counts':ready['readiness_class_counts'],'longest_ready_run':ready['longest_certified_ready_window_run']},indent=2),flush=True)
    except BaseException as error:
        doc['state']='interrupted';doc['error']=repr(error);checkpoint(path,doc);raise


if __name__ == '__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('name');p.add_argument('--candidate',required=True)
    p.add_argument('--hypothesis',required=True)
    p.add_argument('--opponent-reach',type=float,default=2.5)
    p.add_argument('--ping',type=int,default=20)
    p.add_argument('--duration-ms',type=int,default=30000)
    p.add_argument('--reverse',action='store_true')
    args=p.parse_args()
    if not __import__('re').fullmatch('[a-z0-9-]{1,48}',args.name):p.error('Use a short lowercase evidence name')
    asyncio.run(run(args))
