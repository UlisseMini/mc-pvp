"""Extract observed opening choices, accepted hits and actual jump takeoffs from each target duel."""
import bisect
import gzip
import json
import math
from study import ROOT,BUILD,read,freeze

def events(path,kinds):
    needles=[b'"type":"'+k.encode()+b'"' for k in kinds]
    with (gzip.open(path,'rb') if path.suffix=='.gz' else path.open('rb')) as src:
        for line in src:
            if any(k in line for k in needles):
                item=json.loads(line)
                if item['type'] in kinds:yield item

def extract(row):
    mid=row['mid'];dest=BUILD/'opening'/(mid+'.json')
    if dest.exists():return read(dest)
    record=read(ROOT/'runtime/results'/(mid+'.json'));start=record['start']
    seat=row['seat'];other='b' if seat=='a' else 'a'
    hits=[e for e in events(ROOT/'runtime/replays'/(mid+'.jsonl'),{'hit'}) if not e.get('damage_upgrade') and e['time']>=start]
    names={s:record[s] for s in 'ab'}
    out=dict(mid=mid,trace_hits=len(hits))
    if hits:
        h=hits[0];a=h['attacker_state'];b=h['target_state']
        out.update(first_own=h['attacker']==names[seat],first_ms=h['time']-start,
                   first_gap=math.hypot(a['posX']-b['posX'],a['posZ']-b['posZ']),
                   opening_hits_by_arm=[x['attacker']==names[seat] for x in hits[:4]])
    for label,s in [('actor',seat),('target',other)]:
        path,=(ROOT/'runtime/pov'/mid).glob(s+'-client-*-events.jsonl.gz')
        keys={'duel_network','click','reach_probe','fresh_hurt_status','fresh_target_hurt_status'}
        heads=[];ht=[];ranges=[];clicks=[];end=record['finish']
        for e in events(path,keys):
            t=e['time']
            if t<start or t>end:continue
            if e['type'] in ('fresh_hurt_status','fresh_target_hurt_status'):
                end=t;break
            if e['type']=='duel_network':
                obs=e['observation'];heads.append((e['action'],math.hypot(obs[0]-obs[3],obs[2]-obs[5])));ht.append(t)
            elif e['type']=='reach_probe' and e['in_range']:ranges.append(t)
            elif e['type']=='click':clicks.append(t)
        pairs=[(t,bisect.bisect_right(ht,t)-1) for t in ranges]
        # Native control code uses bit 12 for its click gate. W-tap has no neural decision log.
        held=[(heads[i][0]//12)&1 for t,i in pairs if i>=0]
        out[label]=dict(open_ms=end-start,heads=len(heads),near_heads=sum(d<=3.3 for _,d in heads),
            near_gate_closed=sum(d<=3.3 and not ((a//12)&1) for a,d in heads),in_reach_probes=len(ranges),
            gated_in_reach_samples=len(held),gate_closed_in_reach=sum(1-x for x in held),presses_before_cue=len(clicks),
            first_in_reach_ms=ranges[0]-start if ranges else None,first_press_ms=clicks[0]-start if clicks else None)
    score=read(BUILD/'scores'/(mid+'.json'))
    out['takeoffs']=score['confirmed_takeoffs']
    dest.parent.mkdir(exist_ok=True);freeze(dest,out);return out

def impacts(row):
    """Observe the first incoming fresh hit with and without a confirmed nearby takeoff per game."""
    mid=row['mid'];dest=BUILD/'impacts'/(mid+'.json')
    if dest.exists():return read(dest)
    record=read(ROOT/'runtime/results'/(mid+'.json'));side=row['seat'];player=record[side]
    path,=(ROOT/'runtime/pov'/mid).glob(side+'-client-*-events.jsonl.gz')
    jumps=[]
    for e in events(path,{'science_move'}):
        b=e['before']
        if record['start']<=e['time']<=record['finish'] and b['ground'] and b['motionY']==0.41999998688697815:
            jumps.append(e['time'])
    hits=[];velocity={};ticks=[];times=[]
    for e in events(ROOT/'runtime/replays'/(mid+'.jsonl'),{'hit','mmc_knockback','tick'}):
        t=e['time']
        if not record['start']<=t<=record['finish']:continue
        if e['type']=='tick':
            ticks.append((e[side]['posX'],e[side]['posZ']));times.append(t)
        elif e['type']=='hit' and e['target']==player and not e['damage_upgrade'] and not e.get('rod'):hits.append(e)
        elif e['type']=='mmc_knockback' and e['target']==player:velocity.setdefault(t,[]).append(e['velocity'])
    out={}
    for h in hits:
        t=h['time'];jump=min(jumps,key=lambda j:abs(j-t)) if jumps else None
        group='with_near_takeoff' if jump is not None and abs(jump-t)<=150 else 'without_near_takeoff'
        if group in out or len(velocity.get(t,[]))!=1:continue
        i=bisect.bisect_left(times,t+250)
        if i==len(times) or times[i]>t+320:continue
        a,b=h['attacker_state'],h['target_state'];dx=b['posX']-a['posX'];dz=b['posZ']-a['posZ'];gap=math.hypot(dx,dz)
        if gap<=.001:continue
        x,z=ticks[i];v=velocity[t][0]
        out[group]=dict(hit_ms=t-record['start'],jump_minus_hit_ms=jump-t if group=='with_near_takeoff' else None,
            initial_horizontal_gap=gap,sent_horizontal=math.hypot(v[0],v[2]),sent_vertical=v[1],
            outward_travel=((x-b['posX'])*dx+(z-b['posZ'])*dz)/gap,observed_ms=times[i]-t)
    dest.parent.mkdir(exist_ok=True);freeze(dest,out);return out
