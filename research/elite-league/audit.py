"""Re-score live matches, verify frozen weights, replay every neural input head and compare emitted movement keys."""
import argparse
from collections import Counter
import fcntl
import json
import math
from pathlib import Path
import subprocess
import sys
import time
from run import ROOT,HERE,class_path

paths=list(sys.path)
try:
    sys.path.insert(0,str(ROOT/'research/sumo-rl'))
    import captures
    from score_runs import native,MouseAudit
finally:
    sys.path[:]=paths

def continuation_actions(events, parameters, context):
    threshold = int(parameters.get('duel_network_wtap_after', 0))
    if not threshold:
        return {}
    if (parameters.get('kind') != 'wtap' or parameters.get('sprint_mode', 'hold') != 'hold'
            or float(parameters.get('reaction_ms', 0)) != 0):
        raise ValueError(f'{context}: unsupported recorded W-tap fallback settings')
    gap = 1100
    pause = int(float(parameters.get('pause_ms', 50)))
    last_own = last_target = release_until = -math.inf
    unanswered = 0
    result = {}
    for event in events:
        if event['type'] == 'wtap':
            release_until = event['release_until']
            if release_until != event['time'] + pause:
                raise ValueError(f'{context}: unexpected W-tap release duration')
        elif event['type'] == 'duel_network':
            now = event['time']
            own_age, target_age = event['observation'][11:13]
            own = now - own_age if own_age >= 0 else -math.inf
            target = now - target_age if target_age >= 0 else -math.inf
            if own > last_own:
                last_own = own
                unanswered = 0
            if target > last_target:
                if target - last_target > gap:
                    unanswered = 0
                last_target = target
                if last_target > last_own:
                    unanswered += 1
            continuation = unanswered >= threshold and now - last_target <= gap
            if event.get('wtap_continuation') is not continuation:
                raise ValueError(f"{context}: continuation differs at tick {event['tick']}")
            if continuation or not event['released']:
                result[event['tick']] = 16 if now < release_until else 18
    return result

def bindings(mid,side,weights,start,end,parameters=None):
    parameters = parameters or {}
    context = f'{mid}/{side}'
    path,=list((ROOT/'runtime/pov'/mid).glob(side+'-client-*-events.jsonl.gz'))
    events=[e for e in captures.events(path,{'duel_network','duel_network_jump','science_move','wtap','duel_hud_ping','duel_local_timing'}) if e.get('match')==mid]
    decisions=[e for e in events if e['type']=='duel_network']
    if not decisions:raise ValueError(f'{mid}/{side}: missing network decisions')
    ticks=[e['tick'] for e in decisions]
    if ticks!=sorted(set(ticks)):raise ValueError(f'{mid}: out-of-order policy heads')
    fallback = continuation_actions(events, parameters, context)
    has_continuation = int(parameters.get('duel_network_wtap_after', 0)) > 0
    query=['@'+weights]
    by_tick={};actions=Counter();released=[];mismatch=[];checked=0
    experts=Counter();changes=[];previous=None
    fallback_counts=Counter()
    ping_changes = {e['tick']: e['opponent'] for e in events if e['type']=='duel_hud_ping'}
    ping_actor = weights.startswith('PING_MLP.')
    timing = {e['tick']: e for e in events if e['type']=='duel_local_timing'}
    study_actor = weights.startswith(('INPUT8.', 'INPUT32.'))
    if study_actor and set(timing)!=set(ticks):
        raise ValueError(f'{context}: incomplete local timing inputs')
    ping = None
    for d in decisions:
        obs=d['observation'];action=d['action']
        route=[d['expert'],d['switches'],d['selector_score']] if 'expert' in d else []
        if ping_actor:
            ping = ping_changes.get(d['tick'], ping)
            if ping is None: raise ValueError(f'{context}: missing initial HUD ping')
            obs = [*obs,ping]
        if study_actor:
            t = timing[d['tick']]
            age = (t['head_ns']-t['last_press_ns'])/1e6 if t['last_press_ns'] else -1
            obs = [*obs,t['head_ns']/1e6,age]
        query.append(' '.join(map(str,[*obs[:9],int(obs[9]),int(obs[10]),*obs[11:],d['proposed'],action,*route])))
        if d['tick'] in fallback:
            if action != fallback[d['tick']]:
                raise ValueError(f"{context}: incorrect W-tap fallback at tick {d['tick']}")
        elif d['released'] and action!=d['proposed']:
            raise ValueError(f"{context}: released action replaced at tick {d['tick']}")
        if start<=d['time']<=end:
            by_tick[d['tick']]=action;actions[action]+=1
            if has_continuation:
                mode = 'unreleased' if not d['released'] else 'wtap' if d['wtap_continuation'] else 'network'
                fallback_counts[mode] += 1
            if route:
                experts[route[0]]+=1
                if previous is not None and previous!=route[0]:changes.append([d['time']-start,route[0]])
                previous=route[0]
            if d['released']:released.append(d['time']-start)
    result=subprocess.run(['java','-Xms12m','-Xmx48m','-XX:+UseSerialGC','-XX:ActiveProcessorCount=1','-cp',str(class_path()),'NetworkChecks','replay'],
        input='\n'.join(query)+'\n',text=True,capture_output=True)
    if result.returncode or int(result.stdout)!=len(decisions):raise ValueError((mid,'network replay',result.stderr))
    jumps={e['native_tick'] for e in events if e['type']=='duel_network_jump'}
    movement_ticks=set()
    for e in events:
        if e['type']!='science_move' or not start<=e['time']<=end:continue
        tick=e['tick']
        if tick in movement_ticks:raise ValueError(f'Duplicate movement {mid}/{side} at {tick}')
        movement_ticks.add(tick)
        if tick not in by_tick:continue
        action=by_tick[e['tick']];key=action%4;side_key=action//4%3-1
        forward=-1 if key==3 else int(key!=0)
        if not math.isclose(e['forward'],.98*forward,abs_tol=1e-6) or not math.isclose(e['strafe'],side_key*.98,abs_tol=1e-6) or (action>=24)!=(e['tick'] in jumps):
            mismatch.append([e['tick'],key,side_key,action>=24,e['forward'],e['strafe'],e['tick'] in jumps])
        checked+=1
    shared=movement_ticks & by_tick.keys()
    interior=set(range(min(shared),max(shared)+1)) if shared else set()
    coverage=dict(missing_inputs=sorted(interior-by_tick.keys()),missing_moves=sorted(interior-movement_ticks),
                  unpaired_inputs=sorted(by_tick.keys()-movement_ticks),unpaired_moves=sorted(movement_ticks-by_tick.keys()))
    return dict(move_coverage=coverage,**(dict(expert_heads=experts,expert_changes=changes) if experts else {}),
        **(dict(continuation_heads=fallback_counts) if fallback_counts else {}),replayed=len(decisions),active_heads=sum(actions.values()),verified_move_heads=checked,mismatches=mismatch,
        first_released_ms=min(released,default=None),applied_counts=actions,
        forward=sum(v for k,v in actions.items() if k%4 in (1,2)),back=sum(v for k,v in actions.items() if k%4==3),
        strafe=sum(v for k,v in actions.items() if k//4%3!=1),jump=sum(v for k,v in actions.items() if k>=24),
        gate_closed=sum(v for k,v in actions.items() if not k//12%2))

def one(mid,setup):
    game=json.loads((ROOT/f'runtime/results/{mid}.json').read_text());checks={}
    allowed=('ring_out','double_ring_out','timeout_draw') if setup['mode']=='sumo' else ('scenario_completed',)
    if setup.get('match_format')=='first-to-100':allowed=('100_hits','timeout_draw')
    if game['reason'] not in allowed:raise ValueError(f'{mid} end: '+game['reason'])
    for side in 'ab':
        name=game['policy_'+side]
        props=dict(l.split('=',1) for l in (ROOT/'policies'/(name+'.properties')).read_text().splitlines() if '=' in l)
        par=game['parameters_'+side]
        if 'duel_network' in props:
            assert props['duel_network']==par['duel_network']
            checks[side]=bindings(mid,side,props['duel_network'],game['start'],game['finish'],par)
    arm=setup['side']
    summary=native(mid,setup,game['parameters_'+arm].get('duel_network'))
    summary.update(reason=game['reason'],bindings=checks)
    return summary

def collect(manifest,out,watch):
    out.mkdir(exist_ok=True,parents=True)
    while True:
        trial=json.loads(manifest.read_text())
        for req,mid in trial['matches'].items():
            dest=out/(mid+'.json')
            if dest.exists():continue
            try:
                with (ROOT/'build/elite-league/replay-scoring.lock').open('a') as lock:
                    fcntl.flock(lock.fileno(),fcntl.LOCK_EX)
                    summary=one(mid,trial['plan'][req])
            except (FileNotFoundError,EOFError,ValueError) as exc:
                result=json.loads((ROOT/f'runtime/results/{mid}.json').read_text())
                if not watch or time.time()-result['finish']/1000>60:raise
                continue
            pending=dest.with_suffix('.tmp')
            pending.write_text(json.dumps(summary,separators=(',',':')));pending.replace(dest)
        if not watch or (trial['matches'].keys()==trial['plan'].keys() and all((out/(mid+'.json')).exists() for mid in trial['matches'].values())):return
        time.sleep(5)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('manifest',type=Path);p.add_argument('out',type=Path);p.add_argument('--watch',action='store_true')
    a=p.parse_args();collect(a.manifest,a.out,a.watch)
