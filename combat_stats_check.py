#!/usr/bin/env python3
"""Native input verification of rods, jumping criticals, healing and match summaries."""
import hashlib
from collections import defaultdict
import json
import os
import re
import subprocess
import time
import uuid
from decimal import Decimal, ROUND_HALF_UP

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
    sources=('src/lab/Agent.java','src/lab/Referee.java','src/lab/CombatStats.java','src/lab/RodTricks.java','src/lab/DamageBreakdown.java','src/lab/DamageBenefits.java','src/lab/BlockTiming.java','src/lab/BlockAccuracy.java','src/lab/PracticeProbe.java','combat_stats_check.py')
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
                    participants={m[k] for m in s['matches'] if s['time']>=m['start']+1000 for k in ('a','b')}
                    samples.append({'time':s['time'],'players':[{k:p[k] for k in ('name','food','saturation')} for p in s['player_details'] if p['name'] in participants]})
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
    sounds=[e for e in client if e['type']=='practice_sound' and e['sound']=='random.anvil_land']
    rods=[e for e in all_events if e['type']=='hit' and e['rod']]
    crits=[e for e in all_events if e['type']=='critical']
    starts=[e['match'] for e in all_events if e['type']=='start']
    log=(out/'client.log').read_text()
    plain=re.sub(r'§[0-9a-fk-or]','',log,flags=re.I)
    bars=[{'time':e['time'],'text':re.sub(r'§[0-9a-fk-or]','',e['action_bar'],flags=re.I)} for e in client if e.get('action_bar')]
    ratios=[m for e in bars if (m:=re.search(r'Blocks: (\d+)/(\d+) \(([\d.]+)%\)',e['text']))]
    checks={
        'native_client_clean_exit':process.returncode==0 and 'at lab.' not in log,
        'source_unchanged_during_probe':all(hashlib.sha256((ROOT/f).read_bytes()).hexdigest()==h for f,h in frozen.items()),
        'rod_rematch_and_box':[g['mode'] for g in games]==['rod_diamond','rod_diamond','diamond_box'],
        'normal_round_outcomes':len(games)==3 and all(g['reason'] in ('death','stopped') for g in games),
        'both_players_receive_rods':len(starts)==3 and all(m['equipment_'+s]['fishing_rod_slot_2']==(m['mode']=='rod_diamond') for m in starts for s in ('a','b')),
        'unenchanted_diamond_wood_kit':len(starts)==3 and all(m['armor_protection']==0 and m['equipment_'+s]['protection_levels']==[0]*4 and m['equipment_'+s]['armor_points']==20 and m['equipment_'+s]['weapon']=='wooden_sword' and m['equipment_'+s]['weapon_unbreakable'] and m['equipment_'+s]['armor_unbreakable']==[True]*4 and m['equipment_'+s]['fishing_rod_unbreakable']==(m['mode']=='rod_diamond') for m in starts for s in ('a','b')),
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
        'quiet_metallic_sound_received':bool(sounds) and all(abs(e['volume']-.25)<1e-6 and 1.5<e['pitch']<1.7 for e in sounds),
        'native_timing_feedback_received':any('BLOCKED' in e['text'] and 'held ~' in e['text'] for e in bars),
        'native_bar_ratio_arithmetic':bool(ratios) and all(int(m[2])>0 and abs(float(m[3])-100*int(m[1])/int(m[2]))<=.051 for m in ratios),
        'native_bar_retains_damage_fraction':any('BLOCKED' in e['text'] and 'Dmg blocked:' in e['text'] for e in bars),
        'summary_received_in_chat':log.count('Finished ')==len(games)>0 and log.count('Health/damage in hearts.')==len(games),
    }
    for g in games:
        es=events[g['id']]
        summary=plain.split('Finished '+g['id']+' ',1)[-1].split('Finished ',1)[0]
        expected_per_hit=[]
        # Reconstruct each accepted contact from independent native health/block events.
        pending=defaultdict(float);blocked=set();melee={}
        for e in es:
            if e['type']=='health_damage':pending[(e['attacker'],e['target'])]+=e['amount']
            elif e['type']=='blocked_damage':blocked.add((e['attacker'],e['target']))
            elif e['type']=='hit':
                pair=(e['attacker'],e['target']);loss=pending.pop(pair,0);was_blocked=pair in blocked;blocked.discard(pair)
                if not e['rod']:
                    melee[e['hits_a']+e['hits_b']]={'attacker':e['attacker'],'target':e['target'],'health_loss':loss,
                        'blocked':was_blocked,'critical':e['damage']==7.5}
        observed=[e for e in es if e['type']=='melee_damage']
        contacts={e['hit_index']:e for e in es if e['type']=='hit'}
        damage={e['hit_index']:e for e in observed}
        tricks=[e for e in es if e['type']=='rod_trick']
        for e in tricks:
            rod=contacts[e['rod_hit_index']];upgrade=damage[e['upgrade_hit_index']];fresh=damage[e['fresh_hit_index']]
            checks[g['id']+'_rod_trick_'+str(e['fresh_hit_index'])]=(
                rod['rod'] and rod['attacker']==upgrade['attacker']==fresh['attacker']==e['attacker'] and
                rod['target']==upgrade['target']==fresh['target']==e['target'] and
                e['rod_hit_index']<e['upgrade_hit_index']<e['fresh_hit_index'] and
                upgrade['sword'] and fresh['sword'] and upgrade['health_loss']>0 and fresh['health_loss']>0 and
                upgrade['immune_before'] and upgrade['previous_damage']==0 and not fresh['immune_before'] and
                0<=fresh['victim_tick']-upgrade['victim_tick']<=5)
        checks[g['id']+'_rod_tricks_not_duplicated']=len({e['rod_hit_index'] for e in tricks})==len(tricks)
        checks[g['id']+'_per_attack_damage_attribution']=(len(observed)==len(melee) and len({e['hit_index'] for e in observed})==len(melee) and all(
            e['hit_index'] in melee and all(e[k]==v if k!='health_loss' else abs(e[k]-v)<1e-6 for k,v in melee[e['hit_index']].items()) for e in observed))
        expected_benefits=[]
        checks[g['id']+'_shadow_damage_matches_native_hp']=bool(observed) and all(abs(e['predicted_health_loss']-e['health_loss'])<1e-6 for e in observed)
        checks[g['id']+'_benefit_difference_and_scope']=bool(observed) and all(
            abs(e['blocking_saved']-(max(0,e['without_block_health_loss']-e['health_loss']) if e['blocked'] else 0))<1e-6 and
            abs(e['critical_extra']-(max(0,e['health_loss']-e['without_crit_health_loss']) if e['critical'] else 0))<1e-6 for e in observed)
        for side in ('a','b'):
            name=g[side];stats=g['stats_'+side];hits=[e for e in es if e['type']=='hit' and e['attacker']==name]
            checks[g['id']+'_'+side+'_rod_tricks_match_events']=stats['rod_tricks']==sum(e['attacker']==name for e in tricks)
            healed=sum(e['amount'] for e in es if e['type']=='heal' and e['player']==name)
            checks[g['id']+'_'+side+'_health_balance']=abs(20+stats['regenerated']-stats['damage_taken']-g['health_'+side])<.0001
            checks[g['id']+'_'+side+'_native_crit_damage_matches']=stats['crits']==sum(e['damage']==7.5 for e in hits)
            checks[g['id']+'_'+side+'_counters_match_events']=(g['hits_'+side]==len(hits) and stats['rod_hits']==sum(e['rod'] for e in hits)
                and stats['damage_upgrades']==sum(e['damage_upgrade'] for e in hits) and abs(stats['regenerated']-healed)<.0001
                and g['blocks_'+side]==sum(e['type']=='blocked_damage' and e['target']==name for e in es))
            opponent=g['b' if side=='a' else 'a']
            incoming=[e for e in es if e['type']=='hit' and e['attacker']==opponent and e['target']==name and not e['rod']]
            actual_loss=sum(e['amount'] for e in es if e['type']=='health_damage' and e['attacker']==opponent and e['target']==name)
            average=actual_loss/len(incoming) if incoming else None
            checks[g['id']+'_'+side+'_damage_per_hit_matches_native_events']=(stats['melee_hits_taken']==len(incoming) and
                (stats['damage_taken_per_hit'] is None if average is None else abs(stats['damage_taken_per_hit']-average)<.000001))
            # Java Formatter rounds decimal ties up (Python's float formatting can round .575 down).
            shown_average=None if average is None else Decimal(str(average/2)).quantize(Decimal('.01'),rounding=ROUND_HALF_UP)
            expected_per_hit.append('N/A (no melee hits)' if average is None else f'{shown_average} hearts ({len(incoming)} melee hits)')
            for key,label,direction,flag,flag_value in (
                ('blocked_taken','Taken on blocks','target','blocked',True),('unblocked_taken','Taken unblocked','target','blocked',False),
                ('critical_dealt','Dealt by crits','attacker','critical',True),('normal_dealt','Dealt by normal hits','attacker','critical',False)):
                cohort=[e for e in melee.values() if e[direction]==name and e[flag]==flag_value]
                damage=sum(e['health_loss'] for e in cohort);count=len(cohort);group=stats['damage_breakdown'][key]
                checks[g['id']+'_'+side+'_'+key]=(group['hits']==count and abs(group['damage']-damage)<1e-6 and
                    (group['damage_per_hit'] is None if not count else abs(group['damage_per_hit']-damage/count)<1e-6))
            breakdown=stats['damage_breakdown']
            normal_hits=sum(e['blocking_saved_normal_hits'] for e in observed if e['target']==name and e['blocked'])
            checks[g['id']+'_'+side+'_normal_hit_equivalence']=(abs(breakdown['blocking_saved_normal_hits']-normal_hits)<1e-6 and all(
                e['noncrit_raw_damage']==5 and e['protection_roll']==0 and e['normal_hit_damage']==1.0 and
                abs(e['blocking_saved_normal_hits']-e['blocking_saved']/e['normal_hit_damage'])<1e-6 for e in observed if e['target']==name))
            saved=breakdown['blocking_saved']['damage'];taken_damage=breakdown['blocked_taken']['damage']+breakdown['unblocked_taken']['damage']
            damage_percent=100*saved/(taken_damage+saved) if taken_damage+saved else None
            checks[g['id']+'_'+side+'_damage_block_fraction']=(stats['damage_blocked_percent'] is None if damage_percent is None else abs(stats['damage_blocked_percent']-damage_percent)<1e-8)
            block_percent=100*g['blocks_'+side]/len(incoming) if incoming else None
            checks[g['id']+'_'+side+'_hit_block_fraction']=(stats['block_percent'] is None if block_percent is None else abs(stats['block_percent']-block_percent)<1e-8)
            checks[g['id']+'_'+side+'_group_totals_reconcile']=(
                abs(breakdown['blocked_taken']['damage']+breakdown['unblocked_taken']['damage']-(stats['damage_taken']-stats['other_damage']))<1e-6 and
                abs(breakdown['critical_dealt']['damage']+breakdown['normal_dealt']['damage']-stats['damage_dealt'])<1e-6 and
                breakdown['blocked_taken']['hits']==g['blocks_'+side] and breakdown['critical_dealt']['hits']==stats['crits'])
            landed=sum(e['attacker']==name and e['health_loss']>0 for e in observed)
            taken=sum(e['target']==name and e['health_loss']>0 for e in observed)
            checks[g['id']+'_'+side+'_damage_only_headline']=(g['damage_hits_'+side]==landed and
                breakdown['damage_hits_landed']==landed and breakdown['damage_hits_taken']==taken)
            label=('You (LabObserver)' if side=='a' else g['policy_b']+' (bot)')
            header=next((line for line in summary.splitlines() if label+' ' in line),'')
            checks[g['id']+'_'+side+'_headline_chat_excludes_rods']=f'Hits: {landed}' in header and (not g['fishing_rods'] or f'Rod contacts: {stats["rod_hits"]}' in header)
            for group_key,field,direction,flag,total_count,per_all_key,title,per_label,all_label in (
                ('blocking_saved','blocking_saved','target','blocked',taken,'blocking_saved_per_received_hit','Blocking saved','Per block','Per received hit'),
                ('critical_extra','critical_extra','attacker','critical',landed,'critical_extra_per_landed_hit','Extra crit damage','Per crit','Per landed hit')):
                cohort=[e for e in observed if e[direction]==name and e[flag]];total=sum(e[field] for e in cohort);count=len(cohort)
                group=breakdown[group_key]
                checks[g['id']+'_'+side+'_'+group_key]=(group['hits']==count and abs(group['damage']-total)<1e-6 and
                    (group['damage_per_hit'] is None if count==0 else abs(group['damage_per_hit']-total/count)<1e-6) and
                    (breakdown[per_all_key] is None if total_count==0 else abs(breakdown[per_all_key]-total/total_count)<1e-6))
                per=f'{total/count/2:.3f}' if count else 'N/A';overall=f'{total/total_count/2:.3f}' if total_count else 'N/A'
                equivalent=f' ({normal_hits:.2f} normal hits)' if group_key=='blocking_saved' else ''
                expected_benefits.extend([f'{title}: {total/2:.2f} hearts'+equivalent,f'{per_label}: {per} | {all_label}: {overall}'])
        # The native logger wraps chat in an XML CDATA element; only compare the message.
        received=[value.split(']]>',1)[0] for value in re.findall(r'Damage taken per hit: ([^\r\n]*)',summary)]
        checks[g['id']+'_both_damage_per_hit_chat_values']=received==expected_per_hit
        shown_tricks=[int(x) for x in re.findall(r'Rod tricks: (\d+)',summary)]
        checks[g['id']+'_rod_trick_chat_values']=shown_tricks==([g['stats_a']['rod_tricks'],g['stats_b']['rod_tricks']] if g['fishing_rods'] else [])
        received_benefits=[value.split(']]>',1)[0] for value in re.findall(r'((?:Blocking saved|Extra crit damage|Per block|Per crit): [^\r\n]*)',summary)]
        checks[g['id']+'_both_benefit_chat_values']=received_benefits==expected_benefits
    report={'checks':checks,'passed':all(checks.values()),'games':games,'script':script,'source_sha256':frozen,'agent_sha256':agent_hash,
            'rod_contacts':len(rods),'criticals':len(crits),'blocks':len(blocks),'chimes':len(sounds),'food_samples':len(samples)}
    (out/'food-samples.json').write_text(json.dumps(samples,indent=2)+'\n')
    (out/'rod-inputs.json').write_text(json.dumps([e for e in client if e.get('rod_input')],indent=2)+'\n')
    (out/'action-bars.json').write_text(json.dumps(bars,indent=2)+'\n')
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'directory':str(out),'checks':checks,'rod_contacts':len(rods),'criticals':len(crits)},indent=2),flush=True)
    if not report['passed']:raise RuntimeError('Native statistics verification failed; inspect '+str(out))


if __name__=='__main__':run()
