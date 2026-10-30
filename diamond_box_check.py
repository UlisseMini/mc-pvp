#!/usr/bin/env python3
"""Native-client integration: box confinement, actual kits, block cues and rematch."""
import hashlib
import json
import os
import subprocess
import time
import uuid

from lab import ROOT, CACHE, BUILD, RUNTIME
from lab_runtime import sandbox_java, clean_environment


def run():
    state=json.loads((RUNTIME/'status.json').read_text())
    if time.time()-state['time']/1000>5:raise RuntimeError('Start the lab first')
    if 'LabObserver' in state['players']:raise RuntimeError('Another native observer test is running')
    script=('1500:/blocksound test|3000:/mode diamond_box|3500:/duel hold-w 20 20|'
            '22000:/stop|22500:/rematch|34000:/stop|34500:/duel hold-w diamond 20 20|'
            '50000:/stop|50500:/duel hold-w boxing|62000:/stop')
    directory=RUNTIME/'bots/observer';directory.mkdir(exist_ok=True)
    (directory/'control.properties').write_text('enabled=false\n')
    (directory/'options.txt').write_text('renderDistance:6\nfancyGraphics:false\nparticles:2\nsoundCategory_master:0.0\n')
    identifier='diamond-box-'+time.strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:6]
    log_path=RUNTIME/'logs'/(identifier+'.log')
    sources=('src/lab/Referee.java','diamond_box_check.py','src/lab/PracticeProbe.java')
    frozen={f:hashlib.sha256((ROOT/f).read_bytes()).hexdigest() for f in sources}
    agent_hash=hashlib.sha256((BUILD/'lab-agent.jar').read_bytes()).hexdigest()
    launched=time.time()*1000
    cp=os.pathsep.join([str(CACHE/'client.jar'),*json.loads((CACHE/'classpath.json').read_text())])
    args=['-Xmx768M','-XX:ActiveProcessorCount=2','-Dlab.root='+str(ROOT),'-Dlab.role=client',
          '-Dlab.slot=observer','-Dlab.port=25569','-Dhmc.lwjgl.update_sleep=10',
          '-Dlab.test_script='+script,'-Dlab.test_duration=65000',
          '-Dlab.practice_block_windows=6500-11000,15000-19000,27000-32000,39000-44000,55000-60000',
          '-Dlog4j.configurationFile='+str(CACHE/'client-logging.xml'),'-javaagent:'+str(BUILD/'lab-agent.jar'),
          '-cp',cp,'net.minecraft.client.main.Main','--version','1.8.9','--gameDir',str(directory),
          '--assetsDir',str(CACHE/'assets'),'--assetIndex','1.8','--username','LabObserver',
          '--uuid',uuid.uuid3(uuid.NAMESPACE_DNS,'LabObserver').hex,'--accessToken','0','--userProperties','{}','--userType','legacy']
    print('Testing box, rematch, flat diamond and boxing with native right-click input...',flush=True)
    with log_path.open('w') as log:
        process=subprocess.run(sandbox_java(args),cwd=directory,stdout=log,stderr=subprocess.STDOUT,timeout=100,env=clean_environment())
    games=[json.loads(p.read_text()) for p in (RUNTIME/'results').glob('*.json')]
    games=sorted((g for g in games if g['a']=='LabObserver' and g['start']>=launched),key=lambda g:g['start'])
    events={g['id']:[json.loads(line) for line in (RUNTIME/'replays'/(g['id']+'.jsonl')).read_text().splitlines()] for g in games}
    traces=[e for es in events.values() for e in es]
    client=[json.loads(line) for line in (directory/'practice.jsonl').read_text().splitlines() if json.loads(line).get('time',0)>=launched]
    sounds=[e for e in client if e['type']=='practice_sound' and e['sound']=='note.pling']
    blocks=[e for e in traces if e['type']=='blocked_damage' and e['target']=='LabObserver']
    boxes=[g for g in games if g['mode']=='diamond_box']
    starts=[e['match'] for e in traces if e['type']=='start']
    box_ticks=[(g,e) for g in boxes for e in events[g['id']] if e['type']=='tick' and e['time']>=g['start']]
    def in_box(g,p):
        center=4096*(g['arena']+1)
        return center+.29999<=p['posX']<=center+.70001 and .29999<=p['posZ']<=.70001 and 3.99999<=p['posY']<=4.20001
    def hits(mode):return [e for g in games if g['mode']==mode for e in events[g['id']] if e['type']=='hit']
    box_hits=hits('diamond_box');flat_hits=hits('diamond');boxing_hits=hits('boxing')
    checks={
        'client_clean_exit':process.returncode==0 and 'at lab.' not in log_path.read_text(),
        'source_unchanged_during_probe':all(hashlib.sha256((ROOT/f).read_bytes()).hexdigest()==h for f,h in frozen.items()),
        'four_modes_in_order':[g['mode'] for g in games]==['diamond_box','diamond_box','diamond','boxing'],
        'all_rounds_stop_normally':len(games)==4 and all(g['reason']=='stopped' for g in games),
        'box_rematch_retains_pings':len(boxes)==2 and all(g['initial_ping_a']==20 and g['initial_ping_b']==20 for g in boxes),
        'real_protection_iv_all_four_pieces':len(starts)==4 and all(m['equipment_'+s]['protection_levels']==([0]*4 if m['mode']=='boxing' else [4]*4) for m in starts for s in ('a','b')),
        'correct_native_weapons':len(starts)==4 and all(m['equipment_'+s]['weapon']==('wooden_sword' if m['mode']=='diamond_box' else 'diamond_sword') for m in starts for s in ('a','b')),
        'box_wood_swords_unbreakable':len(boxes)==2 and all(m['equipment_'+s]['weapon_unbreakable'] for m in starts if m['mode']=='diamond_box' for s in ('a','b')),
        'both_players_confined_by_native_collision':len(box_ticks)>100 and all(in_box(g,e[s]) for g,e in box_ticks for s in ('a','b')),
        'native_box_hits_and_health_loss':len(box_hits)>=10 and any(e['health_after']<19 for e in box_hits),
        'box_block_reduces_wood_sword_damage':sum(e['damage_before_block']==5 and e['damage_after_block']==3 for g in boxes for e in events[g['id']] if e['type']=='blocked_damage')>=3,
        'chimes_match_real_block_events':len(blocks)>=3 and len(sounds)==1+sum(e['sound_sent'] for e in blocks),
        'flat_arena_reused_and_native_damage':len(flat_hits)>=5 and any(e['health_after']<20 for e in flat_hits),
        'boxing_health_restoration_unchanged':len(boxing_hits)>=5 and all(e['health_after']==20 for e in boxing_hits),
    }
    report={'checks':checks,'passed':all(checks.values()),'games':games,'kits':[{k:m[k] for k in ('id','mode','equipment_a','equipment_b')} for m in starts],
            'box_tick_samples':len(box_ticks),'box_hits':len(box_hits),'block_events':len(blocks),'sound_packets':len(sounds),
            'script':script,'source_sha256':frozen,'agent_sha256':agent_hash,'client_log':str(log_path.relative_to(ROOT)),
            'replay_sha256':{g['id']:hashlib.sha256((RUNTIME/'replays'/(g['id']+'.jsonl')).read_bytes()).hexdigest() for g in games}}
    out=ROOT/'experiments'/(identifier+'.json');out.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'report':str(out),'checks':checks,'games':[g['id'] for g in games],'box_hits':len(box_hits),'blocks':len(blocks),'chimes':len(sounds)},indent=2),flush=True)
    if not report['passed']:raise RuntimeError('Native diamond-box verification failed; inspect '+str(out))


if __name__=='__main__':run()
