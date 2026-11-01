#!/usr/bin/env python3
"""Exercise /replay with a real native spectator client, including received ghost positions."""
import hashlib
import json
import os
import re
import subprocess
import time
import uuid
from pathlib import Path

from lab import ROOT, CACHE, BUILD, RUNTIME
from lab_runtime import sandbox_java, clean_environment


def capture(script,duration,prefix="replay"):
    """Run a real spectator client and capture both native client and server state."""
    state=json.loads((RUNTIME/'status.json').read_text())
    if time.time()-state['time']/1000>5 or 'LabObserver' in state['players']:
        raise RuntimeError('Need a live server and an unused native observer client')
    directory=RUNTIME/'bots/observer';directory.mkdir(exist_ok=True)
    (directory/'control.properties').write_text('enabled=false\n')
    (directory/'options.txt').write_text('renderDistance:6\nfancyGraphics:false\nparticles:2\nsoundCategory_master:0.0\n')
    out=ROOT/'experiments'/(prefix+'-'+time.strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:6]);out.mkdir()
    digest=hashlib.sha256((BUILD/'lab-agent.jar').read_bytes()).hexdigest()
    cp=os.pathsep.join([str(CACHE/'client.jar'),*json.loads((CACHE/'classpath.json').read_text())])
    args=['-Xmx768M','-XX:ActiveProcessorCount=2','-Dlab.root='+str(ROOT),'-Dlab.role=client',
          '-Dlab.slot=observer','-Dlab.port=25569','-Dhmc.lwjgl.update_sleep=10',
          '-Dlab.test_script='+script,'-Dlab.test_duration='+str(duration),'-Dlab.practice_block_windows=',
          '-Dlab.replay_probe=true','-Dlog4j.configurationFile='+str(CACHE/'client-logging.xml'),
          '-javaagent:'+str(BUILD/'lab-agent.jar'),'-cp',cp,'net.minecraft.client.main.Main','--version','1.8.9',
          '--gameDir',str(directory),'--assetsDir',str(CACHE/'assets'),'--assetIndex','1.8','--username','LabObserver',
          '--uuid',uuid.uuid3(uuid.NAMESPACE_DNS,'LabObserver').hex,'--accessToken','0','--userProperties','{}','--userType','legacy']
    launched=time.time()*1000;samples=[]
    print('Testing native replay viewer: '+prefix,flush=True)
    with (out/'client.log').open('w') as log:
        process=subprocess.Popen(sandbox_java(args),cwd=directory,stdout=log,stderr=subprocess.STDOUT,env=clean_environment())
        deadline=time.time()+duration/1000+30
        try:
            while process.poll() is None and time.time()<deadline:
                samples.append(json.loads((RUNTIME/'status.json').read_text()));time.sleep(.2)
            if process.poll() is None:raise TimeoutError('Native replay viewer did not exit')
        finally:
            if process.poll() is None:process.terminate();process.wait(timeout=10)
    path=directory/'replay-probe.jsonl'
    client=[e for line in path.read_text().splitlines() if (e:=json.loads(line))['time']>=launched] if path.exists() else []
    log=(out/'client.log').read_text()
    return out,digest,samples,client,log,process.returncode


def run():
    state=json.loads((RUNTIME/'status.json').read_text())
    if time.time()-state['time']/1000>5 or 'LabObserver' in state['players']:
        raise RuntimeError('Need a live server and an unused native observer client')
    old='m0930'
    own=[json.loads(p.read_text()) for p in (RUNTIME/'results').glob('*.json')]
    rod=max((g for g in own if g.get('a')=='LabObserver' and g.get('mode')=='rod_diamond'),key=lambda g:g['finish'])
    rod_frames=[e for line in (RUNTIME/'replays'/(rod['id']+'.jsonl')).read_text().splitlines()
                if (e:=json.loads(line))['type']=='tick' and any(e.get(s,{}).get('fish_hook') for s in ('a','b'))]
    if not rod_frames:raise RuntimeError('Run combat_stats_check.py first to record native rod flight')
    rod_seek=max(0,(rod_frames[0]['time']-rod['start'])/1000-.2)
    script=(f'1500:/replay list|2500:/replay {old}|7000:/replay pause|8000:/replay seek 12.5|'
            '10000:/replay a|12000:/replay b|14000:/replay free|15000:/replay speed 0.5|16000:/replay play|'
            '19000:/replay stop|20500:/replay|25000:/replay pause|26000:/replay seek 0|28000:/replay play|'
            f'29000:/replay speed 2|35000:/replay seek 9999|37000:/replay {rod["id"]}|41000:/replay seek {rod_seek:.3f}|'
            '47000:/stop|49000:/duel standstill diamond|54000:/stop')
    out,digest,samples,client,log,returncode=capture(script,57000)
    plain=re.sub(r'§[0-9a-fk-or]','',log,flags=re.I)
    sessions=[(s['time'],r) for s in samples for r in s.get('replays',[]) if r['viewer']=='LabObserver']
    def session_at(t):
        return min(sessions,key=lambda r:abs(r[0]-t))[1] if sessions else {}
    cameras={}
    for mode,index,lo,hi in [('a',0,11000,11800),('b',1,13000,13800)]:
        subset=[e for e in client if lo<=e['elapsed_ms']<=hi]
        cameras[mode]=bool(subset) and all(e['camera']==session_at(e['time']).get('actor_ids',[-1,-1])[index] for e in subset)
    paused=[e for e in client if 9000<=e['elapsed_ms']<=13800]
    old_result=json.loads((RUNTIME/'results'/f'{old}.json').read_text());cut=old_result['start']+12500
    events=[json.loads(line) for line in (RUNTIME/'replays'/f'{old}.jsonl').read_text().splitlines()]
    pose={}
    for e in events:
        if e.get('time',0)>cut:continue
        if e['type']=='tick':pose={side:e[side] for side in ('a','b')}
        elif e['type']=='hit' and pose:
            side='a' if e['attacker']==old_result['a'] else 'b';pose[side]=e['attacker_state'];pose['b' if side=='a' else 'a']=e['target_state']
    errors=[]
    for e in paused:
        s=session_at(e['time']);ids=s.get('actor_ids',[]);players={p['id']:p for p in e['players']}
        if len(ids)!=2 or not all(i in players for i in ids):continue
        for i,side in enumerate(('a','b')):
            expected={**pose[side],'posX':pose[side]['posX']+s['center_x']-4096*(old_result['arena']+1)}
            errors.extend(abs(players[ids[i]][axis]-expected[axis]) for axis in ('posX','posY','posZ'))
    half=[(t,s['time_ms']) for t,s in sessions if s['match']==old and s['speed']==.5 and not s['paused']]
    slope=(half[-1][1]-half[0][1])/(half[-1][0]-half[0][0]) if len(half)>2 and half[-1][0]>half[0][0] else None
    latest={s['match'] for _,s in sessions if s['match']!=old}
    checks={
        'native_client_clean_exit':returncode==0 and 'at lab.' not in log,
        'build_unchanged':hashlib.sha256((BUILD/'lab-agent.jar').read_bytes()).hexdigest()==digest,
        'old_recording_and_limit_notice':f'Server replay {old}' in plain and 'Older recording:' in plain,
        'spectator_mode_received':any(e['spectator'] for e in paused),
        'ghost_positions_match_recorded_server_with_protocol_quantization':len(errors)>100 and max(errors)<.04,
        'pause_and_seek_to_12_5_seconds':any(s['paused'] and s['time_ms']==12500 for _,s in sessions),
        'follow_a_camera_received':cameras['a'],'follow_b_camera_received':cameras['b'],
        'free_camera_received':any(14300<=e['elapsed_ms']<=14900 and e['camera']==e['self'] for e in client),
        'half_speed_clock':slope is not None and .4<slope<.6,
        'latest_own_match_and_rod_recording_played':len(latest)==2 and all('LabObserver' in [json.loads((RUNTIME/'results'/f'{mid}.json').read_text())[side] for side in ('a','b')] for mid in latest),
        'new_recording_has_precise_pose':all(any('rotationYaw' in e.get('a',{}) for line in (RUNTIME/'replays'/f'{mid}.jsonl').read_text().splitlines() if (e:=json.loads(line))['type']=='tick') for mid in latest),
        'oversized_seek_clamped_and_finished':any(s['time_ms']==s['duration_ms'] and s['paused'] for _,s in sessions),
        'replay_never_uses_duel_slot':not any(any(m['a']=='LabObserver' or m['b']=='LabObserver' for m in s['matches']) and any(r['viewer']=='LabObserver' for r in s.get('replays',[])) for s in samples),
        'duel_works_after_replay_exit':any(any(m['a']=='LabObserver' and m['mode']=='diamond' for m in s['matches']) for s in samples),
        'replay_session_cleaned':not any(s['viewer']=='LabObserver' for s in samples[-1].get('replays',[])),
        'ghosts_removed_on_exit':any(47500<=e['elapsed_ms']<=48500 and e['camera']==e['self'] and len(e['players'])==1 for e in client),
        'recorded_block_pose_visible':any(28000<=e['elapsed_ms']<=35000 and any(p['id']!=e['self'] and p['blocking'] for p in e['players']) for e in client),
        'recorded_rod_flight_visible':any(41000<=e['elapsed_ms']<=46500 and e['hooks']>0 for e in client),
        'recorded_held_rod_visible':any(41000<=e['elapsed_ms']<=46500 and any(p['id']!=e['self'] and p['held_item_id']==346 for p in e['players']) for e in client),
    }
    report={'passed':all(checks.values()),'checks':checks,'max_position_error':max(errors) if errors else None,
            'position_coordinates_checked':len(errors),'half_speed_slope':slope,'agent_sha256':digest,'script':script,'old_match':old,'new_matches':sorted(latest)}
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n');(out/'server-samples.json').write_text(json.dumps(samples)+'\n')
    (out/'client-samples.json').write_text(json.dumps(client)+'\n')
    print(json.dumps({'directory':str(out),**report},indent=2),flush=True)
    if not report['passed']:raise RuntimeError('Native replay verification failed: '+str(out))


if __name__=='__main__':run()
