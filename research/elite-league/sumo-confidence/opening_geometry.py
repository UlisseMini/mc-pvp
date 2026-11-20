"""First fresh-hit positions relative to the actual ring center, plus pre-feedback movement."""
import math
from statistics import median
from study import BUILD,ROOT,read,freeze
from native_opening import events

def one(r):
    mid=r['mid'];dest=BUILD/'geometry'/(mid+'.json')
    if dest.exists():return read(dest)
    record=read(ROOT/'runtime/results'/(mid+'.json'));start,end=record['start'],record['finish']
    side=r['seat'];other='b' if side=='a' else 'a';center=None;hits=[]
    for e in events(ROOT/'runtime/replays'/(mid+'.jsonl'),{'hit','tick'}):
        if e['type']=='tick' and center is None:
            a,b=e['a'],e['b']
            if abs(math.hypot(a['posX']-b['posX'],a['posZ']-b['posZ'])-8)<.001 and abs(a['posY']-12)<.001 and abs(b['posY']-12)<.001:
                center=((a['posX']+b['posX'])/2,(a['posZ']+b['posZ'])/2)
        if e['type']=='hit' and e['time']>=start and not e.get('rod') and not e['damage_upgrade']:hits.append(e)
    if not center:raise ValueError('No regulation spawns in replay '+mid)
    out={'mid':mid};own=record[side]
    if hits:
        h=hits[0];a,b=(h['attacker_state'],h['target_state']) if h['attacker']==own else (h['target_state'],h['attacker_state'])
        def rad(q):return math.hypot(q['posX']-center[0],q['posZ']-center[1])
        out.update(first_own=h['attacker']==own,first_ms=h['time']-start,first_own_radius=rad(a),first_other_radius=rad(b),
            last_fresh_own=hits[-1]['attacker']==own,first_six=[int(e['attacker']==own) for e in hits[:6]])
        first_own=next((e for e in hits if e['attacker']==own),None);first_other=next((e for e in hits if e['attacker']!=own),None)
        if first_own and first_other:out['first_own_minus_first_other_ms']=first_own['time']-first_other['time']
    src,=(ROOT/'runtime/pov'/mid).glob(side+'-client-*-events.jsonl.gz')
    ns=opens=sideways=backs=stationary=jumps=clicks=0
    for e in events(src,{'duel_network','fresh_hurt_status','fresh_target_hurt_status','click'}):
        if not start<=e['time']<=end:continue
        if e['type'].startswith('fresh_'):break
        if e['type']=='click':clicks+=1;continue
        action=e['action'];ns+=1;opens+=(action//12)&1;sideways+=((action//4)%3)!=1
        backs+=action%4==3;stationary+=action%4==0;jumps+=action>=24
    out.update(own_opening_heads=ns,open_gate_heads=opens,sideways_heads=sideways,backs=backs,release_forward=stationary,
        jump_heads=jumps,presses_before_first_feedback=clicks)
    dest.parent.mkdir(exist_ok=True);freeze(dest,out);return out
