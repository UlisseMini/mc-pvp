"""Verify the physical movement produced by the two ordinary 20 CPS controls."""
import math

from audit import captures
from run import ROOT


def legacy(mid, seat, record):
    path,=(ROOT/'runtime/pov'/mid).glob(seat+'-client-*-events.jsonl.gz')
    actions={}; moves={}; problems=[]
    for e in captures.events(path,{'legacy_head','science_move'}):
        if e.get('match')!=mid or not record['start']<=e['time']<=record['finish']:continue
        tick=e['tick']
        if e['type']=='legacy_head':
            w,_,side,_=e['keys']
            if tick in actions:raise ValueError(f'Duplicate input in {mid} {seat} {tick}')
            actions[tick]=(.98*int(w),-.98*side)
        else:
            if tick in moves:raise ValueError(f'Duplicate move in {mid} {seat} {tick}')
            moves[tick]=(e['forward'],e['strafe'])
    shared=sorted(actions.keys() & moves.keys())
    if not shared:raise ValueError(f'Missing legacy movement in {mid} {seat}')
    # A move and its input can straddle the referee's start or finish.
    interior=set(range(shared[0],shared[-1]+1))
    if not interior<=actions.keys() or not interior<=moves.keys():
        raise ValueError(f'Missing interior movement in {mid} {seat}')
    for t in shared:
        if not all(math.isclose(a,b,abs_tol=1e-6) for a,b in zip(actions[t],moves[t])):
            problems.append(dict(tick=t,expected=actions[t],actual=moves[t]))
    return dict(method='physical forward and strafe against issued legacy keys',heads=len(shared),
                boundary_only_inputs=sorted(actions.keys()-moves.keys()),boundary_only_moves=sorted(moves.keys()-actions.keys()),
                disagreements=problems)
