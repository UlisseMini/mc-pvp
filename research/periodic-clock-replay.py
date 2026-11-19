#!/usr/bin/env python3
"""Replay initial state + recorded input/update clocks through one coupled plant.

Later native poses, packets and attacks are comparison data only. No fresh-hit
confirmations, observed impulses, or remote trajectory corrections are fed back.
"""
import argparse,base64,collections,csv,gzip,hashlib,io,json,math,statistics,struct,subprocess,tempfile,shutil
from network_pipeline_audit import archive,wire as pipeline_wire,pair_payloads,signature,input_signature
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA=Path(shutil.which('java') or 'java')
SOURCES=['src/lab/ExactTravel.java','src/lab/SumoFootprint.java','src/lab/Json.java','src/lab/CombatProfile.java','src/lab/DuelSettings.java','src/lab/Compat.java','src/lab/PracticePresets.java','tests/PeriodicComboFightModel.java','tests/PeriodicComboReplay.java','tests/PeriodicComboTransport.java']

def wire(events,direction,player=None):
    rows=pipeline_wire(events,direction,player)
    # S19PacketEntityStatus has protocol47 ID 0x1a and a fixed int entity ID.
    # Extend this adapter without changing the shared pipeline helper.
    flow='c2s'if (player is None and direction=='out')or(player and direction=='in')else's2c'
    if flow=='s2c':
        for e in events:
            if e['type']!='packet_wire'or(player and e.get('player')!=player):continue
            d=e['data'];meta=d.get('packet',d)
            if meta['direction']!=direction:continue
            raw=base64.b64decode(d['base64'])
            if len(raw)!=6 or raw[0]!=0x1a:continue
            entity,status=struct.unpack('>ib',raw[1:])
            if status!=2:continue
            rows.append({'event':e,'ns':e['monotonic_ns'],'payload':d['base64'],'packet':{'id':0x1a,'entity_id':entity,'status':status},'packet_id':meta.get('packet_id')})
    return sorted(rows,key=lambda r:r['ns'])

def load(mid,side):
    path=ROOT/'research/traces'/f'{mid}-{side}.jsonl.gz'
    if path.exists():
        with gzip.open(path,'rt')as f:return [json.loads(l)for l in f]
    if side=='server':
        with (ROOT/'runtime/replays'/f'{mid}.jsonl').open() as f:return [json.loads(l) for l in f]
    return [e for e in archive(mid,side) if 'time' in e]

def initial_tracker_state(events,game,clock):
    """Use the replay clock boundary for both tracker counter and teleport age."""
    seeds=[next(e for e in events if e['type']=='replication_tracker_before'
                and e['tracker']['player']==game[side] and clock(e)>=0)
           for side in ('a','b')]
    counters=[int(e['tracker']['updateCounter'])for e in seeds]
    if counters[0]!=counters[1]:
        raise ValueError(('Unsupported unequal initial tracker counters',counters))
    # The clock may start between the last pre-start tracker and tick_end.
    # The replay increments its counter at END, whereas the seed was observed
    # immediately before the first in-window tracker. Undo those prefix ends.
    first_track=min(map(clock,seeds))
    prefix_ends=sum(e['type']=='replication_server_clock' and e['phase']=='tick_end'
                    and 0<=clock(e)<first_track for e in events)
    return counters[0]-prefix_ends,[int(e['tracker']['ticksSinceLastForcedTeleport'])for e in seeds]

def server_snapshot_clocks(events,clock):
    """Anchor wall-only snapshots to their preceding hook, using file order.

    Agent inserts ServerReplicationTrace.clock(tick_end), then Referee.tick.
    The former flushes its clock rows before the latter appends the snapshot.
    No world/task step occurs between those calls in frozen active windows;
    Referee administrative interventions still remain comparison mismatches.
    A hook is consumed once;
    missing hooks (including an extra finish snapshot) are unordered, not fitted.
    """
    anchors={};pending=None
    for index,e in enumerate(events):
        if e['type']=='replication_server_clock':
            if e['phase']=='tick_start':pending=None
            elif e['phase']=='tick_end':pending=e
        elif e['type']=='tick':
            if pending is not None:
                anchors[index]=clock(pending)
                pending=None
    return anchors

def network_schedule(mid,data,game,offset,t0,seconds,service_horizon=None):
    """Packet values assert predictions; only delivery times enter simulated state."""
    server=archive(mid,'server');records=[];applies=[]
    ids={e['tracker']['player']:int(e['tracker']['entity_id'])for e in data['server']if e['type']=='replication_tracker_before'}
    def ms(ns):return ns/1e6-offset-t0
    for side in ('a','b'):
        client=archive(mid,side);player=game[side];other=game['b'if side=='a'else'a']
        handlers={e['data']['packet_id']:e['monotonic_ns']for e in client if e['type']=='packet_handler_enter'and e['data'].get('main_thread')}
        inputs=collections.defaultdict(collections.deque)
        for event in sorted((e for e in data['server']if e['type']=='replication_server_input'and e.get('player')==player),key=lambda e:e['monotonic_ns']):
            try:inputs[input_signature(event)].append(event)
            except ValueError:pass
        sent,received=wire(client,'out'),wire(server,'in',player)
        pairs,_,_=pair_payloads(sent,received)
        for send,receive in pairs:
            t=ms(send['ns']);q=send['packet'];pid=q['id']
            if not 0<=t<seconds*1000:continue
            x=y=z=vx=vy=vz=float('nan');flag=-1
            if pid in (3,4,5,6):
                kind=0 if side=='a'else 1
                if q['moving']:x,y,z=q['x'],q['y'],q['z']
                if q['rotating']:vx,vy=q['yaw'],q['pitch']
                flag=int(q['onGround'])
            elif pid==2:
                if q['action']!=1:continue
                kind=3 if side=='a'else 7
            elif pid==11:
                if q['action']not in (3,4):continue
                kind=2 if side=='a'else 9;flag=int(q['action']==3)
            queue=inputs[signature(q)]
            while queue and queue[0]['monotonic_ns']<receive['ns']:queue.popleft()
            if not queue and service_horizon is not None and ms(receive['ns'])>=service_horizon*1000:
                # Verify its generated payload. A packet still in transit at the cutoff
                # cannot affect any state being compared, even if the referee stops tracing.
                records.append((kind,t,'Infinity',x,y,vx,vy,flag,z,vz,pid))
                continue
            if not queue:raise ValueError(('Missing server task service',side,q,t))
            delivery=ms(queue.popleft()['monotonic_ns'])
            records.append((kind,t,delivery,x,y,vx,vy,flag,z,vz,pid));applies.append((delivery,'SERVER_APPLY',[],None))
        sent,received=wire(server,'out',player),wire(client,'in')
        pairs,_,_=pair_payloads(sent,received)
        target=[0.,4.,0.]
        # Remote starts at the other player's independently recorded initial position.
        initial=next(e for e in data['b'if side=='a'else'a']if e['type']=='science_move')['before']
        target=[initial['pos'+axis] for axis in 'XYZ']
        for send,receive in pairs:
            t=ms(send['ns']);q=send['packet'];pid=q['id']
            if not 0<=t<seconds*1000:continue
            x=y=z=vx=vy=vz=float('nan');flag=-1;kind=None
            if pid==0x12 and q['entity_id']==ids[player]:
                kind=8 if side=='a'else 4;vx,vy,vz=q['velocity']
            elif pid in (0x14,0x15,0x16,0x17,0x18)and q['entity_id']==ids[other]:
                if 'position'in q:target=q['position']
                if 'delta'in q:target=[target[i]+q['delta'][i]for i in range(3)]
                kind=5 if side=='a'else 6;x,y,z=target
                if'onGround'in q:flag=int(q['onGround'])
                if'yaw'in q:vx,vy=q['yaw'],q['pitch']
            elif pid==0x1a and q['entity_id']in(ids[player],ids[other]):
                own=q['entity_id']==ids[player];kind=(14 if own else 12)if side=='a'else(15 if own else 13);x=q['status']
            elif pid==0x1c and q['entity_id']==ids[player]and'sprinting'in q:
                kind=10 if side=='a'else 11;flag=int(q['sprinting'])
            if kind is None:continue
            if receive['packet_id']not in handlers:raise ValueError(('Missing main handler',side,receive))
            delivery=ms(handlers[receive['packet_id']]);records.append((kind,t,delivery,x,y,vx,vy,flag,z,vz,pid))
            applies.append((delivery,'APPLY'+side.upper(),[],None))
    return records,applies

def run(mid,classes,frame_drain=False,seconds=4,transport_trace=False):
    game=json.loads((ROOT/'runtime/results'/f'{mid}.json').read_text());t0=game['start']
    profile=game.get('combat_profile',{});combat=[profile.get('kb_horizontal',1.),profile.get('kb_vertical',1.),profile.get('fresh_hit_ticks',10),profile.get('sprint_vertical',.1)]
    data={side:load(mid,side)for side in ('a','b','server')}
    offsets=[e['monotonic_ns']/1e6-e['time'] for es in data.values()for e in es if 'monotonic_ns'in e and 'time'in e]
    offset=statistics.median(offsets)
    def clock(e):return e['monotonic_ns']/1e6-offset-t0 if 'monotonic_ns'in e else e['time']-t0
    moves={side:[e for e in data[side]if e['type']=='science_move']for side in ('a','b')}
    requested_seconds=seconds
    unsupported=[] # Entity pushing and degenerate KB remain declared unsupported transitions.
    ax,bx=(moves[side][0]['before']['posX']for side in ('a','b'))
    sumo=game['mode']=='sumo'
    # Native referee cleanup changes the arena and controls after a ring-out.
    if sumo:seconds=min(seconds,(game['finish']-t0)/1000-.10)
    if seconds<=0:raise ValueError('Insufficient active fight before teardown')
    top=game['sumo_arena']['top_y'] if sumo else 4
    assert all(moves[side][0]['before']['posY']==top and moves[side][0]['before']['motionX']==0 for side in ('a','b'))
    tracker,teleport_ages=initial_tracker_state(data['server'],game,clock)
    rows=[];expected=[];report_seeds=[]
    initial_tick=next(e for e in data['server']if e['type']=='tick'and e['time']>=t0)
    assert not any(e['type']=='science_server_attack'and t0<=e['time']<=initial_tick['time']for e in data['server'])
    initial_damage=[initial_tick[side]['lastDamage']for side in ('a','b')]
    for side in ('a','b'):
        es=data[side];suffix=side.upper()
        raw=archive(mid,side)
        wire_moves=[r for r in wire(raw,'out') if r['packet']['id'] in (3,4,5,6)]
        first_move=moves[side][0]
        prior=[r['packet'] for r in wire_moves if r['ns']<first_move['monotonic_ns']]
        # A tick can move just before match start and serialize its C03 just after.
        # Recreate that pending packet from the recorded input and the settled body.
        first_send=next((r for r in wire_moves if r['ns']>=first_move['monotonic_ns']),None)
        straddles_start=(transport_trace and clock(first_move)<0 and first_send is not None and
                         first_send['ns']/1e6-offset>=t0 and
                         (len(moves[side])==1 or first_send['ns']<moves[side][1]['monotonic_ns']))
        if not prior or not any(q['moving']for q in prior):raise ValueError(('Missing initial C03 history',side))
        last_move=max(i for i,q in enumerate(prior)if q['moving']);q=prior[last_move]
        look=next((p for p in reversed(prior)if p['rotating']),{'yaw':-90 if side=='a'else 90,'pitch':0})
        report_seeds.extend([len(prior)-last_move-1,q['x'],q['y'],q['z'],look['yaw'],look['pitch']])
        starts={e['client_tick']:e for e in raw if e['type']=='tick_start'}
        ends={e['client_tick']:e for e in raw if e['type']=='tick_end'}
        # Recorded ordinary control decisions, not observed sprint state: W
        # without Ctrl is key1 and must not silently replay as W+Ctrl (key2).
        # Historical fixtures only exposed W+Ctrl/coast; newer policy traces
        # provide the actual key for every input tick, including acquisition.
        explicit_keys={e['native_tick']:e['key'] for e in es
                       if e['type'] in ('gap_strafe_plan','frozen_cycle_plan','tick_cycle_plan','hurt_window_coast_plan')}
        explicit_keys.update({e['tick']:e['action']%4 for e in es if e['type']=='duel_network'})
        jumps={e['native_tick'] for e in es if e['type'] in ('sumo_rl_jump','jump_reset_input','duel_network_jump')}
        for move in moves[side]:
            if not (0<=clock(move)<seconds*1000 or (move is first_move and straddles_start)):continue
            tick=move['tick'];q=move['before']
            key=explicit_keys.get(tick,3 if move['forward']<0 else 2 if move['forward'] else 0)
            if (key!=0)!=bool(move['forward']) or (key==3)!=(move['forward']<0):
                raise ValueError(('Recorded control/movement disagreement',side,tick,key,move['forward']))
            clicks=[e for e in es if e['type']=='science_click' and e['tick']==tick]
            interp=[e for e in es if e['type']=='science_interpolation' and e['tick']==tick]
            input_t=min([clock(move)]+[clock(e)for e in interp])-0.001
            if tick in starts:input_t=clock(starts[tick])
            if clicks:
                for click in clicks:
                    input_t=(click['monotonic_ns']if transport_trace else click.get('monotonic_end_ns',click['monotonic_ns']))/1e6-offset-t0
                    look=click['own'];rows.append((input_t,f'INPUT{suffix}',[key,look['rotationYaw'],look['rotationPitch'],1],click))
            else:rows.append((input_t,f'INPUT{suffix}',[key,q['rotationYaw'],q['rotationPitch'],0],None))
            rows.append((clock(move),f'MOVE{suffix}',[key,q['rotationYaw'],move['strafe'],int(tick in jumps)],move))
            if tick not in ends:raise ValueError(('Missing native tick_end',side,tick))
            rows.append((clock(ends[tick]),f'TICKEND{suffix}',[],None))
        for e in es:
            if not 'time'in e or not 0<=clock(e)<seconds*1000:continue
            if e['type']=='science_interpolation':rows.append((clock(e),f'INTERP{suffix}',[],e))
            elif frame_drain and e['type']=='replication_frame':rows.append((clock(e),f'FRAME{suffix}',[],None))
    exact_clocks=any(e['type']=='replication_server_clock'for e in data['server'])
    snapshot_clocks=server_snapshot_clocks(data['server'],clock)if exact_clocks else {}
    snapshot_ordering={'method':'preceding_tick_end_file_order'if exact_clocks else'legacy_wall_proxy',
                       'anchored':0,'unordered':0,'unordered_wall_times_ms':[]}
    for index,e in enumerate(data['server']):
        if 'time'not in e and 'monotonic_ns'not in e:continue
        event_time=clock(e)
        if e['type']=='tick'and exact_clocks:
            if index not in snapshot_clocks:
                if 0<=event_time<seconds*1000:
                    snapshot_ordering['unordered']+=1
                    snapshot_ordering['unordered_wall_times_ms'].append(event_time)
                continue
            event_time=snapshot_clocks[index]
        if not 0<=event_time<seconds*1000:continue
        if e['type']=='replication_server_clock':
            phase=e['phase'];kind=None
            if phase=='drain_start':kind='SERVER_DRAIN'
            elif phase=='entity_tick_start'and e['world_ordinal']==0:kind='SERVER_ENTITY'
            elif phase=='tracker_tick_start'and e['world_ordinal']==0:kind='SERVER_TRACK'
            elif phase=='tick_end':kind='SERVER_END'
            if kind:rows.append((event_time,kind,[],None))
        elif e['type']=='tick':
            rows.append((event_time,'SERVER_OBSERVE'if exact_clocks else'SERVER',[],e))
            if exact_clocks:snapshot_ordering['anchored']+=1
    network=[]
    if transport_trace:
        # A move callback can generate a packet immediately before the cutoff
        # whose native serializer runs after it. Include one tick of send-side
        # assertions; only handlers strictly inside the requested horizon run.
        network,applies=network_schedule(mid,data,game,offset,t0,seconds+(.05 if sumo else 0),seconds)
        # Pending packets can arrive after the requested cutoff. Replaying
        # those handlers would generate new packets outside the captured send
        # horizon, falsely reporting NETWORK_EXTRA at the truncated tail.
        rows.extend(row for row in applies if row[0]<seconds*1000)
    rows.sort(key=lambda r:r[0])
    kb=profile.get('knockback_model','vanilla')
    if kb not in ('mmc_pre_attack_sprint_v2','mmc_recorded_sumo_v3','mmc_recorded_range_v4','vanilla','vanilla_sprint_components') and 'mmc' in kb:
        raise ValueError('Unsupported historical knockback model: '+kb)
    first_tracker=next(e['tracker']['player'] for e in data['server'] if e['type']=='replication_tracker_before'
                       and e['tracker']['player'] in (game['a'],game['b']) and clock(e)>=0)
    arena=[int(sumo),top,(game['arena']+1)*4096,{'mmc_pre_attack_sprint_v2':1,'mmc_recorded_sumo_v3':2,'mmc_recorded_range_v4':3}.get(kb,0),int(first_tracker==game['a'])]
    # The recorded 13-block MMC floor starts both players at z = 0.5.
    arena.extend([game['sumo_arena'].get('diameter',10) if sumo else 10,
                  *[moves[side][0]['before']['posZ'] for side in ('a','b')]])
    lines=['\t'.join(map(str,['INIT',ax,bx,game['ping_a'],game['ping_b'],game['attack_reach_b'],tracker,*report_seeds,*initial_damage,*teleport_ages,*combat,*arena]))]
    lines=['\t'.join(map(lambda x:'NaN'if isinstance(x,float)and x!=x else str(x),['NETWORK',*r]))for r in network]+lines
    for t,k,args,e in rows:
        lines.append('\t'.join(map(str,[t,k,*args])))
        if not k.startswith('FRAME'):expected.append((t,k,e))
    cmd=[str(JAVA),'-Xmx256m',f'-Dperiodic.frame_drain={str(frame_drain).lower()}','-cp',classes,'PeriodicComboReplay']
    p=subprocess.run(cmd,input='\n'.join(lines)+'\n',capture_output=True,text=True)
    predictions=list(csv.DictReader(io.StringIO(p.stdout),delimiter='\t'));assert len(predictions)<=len(expected)
    replay_failure=p.stderr.strip()if p.returncode else None
    scored=[]
    for pred,(t,k,e)in zip(predictions,expected):
        if e is None:continue
        row={'t_ms':t,'event':'SERVER'if k=='SERVER_OBSERVE'else k};pairs={}
        if k.startswith('MOVE'):
            side=k[-1].lower();q=e['after'];pairs={f'{side}_{v}':q['pos'+v.upper()]for v in ('x','y','z')};pairs.update({f'{side}_v{v}':q['motion'+v.upper()]for v in ('x','y','z')});pairs.update({f'{side}_ground':int(q['ground']),f'{side}_sprint':int(q['sprint']),f'{side}_head_yaw':q['rotationYawHead']})
        elif k.startswith('INTERP'):
            side=k[-1].lower();q=e['after'];pairs={f'{side}_view_{v}':q['pos'+v.upper()]for v in ('x','y','z')}
        elif k in ('SERVER','SERVER_OBSERVE'):
            for side in ('a','b'):
                for v in ('x','y','z'):
                    pairs[f'server_{side}{v}']=e[side]['pos'+v.upper()];pairs[f'server_{side}_v{v}']=e[side]['motion'+v.upper()]
                q=e[side];pairs.update({f'server_{side}_h':q['hurtResistantTime'],f'server_{side}_fall':q['fallDistance'],f'server_{side}_last_damage':q['lastDamage'],f'server_{side}_sprint':int(q['sprint'])})
            pairs['accepted_a']=e['hits_a'];pairs['accepted_b']=e['hits_b']
        elif k.startswith('INPUT'):
            side=k[-1].lower();q=e['own'];target=e['target']
            pairs={f'{side}_{v}':q['pos'+v.upper()]for v in ('x','y','z')};pairs.update({f'{side}_view_{v}':target['pos'+v.upper()]for v in ('x','y','z')})
            contact=int(float(pred[side+'_ray'])<=(3 if side=='a'else game['attack_reach_b']));pred['ray_selected']=contact;pairs['ray_selected']=int(e['selected_target'])
            # Independent geometry from native recorded boxes/raycast point.
            # These are assertions only. Floating-point expression order differs
            # from the compact model formula, so report numeric errors separately
            # from the exact state/packet bit checks.
            f32=lambda value:struct.unpack('>f',struct.pack('>f',value))[0]
            eye=[q['posX'],q['posY']+f32(1.62),q['posZ']];box=target['box'];border=f32(.1)
            native_nearest=math.sqrt(sum(max(0,box[i]-border-eye[i],eye[i]-box[i+3]-border)**2 for i in range(3)))
            ranges={'nearest':{'predicted':float(pred['own_range'if side=='a'else'defender_range']),'native':native_nearest}}
            if e['selected_target'] and e.get('ray_point'):
                inside=all(box[i]-border<eye[i]<box[i+3]+border for i in range(3))
                # EntityRenderer assigns selection distance zero inside the box;
                # hitVec can still point to the far face and is not that distance.
                native_ray=0.0 if inside else math.sqrt(sum((eye[i]-e['ray_point'][i])**2 for i in range(3)))
                ranges['selected_ray']={'predicted':float(pred[side+'_ray']),'native':native_ray}
            for value in ranges.values():value['absolute_error']=abs(value['predicted']-value['native'])
            row['range_checks']=ranges
        row['predicted']={key:float(pred[key])for key in pairs};row['native']=pairs;row['absolute_error']={key:abs(float(pred[key])-value)for key,value in pairs.items()};row['bit_mismatches']=[key for key,value in pairs.items()if struct.pack('>d',float(pred[key]))!=struct.pack('>d',float(value))]
        scored.append(row)
    summary={}
    for category in ('MOVEA','MOVEB','INTERPA','INTERPB','INPUTA','INPUTB','SERVER'):
        selected=[e for e in scored if e['event']==category]
        errors=[max(e['absolute_error'].values(),default=0)for e in selected]
        failures=[e for e in selected if max(e['absolute_error'].values(),default=0)>.001]
        bits=[e for e in selected if e['bit_mismatches']]
        summary[category]={'n':len(selected),'bit_mismatch_rows':len(bits),'first_bit_mismatch':bits[0]if bits else None,'max_component_error':max(errors,default=0),'first_error_over_001':failures[0]if failures else None}
    range_summary={}
    for metric in ('nearest','selected_ray'):
        errors=sorted(row['range_checks'][metric]['absolute_error'] for row in scored if metric in row.get('range_checks',{}))
        range_summary[metric]={'n':len(errors),'max_absolute_error_blocks':max(errors,default=0),'p95_absolute_error_blocks':errors[min(len(errors)-1,int(.95*len(errors)))]if errors else None}
    return {'range_summary':range_summary,'match':mid,'combat_profile':combat,'rtt':[game['ping_a'],game['ping_b']],'seconds':seconds,'requested_seconds':requested_seconds,'unsupported':unsupported,'frame_drain':frame_drain,'exact_server_clocks':exact_clocks,'server_snapshot_ordering':snapshot_ordering,'transport_trace':transport_trace,'replay_failure':replay_failure,
            'initial_positions':[ax,bx],'initial_tracker_counter':tracker,'initial_teleport_ages':teleport_ages,'initial_report_state_from_prior_wire':report_seeds,'initial_last_damage_from_first_precontact_snapshot':initial_damage,
            'scope':'Recorded W/A/D/Ctrl/yaw/click inputs plus raw tick_start/end, local move/interpolation and server-tick clocks. Initial settled positions only; all later native states withheld. Transport mode uses click call-start plus measured packet dispatch/delivery schedules; other mode uses call-end and tick-end emission proxies. Exact server drain/entity/tracker hooks are used when present; wall-only snapshots are compared immediately after their one-to-one preceding tick_end hook in original file order, with unanchored snapshots explicitly unscored. Without exact hooks, server tick logs remain end-of-tick proxies. Initial tracker counters and teleport ages use the same monotonic-derived zero boundary as replay events. Critical upgrades, reported fall state, native server pushes and 2D head-ray are modeled. Degenerate random KB is an explicit unsupported stop. All generated relevant packets are asserted by subtype and represented payload bits; initial C03 counters are reconstructed only from pre-start wire history. Initial lastDamage is taken from the first pre-contact server snapshot, with no preceding attack. Metadata checks cover own sprint flags, not unrelated watcher entries. The first divergence ends strict transport replay; requested/unexecuted tails are counted.',
            'completed_event_rows':len(predictions),'requested_event_rows':len(expected),'unexecuted_event_rows':len(expected)-len(predictions),'last_compared_ms':scored[-1]['t_ms']if scored else None,'summary':summary,'rows':scored}

def main():
    ap=argparse.ArgumentParser();ap.add_argument('matches',nargs='*',default=['m1187']);ap.add_argument('--frames',action='store_true');ap.add_argument('--transport-trace',action='store_true');ap.add_argument('--seconds',type=float,default=4);ap.add_argument('--output',type=Path,help='Separate evidence destination; default preserves the historical naming convention');a=ap.parse_args()
    source_hashes={s:hashlib.sha256((ROOT/s).read_bytes()).hexdigest()for s in SOURCES}
    with tempfile.TemporaryDirectory(prefix='periodic-replay-')as temp:
        subprocess.run([str(JAVA.with_name('javac')),'-proc:none','-d',temp,*[str(ROOT/s)for s in SOURCES]],capture_output=True,check=True)
        reports=[run(mid,temp,a.frames,a.seconds,a.transport_trace)for mid in a.matches]
    output={'sources':source_hashes,'reports':reports}
    name='research/periodic-clock-replay'+('-frames'if a.frames else '')+('-transport'if a.transport_trace else '')+'-'+ '-'.join(a.matches)+'.json'
    destination=a.output if a.output is not None else ROOT/name
    destination.parent.mkdir(parents=True,exist_ok=True)
    destination.write_text(json.dumps(output,indent=2)+'\n')
    for d in reports:
        if d['replay_failure']:print(d['match'],d['replay_failure'].splitlines()[0])
        print(d['match'],[(k,round(v['first_error_over_001']['t_ms'],3)if v['first_error_over_001']else None,round(v['max_component_error'],4))for k,v in d['summary'].items()])

if __name__=='__main__':main()
