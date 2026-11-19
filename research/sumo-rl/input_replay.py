"""Audit two-sided physical-input replays over the first 2 seconds of native sumo."""
import argparse, gzip, json, math, statistics, subprocess
from run import ROOT, compile_model, java
from calendars import server_stage, CLIENT
from pathlib import Path

def sprint_flag_drains(events, first, last, start):
    # Read the wire's metadata index to exclude health-only S1C packets. Both wire
    # and main-thread handler IDs come from the same instrumentation namespace.
    import sys
    from bisect import bisect_right
    research = str(ROOT / 'research')
    if research not in sys.path:
        sys.path.append(research)
    from network_pipeline_audit import decode
    received = {}
    intervals = []
    handlers = []
    for e in sorted(events, key=lambda e: e.get('monotonic_ns', -1)):
        typ = e['type']
        if typ == 'packet_wire':
            data = e['data']; packet = data.get('packet', data)
            if packet['direction'] == 'in' and packet['kind'] == 'S1CPacketEntityMetadata':
                decoded = decode(data['base64'], 's2c')
                if decoded['id'] != 0x1c or ('metadata_decode_stopped_at' in decoded and 'flags' not in decoded):
                    raise ValueError('Could not identify metadata flags in packet ' + str(packet['packet_id']))
                received[packet['packet_id']] = decoded
        elif typ == 'client_task_drain':
            if e['data']['phase'] == 'start':
                intervals.append([e['monotonic_ns'], None])
            elif intervals:
                intervals[-1][1] = e['monotonic_ns']
        elif typ == 'packet_handler_enter' and e['data']['main_thread'] and e['data']['kind'] == 'S1CPacketEntityMetadata':
            if first <= e['monotonic_ns'] <= last and e['time_ms'] >= start:
                handlers.append((e['data']['packet_id'], e['monotonic_ns']))
    starts = [entry[0] for entry in intervals]
    flags = []
    for packet_id, at in handlers:
        if packet_id not in received:
            raise ValueError(f'Missing wire for metadata handler {packet_id}')
        packet = received[packet_id]
        if 'flags' not in packet:
            continue
        index = bisect_right(starts, at) - 1
        if index < 0 or (intervals[index][1] is not None and at > intervals[index][1]):
            raise ValueError(f'Metadata handler {packet_id} outside client task drain')
        # The generated model supplies the actual flag value.
        flags.append((packet['entity_id'], starts[index]))
    return flags

def compare(mid,delay_pairs=None,mouse='generated',diagnose=False):
    g=json.loads((ROOT/f'runtime/results/{mid}.json').read_text())
    modes={g['parameters_'+s].get('aim_input','ideal') for s in 'ab'}
    if len(modes)!=1:raise ValueError('Mixed mouse modes in physical-input panel')
    aim=mouse if modes=={'mouse'} else 'ideal'
    if mouse not in ('generated','recorded'):raise ValueError('Unknown physical mouse replay')
    start=g['start'];end=min(start+2000,g['finish'])
    rows=[];refs={};counts={};native_hits=[];clock_origins=[];trackers={}
    interventions={'a':[],'b':[]};velocity_drains={'a':[],'b':[]};sprint_evidence={'a':[],'b':[]}
    settings_set={v for v in delay_pairs or () if isinstance(v,str)}
    use_drains=bool({'observed-velocity-drains','observed-velocity-and-sprint-drains'} & settings_set)
    use_flags=bool({'observed-sprint-drains','observed-velocity-and-sprint-drains'} & settings_set)
    flag_drains={}
    for side in 'ab':
        raw=[]
        with gzip.open(next((ROOT/f'runtime/pov/{mid}').glob(f'{side}-client-*-events.jsonl.gz')),'rt') as f:
            for l in f:
                e=json.loads(l)
                earliest=start-250 if aim!='ideal' else start
                if earliest<=e.get('time',e.get('time_ms',0))<=end:raw.append(e)
        jump={e['native_tick'] for e in raw if e['type'] in ('sumo_rl_jump','jump_reset_input','duel_network_jump')}
        network_keys={e['tick']:e['action']%4 for e in raw if e['type']=='duel_network'}
        for e in raw:
            if e['type']=='legacy_head':
                w,ctrl,_,_=e['keys'];network_keys[e['tick']]=(2 if ctrl else 1) if w else 0
        heads={e['native_tick']:e for e in raw if e['type']=='native_input_head'}
        if not heads:return dict(mid=mid,unsupported='No native heads on '+side)
        moves={e['tick']:e for e in raw if e['type']=='science_move' and e['tick'] in heads}
        if not moves:return dict(mid=mid,unsupported='No native travel on '+side)
        first=heads[min(moves)]['monotonic_ns'];last=max(e['monotonic_ns'] for e in moves.values())
        if use_flags:
            flag_drains[side]=sprint_flag_drains(raw,first,last,start)
        frame=None;deltas={}
        if aim=='recorded':
            for e in raw:
                if e['type']=='replication_frame':frame=e['monotonic_ns']
                if e['type']!='mouse_input':continue
                if frame is None:
                    if e['monotonic_ns']>=first and e['counts']!=[0,0]:raise ValueError('Unanchored physical mouse delta')
                    continue
                if frame in deltas:raise ValueError('Two mouse movements on one native frame')
                if frame<first and e['counts']!=[0,0]:raise ValueError('Nonzero physical mouse input before settled opening')
                deltas[frame]=[*e['counts'],e['sensitivity'],str(e['inverted']).lower()]
        counts[side]=0;drain=None
        for e in raw:
            ns=e.get('monotonic_ns',-1)
            if not first<=ns<=last:continue
            typ=e['type'];kind=CLIENT.get(typ);keys=[]
            if use_drains and typ=='client_task_drain':drain=ns if e['data']['phase']=='start' else None
            if use_drains and typ=='replication_apply_after' and e['packet']['kind']=='hm' and e['time']>=start:
                if drain is None:raise ValueError('Own-velocity application outside a recorded task drain')
                velocity_drains[side].append((e['packet']['entity_id'],drain))
            if diagnose and typ=='replication_apply_after' and e['packet']['kind']=='hm':
                p=e['packet'];interventions[side].append((ns,'velocity',p['entity_id'],[p['motion'+a]/8000 for a in 'XYZ']))
            if diagnose and typ=='engine_attack':interventions[side].append((ns,'contact',None,None))
            if diagnose and typ=='engine_attack':
                sprint_evidence[side].append((ns,'contact',None,e['sprint_before'],e['sprint_after'],e['vx_before'],e['vx_after']))
            if diagnose and typ=='replication_apply_after' and e['packet']['kind']=='hk' and 'sprint' in (e.get('state') or {}):
                sprint_evidence[side].append((ns,'metadata',e['packet']['entity_id'],e['state']['sprint']))
            if typ=='click':kind='POLL_NATIVE' if e['input_poll']=='native_tick' else 'POLL_FRAME';counts[side]+=1
            if typ=='client_task_drain' and e['data']['phase']=='start':kind='CLIENT_DRAIN'
            if typ=='native_input_head':
                tick=e['native_tick'];m=moves[tick]
                keys=[network_keys.get(tick,3 if m['forward']<0 else 2 if m['forward'] else 0),str(tick in jump).lower(),m['strafe']]
            if typ=='replication_frame' and aim=='recorded':keys=deltas.get(ns,[0,0,.5,'false'])
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
           *[int(v['encodedRotationYaw']) for v in seeds],*[int(v['encodedRotationPitch']) for v in seeds],int(trackers[g['a']][0]<trackers[g['b']][0]),
           int(g['initial_ping_b']),g['sumo_arena']['diameter'],g['sumo_arena']['start_gap'],aim]))]
    if diagnose:query[0]+=' ledger'
    if use_drains:
        for side,observed in velocity_drains.items():
            own_id=trackers[g[side]][1]['entity_id']
            query.extend(f'@velocity {side} {ns-origin}' for entity,ns in observed if entity==own_id)
    if use_flags:
        for side,observed in flag_drains.items():
            own_id=trackers[g[side]][1]['entity_id']
            query.extend(f'@flags {side} {ns-origin}' for entity,ns in observed if entity==own_id)
    query+=[' '.join(map(str,[ns-origin,side,kind,*keys])) for ns,side,kind,keys in rows]
    settings=[] if delay_pairs is None else [v if isinstance(v,str) else f'{v[0]},{v[1]}' for v in delay_pairs]
    proc=subprocess.run(java('InputReplay',*settings),input='\n'.join(query)+'\n',text=True,capture_output=True,check=True)
    def measure(pred):
        assert pred['admissions']==[counts['a'],counts['b']],(mid,pred['admissions'],counts)
        assert len(refs)==len(pred['moves'])
        errors=[];first_bad=None;previous={s:None for s in 'ab'}
        ledger=None
        for ns,side,*values in pred['moves']:
            native=refs[ns+origin,side]
            distance=math.dist(values[:3],native[:3]);errors.append(distance)
            if first_bad is None and values!=native:
                first_bad={'side':side,'ms':ns/1e6+origin/1e6+statistics.median(clock_origins)-start,'pred':values,'native':native}
                if diagnose:
                    own_id=trackers[g[side]][1]['entity_id']
                    physical=sorted((at-origin,kind,vector) for at,kind,entity,vector in interventions[side] if entity in (None,own_id))
                    simulated=sorted([(at,'velocity',v) for at,*v in pred['velocity'][side]]+[(at,'contact',None) for at,s in pred['contacts'] if s==side])
                    lo=previous[side] if previous[side] is not None else -1
                    ledger=dict(previous_travel_ns=lo,travel_ns=ns,native_in_step=[e for e in physical if lo<e[0]<=ns],
                                sim_in_step=[e for e in simulated if lo<e[0]<=ns],
                                native_sprint=[(at-origin,kind,*v) for at,kind,entity,*v in sprint_evidence[side]
                                               if entity in (None,own_id) and abs(at-origin-ns)<100_000_000],
                                sim_sprint=[(at,kind,value) for at,s,kind,value in pred['sprint_history']
                                            if s==side and abs(at-ns)<100_000_000],
                                native_near=[e for e in physical if abs(e[0]-ns)<150_000_000],
                                sim_near=[e for e in simulated if abs(e[0]-ns)<150_000_000])
            previous[side]=ns
        shift=origin/1e6+statistics.median(clock_origins)-start
        sim_hits=[[round(t+shift,2),a,u] for t,a,u in pred['hits']]
        result=dict(mid=mid,aim_input=aim,moves=len(errors),clicks=sum(counts.values()),exact=first_bad is None,
                    first_difference=first_bad,max_position_error=max(errors),native_hits=native_hits,model_hits=sim_hits)
        if 'observed_drain_coverage' in pred:result['observed_drain_coverage']=pred['observed_drain_coverage']
        if 'observed_flag_coverage' in pred:result['observed_flag_coverage']=pred['observed_flag_coverage']
        if diagnose:result['first_step_ledger']=ledger
        return result
    results=[measure(json.loads(line)) for line in proc.stdout.splitlines()]
    if delay_pairs is None:return results[0]
    if len(results)!=len(delay_pairs):raise ValueError('Incomplete transport grid')
    return results
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('output',type=Path);p.add_argument('mids',nargs='+');a=p.parse_args()
    compile_model();results=[compare(mid) for mid in a.mids]
    with a.output.open('x') as f:json.dump(results,f,indent=2)
    print([(r['mid'],r.get('exact'),round(r.get('max_position_error',0),4)) for r in results])
