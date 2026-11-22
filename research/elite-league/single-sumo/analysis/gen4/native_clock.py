"""Extract native button timing, contacts and server hits from the room's recordings."""
import gzip,json,math,statistics,sys
from collections import Counter
from pathlib import Path

ROOT=Path(__file__).resolve().parents[5]
sys.path.insert(0,str(ROOT/'research/sumo-replay-analysis'))
import inspect_replay as replay

def read(f):
    with gzip.open(f,'rt') as z:
        for l in z:yield json.loads(l)

def duel(r,root):
    mid=r['id']; folder=root/'pov'/mid
    events=[x for x in replay.read(root/'replays'/(mid+'.jsonl')) if x['type']=='hit']
    end=r['finish'];start=r['start'];dt=(end-start)/1000
    datum=dict(mid=mid,speed=r['training_speed'],winner=r['winner'],reason=r['reason'],seconds=dt,
               reach=[r['attack_reach_a'],r['attack_reach_b']],seats=[r['a'],r['b']],
               policies=[r['policy_a'],r['policy_b']],request=r.get('request'))
    for s in 'ab':
        path=next(folder.glob(s+'-client-*-events.jsonl.gz'))
        c=Counter();victim=None;pending={};pulses=[];clicks=[];heads=[];rays=[];mice=[];ticks=[]
        for e in read(path):
            kind=e['type'];d=e.get('data',{});t=e.get('time_ms',e.get('time',0))
            if kind=='click' and 'wall_monotonic_ns' in e:
                clicks.append([e['wall_monotonic_ns'],e['monotonic_ns']])
            if kind=='duel_network':
                c['decisions']+=1;c['allow']+=int((e['action']//12)&1)
                c['changed_action']+=e['action']!=e['proposed']
            if not start<=t<=end:continue
            if kind=='tick_start':ticks.append(e['monotonic_ns'])
            if kind=='native_input_head':heads.append(e['decision_ns'])
            if kind=='render_view' and victim is None:
                victim=next((v['id'] for v in d.get('entities',[]) if v.get('name')==r['b' if s=='a' else 'a']),None)
            if kind=='mouse' and d.get('button')==0 and d.get('down'):
                mice.append(d['event_ns'])
            if kind=='motion_jump_before':c['jumps']+=1
            if kind=='attack_click_before':
                c['press']+=1;ray=d.get('ray',{});before=d['self'];pending[e['client_tick']]=(before,d)
                contact=victim is not None and ray.get('entity')==victim and d['left_click_counter']==0
                c['contact']+=contact
            if kind=='attack_click_after' and e['client_tick'] in pending:
                before,data=pending.pop(e['client_tick']);after=d['self'];u=before['motion'];v=after['motion']
                brake=before['sprinting'] and not after['sprinting'] and not data['left_click_counter'] and math.hypot(u[0],u[2])>0 and math.hypot(v[0]-.6*u[0],v[2]-.6*u[2])<1e-8
                c['brakes']+=brake
            if kind=='engine_attack' and e.get('target_is_canonical') and e.get('ray_selected_target') and 'native_raycast_distance' in e:rays.append(e['native_raycast_distance'])
            if kind=='native_cap_poll':
                pulses.append([e['poll_ns'],e['emitted_monotonic_ns'],e['minimum_interval_ns'],e['admitted'],e['gate_at_poll']])
        own=[h for h in events if h['attacker']==r[s]]
        fresh=[h for h in own if not h['damage_upgrade']]
        c.update(fresh=len(fresh),sprint_fresh=sum(bool(h['attacker_sprint']) for h in fresh),
                 upgrade=len(own)-len(fresh),crits=r['stats_'+s]['crits'])
        m=dict(c);m.update(mouse=len(set(mice)),tps=(len(ticks)-1)*1e9/(ticks[-1]-ticks[0]) if len(ticks)>1 else None)
        if len(clicks)>1:
            m['injected']=len(clicks)
            m['injection_clock_ratio']=(clicks[-1][1]-clicks[0][1])/(clicks[-1][0]-clicks[0][0])
            m['min_wall_click_ms']=min((b[0]-a[0])/1e6 for a,b in zip(clicks,clicks[1:]))
        if pulses:
            m.update(clock_ratio=(pulses[-1][0]-pulses[0][0])/(pulses[-1][1]-pulses[0][1]),
                     cap_ns=sorted(set(p[2] for p in pulses)),admissions=sum(p[3] for p in pulses),
                     longest_ray=max(rays,default=None))
        datum[s]=m
    return datum

if __name__=='__main__':
    root=ROOT/'runtime/slow-sumo/runtime'
    for mid in sys.argv[1:]:
        r=json.loads((root/'results'/(mid+'.json')).read_text())
        print(json.dumps(duel(r,root)),flush=True)
