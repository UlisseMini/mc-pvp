#!/usr/bin/env python3
"""Native input verification of rods, jumping criticals, healing and match summaries."""
import hashlib
import json
import os
import re
import subprocess
import time
import uuid

from lab import ROOT, CACHE, BUILD, RUNTIME
from lab_runtime import sandbox_java, clean_environment


def run():
    state=json.loads((RUNTIME/'status.json').read_text())
    if time.time()-state['time']/1000>5:raise RuntimeError('Start the lab first')
    if 'LabObserver' in state['players']:raise RuntimeError('Another native observer test is running')
    # The first duel after a restart can spend several seconds preparing chunks.
    # Leave time for that and the countdown before the first input window.
    script=('1500:/blocksound test|3000:/duel standstill rod_diamond 20 20|30000:/stop|'
            '31000:/rematch|50000:/stop|51000:/duel hold-w diamond_box|64000:/stop')
    directory=RUNTIME/'bots/observer';directory.mkdir(exist_ok=True)
    (directory/'control.properties').write_text('enabled=false\n')
    (directory/'options.txt').write_text('renderDistance:6\nfancyGraphics:false\nparticles:2\nsoundCategory_master:0.0\n')
    identifier='combat-stats-'+time.strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:6]
    out=ROOT/'experiments'/identifier;out.mkdir()
    sources=('src/lab/Agent.java','src/lab/Referee.java','src/lab/CombatStats.java','src/lab/PracticeProbe.java','combat_stats_check.py')
    frozen={f:hashlib.sha256((ROOT/f).read_bytes()).hexdigest() for f in sources}
    agent_hash=hashlib.sha256((BUILD/'lab-agent.jar').read_bytes()).hexdigest()
    launched=time.time()*1000
    cp=os.pathsep.join([str(CACHE/'client.jar'),*json.loads((CACHE/'classpath.json').read_text())])
    args=['-Xmx768M','-XX:ActiveProcessorCount=2','-Dlab.root='+str(ROOT),'-Dlab.role=client',
          '-Dlab.slot=observer','-Dlab.port=25569','-Dhmc.lwjgl.update_sleep=10',
          '-Dlab.test_script='+script,'-Dlab.test_duration=67000',
          '-Dlab.practice_block_windows=27500-29000,48000-49000,55000-62000',
          '-Dlab.practice_rod_windows=12500-17000,34500-39000',
          '-Dlab.practice_attack_windows=18000-27000,40000-47000',
          '-Dlog4j.configurationFile='+str(CACHE/'client-logging.xml'),'-javaagent:'+str(BUILD/'lab-agent.jar'),
          '-cp',cp,'net.minecraft.client.main.Main','--version','1.8.9','--gameDir',str(directory),
          '--assetsDir',str(CACHE/'assets'),'--assetIndex','1.8','--username','LabObserver',
          '--uuid',uuid.uuid3(uuid.NAMESPACE_DNS,'LabObserver').hex,'--accessToken','0','--userProperties','{}','--userType','legacy']
    samples=[]
    print('Testing native rod casts, jumping sword attacks, blocking and regeneration...',flush=True)
    with (out/'client.log').open('w') as log:
        process=subprocess.Popen(sandbox_java(args),cwd=directory,stdout=log,stderr=subprocess.STDOUT,env=clean_environment())
        deadline=time.time()+100
        try:
            while process.poll() is None and time.time()<deadline:
                s=json.loads((RUNTIME/'status.json').read_text())
                if any(m['a']=='LabObserver' and s['time']>=m['start']+1000 for m in s['matches']):
                    samples.append({'time':s['time'],'players':[{k:p[k] for k in ('name','food','saturation')} for p in s['player_details']]})
                time.sleep(.5)
            if process.poll() is None:raise TimeoutError('Native statistics probe exceeded deadline')
        finally:
            if process.poll() is None:process.terminate();process.wait(timeout=10)
    games=[json.loads(p.read_text()) for p in (RUNTIME/'results').glob('*.json')]
    games=sorted((g for g in games if g['a']=='LabObserver' and g['start']>=launched),key=lambda g:g['start'])
    events={}
    for g in games:
        raw=(RUNTIME/'replays'/(g['id']+'.jsonl')).read_text();(out/(g['id']+'.jsonl')).write_text(raw)
        events[g['id']]=[json.loads(line) for line in raw.splitlines()]
    all_events=[e for es in events.values() for e in es]
    client=[json.loads(line) for line in (directory/'practice.jsonl').read_text().splitlines() if json.loads(line).get('time',0)>=launched]
    blocks=[e for e in all_events if e['type']=='blocked_damage' and e['target']=='LabObserver']
    sounds=[e for e in client if e['type']=='practice_sound' and e['sound']=='note.pling']
    rods=[e for e in all_events if e['type']=='hit' and e['rod']]
    crits=[e for e in all_events if e['type']=='critical']
    starts=[e['match'] for e in all_events if e['type']=='start']
    log=(out/'client.log').read_text()
    plain=re.sub(r'§[0-9a-fk-or]','',log,flags=re.I)
    checks={
        'native_client_clean_exit':process.returncode==0 and 'at lab.' not in log,
        'source_unchanged_during_probe':all(hashlib.sha256((ROOT/f).read_bytes()).hexdigest()==h for f,h in frozen.items()),
        'rod_rematch_and_box':[g['mode'] for g in games]==['rod_diamond','rod_diamond','diamond_box'],
        'normal_round_outcomes':len(games)==3 and all(g['reason'] in ('death','stopped') for g in games),
        'both_players_receive_rods':len(starts)==3 and all(m['equipment_'+s]['fishing_rod_slot_2']==(m['mode']=='rod_diamond') for m in starts for s in ('a','b')),
        'protection_iv_preserved':len(starts)==3 and all(m['equipment_'+s]['protection_levels']==[4]*4 for m in starts for s in ('a','b')),
        'native_rods_selected_and_cast':any(e.get('rod_input') and e.get('selected_slot')==1 and e.get('has_fish_hook') for e in client),
        'rod_contacts_in_both_rounds':len(games)==3 and all(any(e['type']=='hit' and e['rod'] for e in events[g['id']]) for g in games[:2]),
        'rod_rule_scoped_to_mode':len(games)==3 and all((g['rod_knockback']=='native_zero_damage_contacts_between_duel_opponents')==(g['mode']=='rod_diamond') for g in games),
        'rods_keep_zero_damage':len(rods)>0 and all(e['damage']==0 for e in rods) and not any(e['type']=='health_damage' and e['source']=='thrown' for e in all_events),
        'rods_apply_native_knockback':len(rods)>0 and all(e['target_state']['motionY']>0 and
            (e['target_state']['motionX']*(e['target_state']['posX']-e['attacker_state']['posX'])+
             e['target_state']['motionZ']*(e['target_state']['posZ']-e['attacker_state']['posZ']))>0 for e in rods),
        'rod_velocity_causes_native_client_movement':len(rods)>0 and all(any(
            t['type']=='tick' and e['time']<t['time']<e['time']+700 and t['b']['posY']>e['target_state']['posY']+.05
            for t in all_events) for e in rods),
        'native_criticals_observed':len(crits)>=1,
        'native_healing_observed':any(e['type']=='heal' and e['amount']>0 for e in all_events),
        'all_players_fed_equally':len(samples)>10 and all(p['food']==20 and 19<=p['saturation']<=20 for s in samples for p in s['players']),
        'block_sound_delivery_unchanged':len(blocks)>0 and len(sounds)==1+sum(e['sound_sent'] for e in blocks),
        'summary_received_in_chat':log.count('Finished ')==len(games)>0 and log.count('Health/damage in hearts.')==len(games),
    }
    for g in games:
        es=events[g['id']]
        summary=plain.split('Finished '+g['id']+' ',1)[-1].split('Finished ',1)[0]
        expected_per_hit=[]
        for side in ('a','b'):
            name=g[side];stats=g['stats_'+side];hits=[e for e in es if e['type']=='hit' and e['attacker']==name]
            healed=sum(e['amount'] for e in es if e['type']=='heal' and e['player']==name)
            checks[g['id']+'_'+side+'_health_balance']=abs(20+stats['regenerated']-stats['damage_taken']-g['health_'+side])<.0001
            checks[g['id']+'_'+side+'_native_crit_damage_matches']=stats['crits']==sum(e['damage']==(7.5 if g['mode']=='diamond_box' else 12) for e in hits)
            checks[g['id']+'_'+side+'_counters_match_events']=(g['hits_'+side]==len(hits) and stats['rod_hits']==sum(e['rod'] for e in hits)
                and stats['damage_upgrades']==sum(e['damage_upgrade'] for e in hits) and abs(stats['regenerated']-healed)<.0001
                and g['blocks_'+side]==sum(e['type']=='blocked_damage' and e['target']==name for e in es))
            opponent=g['b' if side=='a' else 'a']
            incoming=[e for e in es if e['type']=='hit' and e['attacker']==opponent and e['target']==name and not e['rod']]
            actual_loss=sum(e['amount'] for e in es if e['type']=='health_damage' and e['attacker']==opponent and e['target']==name)
            average=actual_loss/len(incoming) if incoming else None
            checks[g['id']+'_'+side+'_damage_per_hit_matches_native_events']=(stats['melee_hits_taken']==len(incoming) and
                (stats['damage_taken_per_hit'] is None if average is None else abs(stats['damage_taken_per_hit']-average)<.000001))
            expected_per_hit.append('N/A (no melee hits)' if average is None else f'{average/2:.2f} hearts ({len(incoming)} melee hits)')
        # The native logger wraps chat in an XML CDATA element; only compare the message.
        received=[value.split(']]>',1)[0] for value in re.findall(r'Damage taken per hit: ([^\r\n]*)',summary)]
        checks[g['id']+'_both_damage_per_hit_chat_values']=received==expected_per_hit
    report={'checks':checks,'passed':all(checks.values()),'games':games,'script':script,'source_sha256':frozen,'agent_sha256':agent_hash,
            'rod_contacts':len(rods),'criticals':len(crits),'blocks':len(blocks),'chimes':len(sounds),'food_samples':len(samples)}
    (out/'food-samples.json').write_text(json.dumps(samples,indent=2)+'\n')
    (out/'rod-inputs.json').write_text(json.dumps([e for e in client if e.get('rod_input')],indent=2)+'\n')
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'directory':str(out),'checks':checks,'rod_contacts':len(rods),'criticals':len(crits)},indent=2),flush=True)
    if not report['passed']:raise RuntimeError('Native statistics verification failed; inspect '+str(out))


if __name__=='__main__':run()
