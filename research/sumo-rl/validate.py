"""Compare the sumo travel kernel to native 1.8.9 and check the first divergent step of each rollout."""
import argparse
import captures
import json
from pathlib import Path
import subprocess
from run import ROOT, compile_model, java

def state(s):
    return [*[s[p+a] for p in ('pos','motion') for a in 'XYZ'],*s['box'],
            *[s[k] for k in ('ground','isCollidedHorizontally','isCollidedVertically','isCollided')]]
def client(mid,side,sprint_packets=False):
    g=json.loads((ROOT/f'runtime/results/{mid}.json').read_text())
    with (ROOT/f'runtime/replays/{mid}.jsonl').open() as f:
        ids={e['tracker']['entity_id'] for line in f if (e:=json.loads(line))['type']=='replication_tracker_before'
             and e['tracker']['player']==g[side]}
    if len(ids)!=1:raise ValueError((mid,side,'velocity ID',ids))
    path=next((ROOT/f'runtime/pov/{mid}').glob(f'{side}-client-*-events.jsonl.gz'))
    rows=[];jump=set();network_keys={};metadata={}
    for e in captures.events(path):
        # The server start can fall between a client's input-head timestamp and that
        # tick's travel. Its key still drives the first in-bout movement.
        if e.get('match')==mid:
            if e['type']=='duel_network':network_keys[e['tick']]=e['action']%4
            if e['type']=='legacy_head':
                w,ctrl,_,_=e['keys'];network_keys[e['tick']]=(2 if ctrl else 1) if w else 0
            if e['type'] in ('sumo_rl_jump','jump_reset_input','duel_network_jump'):jump.add(e['native_tick'])
        if not g['start']<=e.get('time',0)<=g['finish']:continue
        if sprint_packets and e['type']=='replication_apply_before' and e['packet']['kind']=='hk' and e['packet']['entity_id'] in ids:
            metadata[e['packet_id']]=e['state']['sprint']
        if 'monotonic_ns' in e and e['type'] in ('science_move','engine_attack','replication_apply_after'):rows.append(e)
    rows.sort(key=lambda x:x['monotonic_ns'])
    moves=[e for e in rows if e['type']=='science_move']
    if not moves:return {'mid':mid,'side':side,'unsupported':'No movement telemetry in this POV'}
    start=moves[0];inputs=[['BEGIN',(1+g['arena'])*4096,12,*state(start['before']),start['before']['sprint'],start['air_acceleration'],g['sumo_arena']['diameter']]]
    for e in rows:
        if e['monotonic_ns']<start['monotonic_ns']:continue
        if e['type']=='science_move':
            inputs.append(['MOVE',e['before']['rotationYaw'],e['strafe'],e['forward'],e['ground_acceleration'],e['air_acceleration'],
                           e['tick'] in jump,*state(e['before']),*state(e['after']),network_keys.get(e['tick'],3 if e['forward']<0 else 2 if e['forward'] else 0)])
        elif e['type']=='engine_attack':inputs.append(['CONTACT',e['sprint_before']])
        elif e['packet']['kind']=='hm' and e['packet']['entity_id'] in ids:
            inputs.append(['S12',*[e['packet']['motion'+a]/8000 for a in 'XYZ']])
        elif sprint_packets and e['packet']['kind']=='hk' and e['packet']['entity_id'] in ids:
            before=metadata.get(e['packet_id'])
            after=e['state']['sprint']
            if before is not None and before!=after:inputs.append(['SPRINT_METADATA',after])
    text='\n'.join('\t'.join(str(v).lower() if isinstance(v,bool) else str(v) for v in r) for r in inputs)+'\n'
    run=subprocess.run(java('NativeTravel'),input=text,text=True,capture_output=True,check=True)
    out=[line.split() for line in run.stdout.splitlines()]
    assert len(out)==len(moves)
    def failures(i):
        return [{'tick':m['tick'],'at_ms':m['time']-g['start'],'jump':m['tick'] in jump,
                 'dx':float(r[3]),'dy':float(r[4])} for r,m in zip(out,moves) if r[i]!='1']
    step,continuous=failures(0),failures(1)
    return dict(mid=mid,side=side,moves=len(moves),step_failures=len(step),first_step_failure=next(iter(step),None),
                continuous_failures=len(continuous),first_continuous_failure=next(iter(continuous),None),
                jumps=len(jump),off_platform=sum(r[2]=='0' for r in out),
                contacts=sum(r[0]=='CONTACT' for r in inputs),velocities=sum(r[0]=='S12' for r in inputs),
                **({'observed_sprint_changes':sum(r[0]=='SPRINT_METADATA' for r in inputs)} if sprint_packets else {}))
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('output',type=Path);p.add_argument('mids',nargs='+')
    p.add_argument('--sprint-packets',action='store_true',help='Also supply observed own sprint bit changes during metadata handlers')
    a=p.parse_args();compile_model()
    rows=[client(mid,s,a.sprint_packets) for mid in a.mids for s in 'ab']
    with a.output.open('x') as f:json.dump(rows,f,indent=2)
    for key in ('moves','step_failures','continuous_failures','off_platform','contacts','velocities','jumps'):print(key,sum(r.get(key,0) for r in rows))
