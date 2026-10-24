#!/usr/bin/env python3
"""Offline calibration of remote-position age using both recorded real clients.

This reads both clients only after play. The bot never receives its opponent's
private state or server traces during play.
"""
import argparse
import bisect
import json
import math
import statistics
from pathlib import Path
from lab import ROOT,RUNTIME

def analyze(identifier):
    events=[json.loads(line) for line in (RUNTIME/'replays'/f'{identifier}.jsonl').read_text().splitlines()]
    match=next(e['match'] for e in events if e['type']=='start');clients={}
    for side in ('a','b'):
        path=RUNTIME/'bots'/match[side].removeprefix('LabSlot')/'events.jsonl'
        clients[side]=[e for line in path.read_text().splitlines() if (e:=json.loads(line)).get('match')==identifier and e['type']=='observation']
    result={'match':identifier,'rtt_a':match['ping_a'],'rtt_b':match['ping_b'],'method':'minimum median squared horizontal position residual, 0–500 ms lag; post-match only'}
    # Closest point to the ordinary expanded player box. This diagnoses geometry;
    # it deliberately does not replace native raycasts, attack cooldown or damage.
    def geometric_reach(obs):
        dx=max(0,abs(obs['target_x']-obs['x'])-.4);dz=max(0,abs(obs['target_z']-obs['z'])-.4)
        eye=obs['y']+1.62;dy=max(0,obs['target_y']-.1-eye,eye-(obs['target_y']+1.9))
        return dx*dx+dy*dy+dz*dz<=9
    counts={k:0 for k in ('a_only','b_only','both','neither')};longest={'a_only':0,'b_only':0};active=None;since=0
    bt=[e['time'] for e in clients['b']]
    for obs in clients['a']:
        i=bisect.bisect_left(bt,obs['time']);near=clients['b'][max(0,i-1):min(len(bt),i+1)]
        if not near:continue
        other=min(near,key=lambda e:abs(e['time']-obs['time']))
        if abs(other['time']-obs['time'])>75:continue
        a,b=geometric_reach(obs),geometric_reach(other)
        key='both' if a and b else 'a_only' if a else 'b_only' if b else 'neither';counts[key]+=1
        if key!=active:active=key;since=obs['time']
        if key in longest:longest[key]=max(longest[key],obs['time']-since)
    result['geometric_windows']={'paired_samples':sum(counts.values()),'sample_counts':counts,'longest_ms':longest,
                                 'limitation':'closest-point geometry only; ignores ray direction, miss cooldown, immunity and attacks already sent'}
    for side,other in [('a','b'),('b','a')]:
        truth=clients[other];times=[e['time'] for e in truth];fits=[]
        for lag in range(0,1001,10):
            residuals=[]
            for obs in clients[side]:
                target_time=obs['time']-lag;i=bisect.bisect_right(times,target_time)
                if i<1 or i>=len(times) or obs['time']<match['start']+1500:continue
                p,q=truth[i-1:i+1];dt=q['time']-p['time']
                if dt<=0 or dt>150:continue
                if ((q['x']-p['x'])**2+(q['z']-p['z'])**2)**.5/(dt/1000)<.3:continue
                f=(target_time-p['time'])/dt
                x=p['x']+f*(q['x']-p['x']);z=p['z']+f*(q['z']-p['z'])
                residuals.append((obs['target_x']-x)**2+(obs['target_z']-z)**2)
            fits.append({'lag_ms':lag,'median_squared_residual':statistics.median(residuals) if residuals else 1e99,'samples':len(residuals)})
        seen=clients[side]
        def extent(items,prefix=''):
            return math.sqrt(sum((max(e[prefix+k] for e in items)-min(e[prefix+k] for e in items))**2 for k in ('x','y','z'))) if items else 0
        remote_extent=extent(seen,'target_');actual_extent=extent(truth)
        result[side]={'best':min(fits,key=lambda f:f['median_squared_residual']),'fits':fits,
                      'remote_extent':remote_extent,'actual_extent':actual_extent,
                      'frozen_remote':bool(seen and seen[-1]['time']-seen[0]['time']>3000 and remote_extent<.05 and actual_extent>1)}
    return result

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('match');args=parser.parse_args()
    data=analyze(args.match)
    output=ROOT/'experiments'/f'{args.match}-view-age.json';output.write_text(json.dumps(data,indent=2))
    print(json.dumps({k:v if k not in ('a','b') else v['best'] for k,v in data.items()},indent=2))
