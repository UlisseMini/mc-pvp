"""Verify the physical movement produced by the two ordinary 20 CPS controls."""
import math

from audit import captures
from run import ROOT


def legacy(mid, seat, record, observed_keys=False):
    path,=(ROOT/'runtime/pov'/mid).glob(seat+'-client-*-events.jsonl.gz')
    actions={}; moves={}; problems=[]
    travel='tick_end' if observed_keys else 'science_move'
    scale=1 if observed_keys else .98
    for e in captures.events(path,{'legacy_head',travel}):
        if e.get('match')!=mid or not record['start']<=e.get('time',e.get('time_ms',0))<=record['finish']:continue
        tick=e['client_tick'] if e['type']=='tick_end' else e['tick']
        if e['type']=='legacy_head':
            w,_,side,_=e['keys']
            if tick in actions:raise ValueError(f'Duplicate input in {mid} {seat} {tick}')
            actions[tick]=(scale*int(w),-scale*side)
        else:
            if tick in moves:raise ValueError(f'Duplicate move in {mid} {seat} {tick}')
            if observed_keys:
                body=e['data']['self']
                if body['name']!=record[seat]:raise ValueError('Wrong recorded POV '+mid)
                inp=body['movement_input']
                moves[tick]=(inp['moveForward'],inp['moveStrafe'])
            else:moves[tick]=(e['forward'],e['strafe'])
    shared=sorted(actions.keys() & moves.keys())
    if not shared:raise ValueError(f'Missing legacy movement in {mid} {seat}')
    # A move and its input can straddle the referee's start or finish.
    interior=set(range(shared[0],shared[-1]+1))
    if not interior<=actions.keys() or not interior<=moves.keys():
        raise ValueError(f'Missing interior movement in {mid} {seat}')
    for t in shared:
        if not all(math.isclose(a,b,abs_tol=1e-6) for a,b in zip(actions[t],moves[t])):
            problems.append(dict(tick=t,expected=actions[t],actual=moves[t]))
    method='vanilla end-of-tick movement input against issued legacy keys' if observed_keys else 'physical forward and strafe against issued legacy keys'
    return dict(method=method,heads=len(shared),
                boundary_only_inputs=sorted(actions.keys()-moves.keys()),boundary_only_moves=sorted(moves.keys()-actions.keys()),
                disagreements=problems)
