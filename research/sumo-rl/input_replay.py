"""Audit two-sided physical-input replays over the first 2 seconds of native sumo."""
import argparse, gzip, json, math, statistics, subprocess
from run import ROOT, compile_model, java
from calendars import server_stage, CLIENT
from pathlib import Path

def compare(mid):
    g=json.loads((ROOT/f'runtime/results/{mid}.json').read_text())
    start=g['start'];end=min(start+2000,g['finish'])
    rows=[];refs={};counts={};native_hits=[];clock_origins=[];trackers={}
    for side in 'ab':
        raw=[]
        with gzip.open(next((ROOT/f'runtime/pov/{mid}').glob(f'{side}-client-*-events.jsonl.gz')),'rt') as f:
            for l in f:
                e=json.loads(l)
                if start<=e.get('time',e.get('time_ms',0))<=end:raw.append(e)
        jump={e['native_tick'] for e in raw if e['type'] in ('sumo_rl_jump','jump_reset_input','duel_network_jump')}
        network_keys={e['tick']:e['action']%4 for e in raw if e['type']=='duel_network'}
        heads={e['native_tick']:e for e in raw if e['type']=='native_input_head'}
        if not heads:return dict(mid=mid,unsupported='No native heads on '+side)
        moves={e['tick']:e for e in raw if e['type']=='science_move' and e['tick'] in heads}
        if not moves:return dict(mid=mid,unsupported='No native travel on '+side)
        first=heads[min(moves)]['monotonic_ns'];last=max(e['monotonic_ns'] for e in moves.values())
        counts[side]=0
        for e in raw:
            ns=e.get('monotonic_ns',-1)
            if not first<=ns<=last:continue
            typ=e['type'];kind=CLIENT.get(typ);keys=[]
            if typ=='click':kind='POLL_NATIVE' if e['input_poll']=='native_tick' else 'POLL_FRAME';counts[side]+=1
            if typ=='client_task_drain' and e['data']['phase']=='start':kind='CLIENT_DRAIN'
            if typ=='native_input_head':
                tick=e['native_tick'];m=moves[tick]
                if m['forward']<0:return dict(mid=mid,unsupported='Backward input')
                keys=[network_keys.get(tick,3 if m['forward']<0 else 2 if m['forward'] else 0),str(tick in jump).lower(),m['strafe']]
            if not kind:continue
            rows.append((ns,side,kind,keys))
            if typ=='science_move':refs[ns,side]=[e['after'][p+a] for p in ('pos','motion') for a in 'XYZ']
    with (ROOT/f'runtime/replays/{mid}.jsonl').open() as f:
        for l in f:
            e=json.loads(l)
            if not start<=e.get('time',0)<=end:continue
            if e['type']=='replication_tracker_before':
                trackers.setdefault(e['tracker']['player'],(e['monotonic_ns'],e['tracker']))
            if server_stage(e):
                rows.append((e['monotonic_ns'],'s',server_stage(e),[]))
                clock_origins.append(e['time']-e['monotonic_ns']/1e6)
            if e['type']=='hit' and not e['rod']:native_hits.append([e['time']-start,e['attacker']==g['a'],e['damage_upgrade']])
    rows.sort();origin=rows[0][0]
    first_tracker=min(trackers[g[s]][0] for s in 'ab')
    prefix_ends=sum(ns<first_tracker and kind=='SERVER_END' for ns,_,kind,_ in rows)
    seeds=[trackers[g[s]][1] for s in 'ab']
    if seeds[0]['updateCounter']!=seeds[1]['updateCounter']:raise ValueError('Unsynchronized tracker initialization')
    query=[' '.join(map(str,[(g['arena']+1)*4096,int(g['initial_ping_a']),g['practice_preset'],
           int(seeds[0]['updateCounter'])-prefix_ends,*[int(v['ticksSinceLastForcedTeleport']) for v in seeds],
           *[int(v['encodedRotationYaw']) for v in seeds],*[int(v['encodedRotationPitch']) for v in seeds],int(trackers[g['a']][0]<trackers[g['b']][0])]))]
    query+=[' '.join(map(str,[ns-origin,side,kind,*keys])) for ns,side,kind,keys in rows]
    proc=subprocess.run(java('InputReplay'),input='\n'.join(query)+'\n',text=True,capture_output=True,check=True)
    pred=json.loads(proc.stdout);assert pred['admissions']==[counts['a'],counts['b']],(mid,pred['admissions'],counts)
    assert len(refs)==len(pred['moves'])
    errors=[];first_bad=None
    for ns,side,*values in pred['moves']:
        native=refs[ns+origin,side]
        distance=math.dist(values[:3],native[:3]);errors.append(distance)
        if first_bad is None and values!=native:
            first_bad={'side':side,'ms':ns/1e6+origin/1e6+statistics.median(clock_origins)-start,'pred':values,'native':native}
    shift=origin/1e6+statistics.median(clock_origins)-start
    sim_hits=[[round(t+shift,2),a,u] for t,a,u in pred['hits']]
    return dict(mid=mid,moves=len(errors),clicks=sum(counts.values()),exact=first_bad is None,
                first_difference=first_bad,max_position_error=max(errors),native_hits=native_hits,model_hits=sim_hits)
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('output',type=Path);p.add_argument('mids',nargs='+');a=p.parse_args()
    compile_model();results=[compare(mid) for mid in a.mids]
    with a.output.open('x') as f:json.dump(results,f,indent=2)
    print([(r['mid'],r.get('exact'),round(r.get('max_position_error',0),4)) for r in results])
