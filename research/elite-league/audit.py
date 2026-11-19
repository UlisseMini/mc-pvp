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

sys.path.insert(0,str(ROOT/'research/sumo-rl'))
import captures
from score_runs import native

def bindings(mid,side,weights,start,end):
    path,=list((ROOT/'runtime/pov'/mid).glob(side+'-client-*-events.jsonl.gz'))
    events=[e for e in captures.events(path) if e.get('match')==mid]
    decisions=[e for e in events if e['type']=='duel_network']
    if not decisions:raise ValueError(f'{mid}/{side}: missing network decisions')
    ticks=[e['tick'] for e in decisions]
    if ticks!=sorted(set(ticks)):raise ValueError(f'{mid}: out-of-order policy heads')
    query=['@'+weights]
    by_tick={};actions=Counter();released=[];mismatch=[];checked=0
    for d in decisions:
        obs=d['observation'];action=d['action']
        query.append(' '.join(map(str,[*obs[:9],int(obs[9]),int(obs[10]),*obs[11:],d['proposed'],action])))
        if d['released'] and action!=d['proposed']:raise ValueError('Released action replaced')
        if start<=d['time']<=end:
            by_tick[d['tick']]=action;actions[action]+=1
            if d['released']:released.append(d['time']-start)
    result=subprocess.run(['java','-Xms12m','-Xmx48m','-XX:+UseSerialGC','-XX:ActiveProcessorCount=1','-cp',str(class_path()),'NetworkChecks','replay'],
        input='\n'.join(query)+'\n',text=True,capture_output=True)
    if result.returncode or int(result.stdout)!=len(decisions):raise ValueError((mid,'network replay',result.stderr))
    jumps={e['native_tick'] for e in events if e['type']=='duel_network_jump'}
    for e in events:
        if e['type']!='science_move' or e['tick'] not in by_tick or not start<=e['time']<=end:continue
        action=by_tick[e['tick']];key=action%4;side_key=action//4%3-1
        forward=-1 if key==3 else int(key!=0)
        if not math.isclose(e['forward'],.98*forward,abs_tol=1e-6) or not math.isclose(e['strafe'],side_key*.98,abs_tol=1e-6) or (action>=24)!=(e['tick'] in jumps):
            mismatch.append([e['tick'],key,side_key,action>=24,e['forward'],e['strafe'],e['tick'] in jumps])
        checked+=1
    return dict(replayed=len(decisions),active_heads=sum(actions.values()),verified_move_heads=checked,mismatches=mismatch,
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
            checks[side]=bindings(mid,side,props['duel_network'],game['start'],game['finish'])
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
