#!/usr/bin/env python3
"""Native per-duel reach/CPS checks and saved ping across rematches and reconnects."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import uuid

from lab import ROOT,CACHE,BUILD,RUNTIME
from lab_runtime import sandbox_java,clean_environment,match_events


def run():
    state=json.loads((RUNTIME/'status.json').read_text())
    if time.time()-state['time']/1000>5 or 'LabObserver' in state['players']:raise RuntimeError('Need a live server and an unused observer')
    out=ROOT/'experiments'/('duel-settings-'+time.strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:6]);out.mkdir()
    source_names=['src/lab/Agent.java','src/lab/Client.java','src/lab/DuelSettings.java','src/lab/Referee.java','duel_settings_check.py']
    frozen={f:hashlib.sha256((ROOT/f).read_bytes()).hexdigest() for f in source_names}
    policies={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in (ROOT/'policies').glob('*.properties')}
    launched=time.time()*1000
    directory=RUNTIME/'bots/observer';directory.mkdir(exist_ok=True)
    (directory/'control.properties').write_text('enabled=false\n')
    (directory/'options.txt').write_text('renderDistance:6\nfancyGraphics:false\nparticles:2\nsoundCategory_master:0.0\n')
    cp=os.pathsep.join([str(CACHE/'client.jar'),*json.loads((CACHE/'classpath.json').read_text())])
    scripts=[('1500:/ping me 80|2300:/duel hold-w reach=NaN|3000:/duel hold-w boxing reach=5 cps=20 ping=0|'
              '16000:/ping me 120|18000:/stop|19000:/rematch|30000:/stop|'
              '31000:/duel hold-w boxing reach=2 cps=5 ping=40|43000:/stop|'
              '44000:/duel wtap|56000:/stop|58000:/ping',61000),
             ('1500:/ping|3000:/duel hold-w|15000:/stop',18000)]
    codes=[]
    for i,(script,duration) in enumerate(scripts):
        args=['-Xmx768M','-XX:ActiveProcessorCount=2','-Dlab.root='+str(ROOT),'-Dlab.role=client','-Dlab.slot=observer','-Dlab.port=25569',
              '-Dhmc.lwjgl.update_sleep=10','-Dlab.test_script='+script,'-Dlab.test_duration='+str(duration),'-Dlab.practice_block_windows=',
              '-Dlog4j.configurationFile='+str(CACHE/'client-logging.xml'),'-javaagent:'+str(BUILD/'lab-agent.jar'),'-cp',cp,'net.minecraft.client.main.Main',
              '--version','1.8.9','--gameDir',str(directory),'--assetsDir',str(CACHE/'assets'),'--assetIndex','1.8','--username','LabObserver',
              '--uuid',uuid.uuid3(uuid.NAMESPACE_DNS,'LabObserver').hex,'--accessToken','0','--userProperties','{}','--userType','legacy']
        print('Native duel-options session '+str(i+1),flush=True)
        with (out/f'client-{i+1}.log').open('w') as log:
            process=subprocess.run(sandbox_java(args),cwd=directory,stdout=log,stderr=subprocess.STDOUT,timeout=duration/1000+40,env=clean_environment())
        codes.append(process.returncode)
        # Observe the disconnect before connecting the same test identity again.
        for _ in range(30):
            if 'LabObserver' not in json.loads((RUNTIME/'status.json').read_text())['players']:break
            time.sleep(.1)
    games=sorted((g for p in (RUNTIME/'results').glob('*.json') if (g:=json.loads(p.read_text()))['a']=='LabObserver' and g['start']>=launched),key=lambda g:g['start'])
    logs='\n'.join((out/f'client-{i+1}.log').read_text() for i in range(2))
    plain=re.sub(r'§[0-9a-fk-or]','',logs,flags=re.I)
    checks={
        'clean_native_sessions':codes==[0,0] and 'at lab.' not in logs,
        'five_valid_duels':len(games)==5 and all(g['reason']=='stopped' for g in games),
        'effective_reach':[g['attack_reach_b'] for g in games]==[5,5,2,3,3],
        'effective_cps':[g['click_cps_b'] for g in games]==[20,20,5,10,10],
        'bot_pings':[g['initial_ping_b'] for g in games]==[0,0,40,0,0],
        'saved_player_ping_across_rematch_new_duel_and_reconnect':[g['initial_ping_a'] for g in games]==[80,120,120,120,120],
        'midround_ping_persisted':len(games)==5 and games[0]['ping_a']==120 and games[0]['ping_changes']==1,
        'modified_matches_labelled':[g['modified_reach'] for g in games]==[True,True,True,False,False] and 'Modified-reach practice' in plain,
        'invalid_reach_rejected':'Reach (blocks) must be 1.0 through 5.0' in plain,
        'human_reach_unchanged':all(g['attack_reach_a']==3 for g in games),
        'shared_policies_unchanged':all(hashlib.sha256(Path(f).read_bytes()).hexdigest()==h for f,h in policies.items()),
        'source_unchanged':all(hashlib.sha256((ROOT/f).read_bytes()).hexdigest()==h for f,h in frozen.items()),
        'saved_ping_on_disk':'human_ping=120.0' in (RUNTIME/'preferences/labobserver.properties').read_text(),
    }
    measured=[]
    for g in games:
        es=list(match_events(RUNTIME/'bots'/g['b'].removeprefix('LabSlot')/'events.jsonl',g['id']))
        clicks=[e for e in es if e['type']=='click'];attacks=[e for e in es if e['type']=='engine_attack'];probes=[e for e in es if e['type']=='reach_probe']
        reach=g['attack_reach_b'];cps=g['click_cps_b'];interval=(1_000_000_000+cps-1)//cps
        checks[g['id']+'_native_reach_limit']=bool(attacks) and all(e['native_raycast_distance']<=reach+.00001 and e['ray_selected_target'] and e['target_ready'] for e in attacks)
        checks[g['id']+'_actual_click_ceiling']=len(clicks)>5 and all(e['click_cps']==cps for e in clicks) and all(b['monotonic_ns']-a['monotonic_ns']>=interval for a,b in zip(clicks,clicks[1:]))
        checks[g['id']+'_server_hits_registered']=g['hits_b']>0
        if reach>3:checks[g['id']+'_actual_extended_attacks']=any(e['native_raycast_distance']>3.2 for e in attacks)
        measured.append({'id':g['id'],'configured_reach':reach,'maximum_actual_attack_ray':max((e['native_raycast_distance'] for e in attacks),default=None),
                         'attacks':len(attacks),'clicks':len(clicks),'hits':g['hits_b']})
        (out/(g['id']+'-client.jsonl')).write_text(''.join(json.dumps(e)+'\n' for e in clicks+attacks+probes))
        (out/(g['id']+'.jsonl')).write_bytes((RUNTIME/'replays'/(g['id']+'.jsonl')).read_bytes())
    report={'passed':all(checks.values()),'checks':checks,'games':games,'measurements':measured,'source_sha256':frozen,
            'agent_sha256':hashlib.sha256((BUILD/'lab-agent.jar').read_bytes()).hexdigest(),'scripts':scripts}
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'directory':str(out),'checks':checks,'measurements':measured},indent=2),flush=True)
    if not report['passed']:raise RuntimeError('Duel settings verification failed; inspect '+str(out))


if __name__=='__main__':run()
