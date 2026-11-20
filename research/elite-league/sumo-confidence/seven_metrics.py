"""Measure assigned velocity, effective 250 ms recoil, and actual contact drag for both clients."""
import bisect
import math
from statistics import mean
from study import BUILD,ROOT,read,freeze
from native_opening import events

def quant(v):
    return tuple(int(max(-3.9,min(3.9,x))*8000) for x in v)
def summarize(xs):return mean(xs) if xs else None
def one(row):
    mid=row['mid'];path=BUILD/'seven-impacts'/(mid+'.json')
    if path.exists():return read(path)
    record=read(ROOT/'runtime/results'/(mid+'.json'));start,end=record['start'],record['finish']
    ticks=[];times=[];hits=[];vel={}
    for e in events(ROOT/'runtime/replays'/(mid+'.jsonl'),{'hit','mmc_knockback','tick'}):
        t=e['time']
        if not start<=t<=end:continue
        if e['type']=='tick':ticks.append({s:(e[s]['posX'],e[s]['posZ']) for s in 'ab'});times.append(t)
        elif e['type']=='hit' and not e['damage_upgrade'] and not e.get('rod'):hits.append(e)
        elif e['type']=='mmc_knockback':vel.setdefault((t,e['target']),[]).append(e['velocity'])
    result={}
    for label,s in [('actor',row['seat']),('frozen','b' if row['seat']=='a' else 'a')]:
        packets=[];pt=[];press=[];brakes=[];attacks=[]
        src,=(ROOT/'runtime/pov'/mid).glob(s+'-client-*-events.jsonl.gz')
        for e in events(src,{'engine_attack','velocity_packet','click'}):
            if not start<=e['time']<=end:continue
            if e['type']=='click':press.append(e['time'])
            elif e['type']=='velocity_packet' and e.get('own_entity') and (e['vx'] or e['vz']):
                pt.append(e['time']);packets.append(tuple(round(e[k]*8000) for k in ('vx','vy','vz')))
            elif e['type']=='engine_attack':
                attacks.append(e['time'])
                # Require actual reduction of horizontal motion away from the opponent.
                dot=(e['target_x']-e['own_x'])*e['vx_before']+(e['target_z']-e['own_z'])*e['vz_before']
                if dot<0 and e['sprint_before'] and all(abs(e[k+'_after']-.6*e[k+'_before'])<1e-6 for k in ('vx','vz')):
                    brakes.append(e['time'])
        group=[h for h in hits if h['target']==record[s]];sent=[];outward=[];drag=[];presses=[];contacts=[]
        paired=eligible=0;cursor=0
        for h in group:
            at=h['time'];vs=vel.get((at,record[s]),[])
            if len(vs)!=1:continue
            v=vs[0];sent.append(math.hypot(v[0],v[2]))
            i=bisect.bisect_left(times,at+250)
            if i<len(times) and times[i]<=at+320:
                a=h['attacker_state'];b=h['target_state'];dx=b['posX']-a['posX'];dz=b['posZ']-a['posZ'];gap=math.hypot(dx,dz)
                if gap>.001:
                    x,z=ticks[i][s];outward.append(((x-b['posX'])*dx+(z-b['posZ'])*dz)/gap)
            lo=max(cursor,bisect.bisect_left(pt,at-2));hi=bisect.bisect_right(pt,at+450)
            indices=[j for j in range(lo,hi) if packets[j]==quant(v)]
            if len(indices)!=1:continue
            idx=indices[0];cursor=idx+1;paired+=1;t=pt[idx];stop=t+250
            if stop>end or idx+1<len(pt) and pt[idx+1]<=stop:continue
            eligible+=1
            def n(stamps):return bisect.bisect_right(stamps,stop)-bisect.bisect_right(stamps,t)
            drag.append(n(brakes));presses.append(n(press));contacts.append(n(attacks))
        result[label]=dict(incoming_fresh=len(group),unique_server_velocity=len(sent),matched_client_velocity=paired,
            complete_recoil_windows=len(outward),uninterrupted_client_windows=eligible,server_horizontal=summarize(sent),
            outward_travel=summarize(outward),physical_presses_after_velocity=summarize(presses),
            entity_contacts_after_velocity=summarize(contacts),brakes_after_velocity=summarize(drag))
    path.parent.mkdir(exist_ok=True);freeze(path,result);return result
