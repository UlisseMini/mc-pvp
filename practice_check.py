#!/usr/bin/env python3
"""Exercise real blocking input, private sound packets, health duels and rematch."""
import hashlib
import json
import os
import subprocess
import time
import uuid

from lab import ROOT, CACHE, BUILD, RUNTIME, JAVA
from lab_runtime import sandbox_java, clean_environment


def run():
    state=json.loads((RUNTIME/'status.json').read_text())
    if time.time()-state['time']/1000>5:raise RuntimeError('Start the lab first')
    # Weak swords prolong health duels; allow the first round to end on
    # native death before testing /rematch. The second round ends with /stop.
    script='1500:/blocksound test|3000:/duel hold-w boxing|9000:/blocksound off|12000:/blocksound on|14500:/stop|15000:/mode diamond|15500:/duel hold-w 20 20|65000:/rematch|78000:/stop'
    directory=RUNTIME/'bots/observer';directory.mkdir(exist_ok=True)
    (directory/'control.properties').write_text('enabled=false\n')
    (directory/'options.txt').write_text('renderDistance:6\nfancyGraphics:false\nparticles:2\nsoundCategory_master:0.0\n')
    trace=directory/'practice.jsonl'
    # Unique verification artifacts retain every run; do not overwrite old evidence.
    name='practice-'+time.strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:6]
    out=ROOT/'experiments'/name;out.mkdir(parents=True)
    sources=('src/lab/Agent.java','src/lab/Referee.java','src/lab/Client.java','src/lab/PracticeProbe.java','practice_check.py')
    frozen={'source_sha256':{f:hashlib.sha256((ROOT/f).read_bytes()).hexdigest() for f in sources},
            'agent_sha256':hashlib.sha256((BUILD/'lab-agent.jar').read_bytes()).hexdigest(),'script':script}
    (out/'manifest.json').write_text(json.dumps(frozen,indent=2)+'\n')
    launched=time.time()*1000
    cp=os.pathsep.join([str(CACHE/'client.jar'),*json.loads((CACHE/'classpath.json').read_text())])
    args=['-Xmx768M','-XX:ActiveProcessorCount=2','-Dlab.root='+str(ROOT),'-Dlab.role=client','-Dlab.slot=observer','-Dlab.port=25569',
          '-Dhmc.lwjgl.update_sleep=10','-Dlab.test_script='+script,'-Dlab.test_duration=81000',
          '-Dlab.practice_block_windows=5900-9000,10500-14000,19000-22000',
          '-Dlog4j.configurationFile='+str(CACHE/'client-logging.xml'),'-javaagent:'+str(BUILD/'lab-agent.jar'),
          '-cp',cp,'net.minecraft.client.main.Main','--version','1.8.9','--gameDir',str(directory),
          '--assetsDir',str(CACHE/'assets'),'--assetIndex','1.8','--username','LabObserver',
          '--uuid',uuid.uuid3(uuid.NAMESPACE_DNS,'LabObserver').hex,'--accessToken','0','--userProperties','{}','--userType','legacy']
    with (out/'client.log').open('w') as log:
        process=subprocess.run(sandbox_java(args),cwd=directory,stdout=log,stderr=subprocess.STDOUT,timeout=120,env=clean_environment())
    games=[json.loads(p.read_text()) for p in (RUNTIME/'results').glob('*.json')]
    games=sorted((g for g in games if g['a']=='LabObserver' and g['start']>=launched),key=lambda g:g['start'])
    client=[json.loads(line) for line in trace.read_text().splitlines() if json.loads(line).get('time',0)>=launched]
    events=[]
    for g in games:
        raw=(RUNTIME/'replays'/(g['id']+'.jsonl')).read_text();(out/(g['id']+'.jsonl')).write_text(raw)
        events.extend(json.loads(line) for line in raw.splitlines())
    blocks=[e for e in events if e['type']=='blocked_damage' and e['target']=='LabObserver']
    hits=[e for e in events if e['type']=='hit' and e['target']=='LabObserver']
    sounds=[e for e in client if e['type']=='practice_sound' and e['sound']=='note.pling']
    diamond=[g for g in games if g['mode']=='diamond'];boxing=[g for g in games if g['mode']=='boxing']
    boxing_hits=[json.loads(line) for g in boxing for line in (out/(g['id']+'.jsonl')).read_text().splitlines() if json.loads(line)['type']=='hit']
    log=(out/'client.log').read_text()
    checks={
        'client_exited_cleanly':process.returncode==0 and 'at lab.' not in log,
        'source_unchanged_during_probe':all(hashlib.sha256((ROOT/f).read_bytes()).hexdigest()==digest for f,digest in frozen['source_sha256'].items()),
        'three_duels_with_rematch':len(games)==3 and len(diamond)==2 and len(boxing)==1,
        'vanilla_reduction_observed':len(blocks)>=3 and all(e['damage_after_block']==(e['damage_before_block']+1)*.5 and e['damage_after_block']<e['damage_before_block'] for e in blocks),
        'ordinary_and_blocked_hits':any(e['blocking'] for e in hits) and any(not e['blocking'] for e in hits),
        'each_cue_is_an_accepted_attack':all(any(h['attacker']==b['attacker'] and h['target']==b['target'] and 0<=h['time']-b['time']<100 for h in hits) for b in blocks),
        'private_sound_received_once_per_unmuted_block':len(sounds)==1+sum(b['sound_sent'] for b in blocks),
        'mute_and_unmute_exercised':any(not b['sound_sent'] for b in blocks) and any(b['sound_sent'] for b in blocks),
        'boxing_still_restores_health':bool(boxing_hits) and all(h['health_after']==20 for h in boxing_hits),
        'diamond_health_reaches_death':any(g['reason']=='death' and g['health_a']==0 and g['winner']==g['b'] and g['hits_b']<100 for g in diamond),
        'diamond_full_armor':any(e['type']=='practice_input' and e['armor_points']==20 and e['health']<20 for e in client),
        'rematch_retains_mode_and_ping':len(diamond)==2 and all(g['initial_ping_a']==20 and g['initial_ping_b']==20 for g in diamond),
        'new_mode_does_not_instantly_finish_at_zero_goal':all(g['duration_seconds']>1 and g['hits_b']>0 for g in diamond),
    }
    report={'checks':checks,'passed':all(checks.values()),'games':games,'block_events':blocks,
            'sound_packets':sounds,**frozen}
    (out/'client-events.json').write_text(json.dumps(client,indent=2)+'\n')
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'directory':str(out),'checks':checks,'games':[g['id'] for g in games],'blocks':len(blocks),'chimes':len(sounds)},indent=2),flush=True)
    if not report['passed']:raise RuntimeError('Native practice verification failed; inspect '+str(out))


if __name__=='__main__':run()
