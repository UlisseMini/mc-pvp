"""Audit registered native arms, emitted physical inputs and exposed combo windows."""
import argparse, collections, gzip, json, math, statistics, sys, time
from pathlib import Path

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
sys.path.insert(0,str(ROOT/'research/recoil-drill'))
import score
import captures

def native(mid,setup,network=None):
    g=score.record(ROOT/f'runtime/results/{mid}.json')
    side=setup['side'];other='b' if side=='a' else 'a';start=g['start'];end=g['finish']
    assert g['policy_'+side]==setup['arm'] and g['policy_'+other]==setup['opponent']
    assert g['mode']==setup['mode'] and g['practice_preset']==setup['profile']
    assert g['combat_profile']['fresh_hit_ticks']==9
    assert g['duration_limit_ms']==(120000 if g['mode']=='sumo' else 20000)
    if g['mode']=='sumo':assert g['sumo_arena']['diameter']==10 and g['sumo_arena']['start_gap']==6
    if setup['profile']=='mmc':assert g['combat_profile']['knockback_model']=='mmc_pre_attack_sprint_v2'
    p=g['parameters_'+side]
    expected=0 if network is not None or setup['arm'] in ('research-wtap20','research-hold20') else score.record(HERE/{'sumo-rl-jump':'v1/fit.json','sumo-rl':'fit.json','sumo-rl-pressure':'league-fit.json'}[setup['arm']])['mask']
    assert int(p.get('sumo_jump_mask',0))==expected
    if network is not None:
        assert p['duel_network']==network and p['kind']==('wtap' if g['mode']=='boxing' else 'hold')
        assert int(p.get('duel_network_landmark',0))==(3 if g['mode']=='boxing' else 0)
    elif setup['arm']=='sumo-rl-pressure':
        fit=score.record(HERE/'league-fit.json')
        assert p['kind']=={'hold':'hold','wtap':'wtap','trade':'tradetap'}[fit['style']]
        if fit['style']=='trade':assert int(p['trade_window_ms'])==fit['window'] and int(p['reply_wait_ms'])==fit['wait']
    else:assert p['kind']==('hold' if setup['arm']=='research-hold20' else 'wtap')
    hashes={};streams={};starts={};press={};jumps=[];takes=set();moves={};sprint_bonus=collections.Counter()
    for s in 'ab':
        par=g['parameters_'+s];assert g['click_cps_'+s]==20 and g['attack_reach_'+s]==3 and g['reaction_ms_'+s]==0
        assert g['initial_ping_'+s]==(setup['rtt'] if s==side else setup.get('opponent_rtt',setup['rtt'])) and g['client_'+s]['backend']=='forge'
        assert all(float(par[k])==0 for k in ('aim_noise_deg','aim_delay_ms','aim_turn_rate_dps'))
        assert int(par.get('click_start_ms',0))==(2000 if g['mode']=='boxing' and s==other else 0)
        hashes[s]=[g['client_'+s][k] for k in ('bridge_sha256','module_sha256','headless_sha256','java_sha256')]
        files=list((ROOT/f'runtime/pov/{mid}').glob(s+'-client-*-events.jsonl.gz'))
        if len(files)!=1:raise ValueError(f'{mid} has {len(files)} archives on {s}')
        keep=[];footer=None
        for e in captures.events(files[0]):
            typ=e['type']
            if typ=='capture_end':footer=e
            if not start<=e.get('time',0)<=end:continue
            if typ in ('click','science_click','engine_attack','velocity_packet','reach_probe'):keep.append(e)
            if s==side:
                if typ=='sumo_rl_jump':
                    assert expected & (1<<e['state']);jumps.append([e['native_tick'],e['state'],e['time']-start])
                if typ=='duel_network_jump':jumps.append([e['native_tick'],-1,e['time']-start])
                if typ=='science_move':
                    moves[e['tick']]=e
                    if e['before']['ground'] and e['before']['motionY']==0.41999998688697815:takes.add(e['tick'])
        assert footer and footer['writer_complete'] and not any(footer[k] for k in ('dropped','size_capped','continues'))
        clicks=[e for e in keep if e['type']=='click'];starts[s]=clicks[0]['time'] if clicks else None;streams[s]=keep
        intervals=[(b['monotonic_ns']-a['monotonic_ns'])/1e6 for a,b in zip(clicks,clicks[1:])]
        assert not intervals or min(intervals)>=50
        press[s]=[len(clicks),min(intervals,default=None)]
    assert hashes['a']==hashes['b']
    raw=[e for e in score.events(ROOT/f'runtime/replays/{mid}.jsonl') if e['type'] in
         ('hit','tick','replication_server_input','science_server_attack','mmc_knockback')]
    hs=[(e['time'],e['attacker']!=g[side],e['damage_upgrade']) for e in raw if e['type']=='hit' and not e['rod'] and start<=e['time']<=end]
    assert sum(not ret for t,ret,u in hs)==g['hits_'+side] and sum(ret for t,ret,u in hs)==g['hits_'+other]
    active=max(starts.values()) if all(t is not None for t in starts.values()) else end+1
    snaps=[e for e in raw if e['type']=='tick' and start<=e['time']<=end]
    initial=next((e for e in reversed(snaps) if e['time']<=active),None)
    def geometry(a,b):
        return [math.hypot(a['posX']-b['posX'],a['posZ']-b['posZ']),a['posY'],b['posY'],a['sprint'],b['sprint']]
    landmarks=[];streak=0
    for e in raw:
        if e['type']!='hit' or e['rod'] or e['time']<active:continue
        if e['attacker']!=g[side]:streak=0
        elif not e['damage_upgrade']:
            streak+=1
            if streak in (3,5,10):landmarks.append([streak,e['time']-start,e['server_tick'],*geometry(e['attacker_state'],e['target_state'])])
    if setup['profile']=='mmc':
        fresh=[e for e in raw if e['type']=='science_server_attack' and e['score_before']!=e['score_after'] and
               e['target_before']['hurtResistantTime']<=9]
        impulses=[e for e in raw if e['type']=='mmc_knockback']
        for h,k in zip(fresh,impulses,strict=True):
            source=h['attacker_before'];victim=h['target_before'];bonus=int(source['sprint'])
            assert h['target']==k['target'] and k['extra_levels']==bonus and k['pre_attack_server_sprint']==bool(bonus)
            yaw=math.radians(source['rotationYaw']);f=(-math.sin(yaw),math.cos(yaw))
            delta=(victim['posX']-source['posX'],victim['posZ']-source['posZ']);l=math.hypot(*delta)
            unit=f if l<.01 else [v/l for v in delta]
            assert math.dist([.5274*u+.327*v*bonus for u,v in zip(unit,f)],k['velocity'][::2])<1e-9
            assert [int(v*8000)/8000 for v in k['velocity']]==h['velocity_packet']
            sprint_bonus['all_fresh']+=1
            if h['attacker']==g[side]:sprint_bonus['own_fresh']+=1;sprint_bonus['own_sprint']+=bonus
    try:
        w=score.analyze_events(g,side,raw,streams[side],streams[other])
        windows=dict(scored=len(w['windows']),definitely_over_100ms=sum(x['window_to_next_hit_delay_ms'][0]>100 for x in w['windows']),
                     terminal=w['terminal_unfinished_window'],readiness=w['readiness_class_counts'])
    except ValueError as e:windows={'unknown':str(e)}
    return dict(mid=mid,**{k:v for k,v in setup.items() if k!='command'},win=g['winner']==g[side],draw=not g['winner'],
        wake_ms=active-start if active<=end else None,wake_snapshot=geometry(initial[side],initial[other]) if initial and active<=end else None,
        max_server_gap_ms=max((b['time']-a['time'] for a,b in zip(snaps,snaps[1:])),default=None),
        margin=g['hits_'+side]-g['hits_'+other],duration_ms=end-start,build=hashes['a'],press=press,jump_inputs=jumps,
        confirmed_takeoffs=sum(k in takes for k,_,_ in jumps),unmatched_takeoffs=sorted(takes-{j[0] for j in jumps}),
        maintenance=score.maintenance(hs,active,end,9),landmark_states=landmarks,windows=windows,
        contact_braking=score.audit_motion(streams[side])['counts'],sprint_bonus=sprint_bonus)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('manifest',type=Path);p.add_argument('out',type=Path)
    p.add_argument('--follow',action='store_true');a=p.parse_args();a.out.mkdir(exist_ok=True,parents=True)
    while True:
        m=score.record(a.manifest)
        for req,mid in m['matches'].items():
            dest=a.out/(mid+'.json')
            if dest.exists():continue
            try:r=native(mid,m['plan'][req])
            except (EOFError,ValueError) as exc:
                if not a.follow or time.time()-score.record(ROOT/f'runtime/results/{mid}.json')['finish']/1000>30:raise
                continue
            tmp=dest.with_suffix('.tmp');tmp.write_text(json.dumps(r,separators=(',',':')));tmp.replace(dest)
        if not a.follow or all((a.out/(mid+'.json')).exists() for mid in m['matches'].values()) and m['matches'].keys()==m['plan'].keys():break
        time.sleep(2)
