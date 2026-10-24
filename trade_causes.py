#!/usr/bin/env python3
"""Offline attribution of sprint consumption, key release and native click outcomes."""
import argparse
import bisect
import collections
import json
import math
import struct
from science import OUT,client_events,server_events


def classify(e):
    if isinstance(e['score_before'],list) and max(e['score_before'])>=100:
        return 'after_winning_score'
    if e['score_before']==e['score_after']:return 'immune'
    return 'fresh' if e['target_before']['hurtResistantTime']<=10 else 'upgrade'


def analyze(game):
    raw=server_events(game['id'])
    attacks=[dict(e,log_index=i) for i,e in enumerate(raw) if e['type']=='science_server_attack']
    report={'match':game['id'],'sides':{}}
    for side,other in (('a','b'),('b','a')):
        player=game[side];native=client_events(game,side)
        ours=[e for e in attacks if e['attacker']==player]
        contacts=[e for e in native if e['type']=='engine_attack']
        clicks=[e for e in native if e['type']=='science_click']
        movements=[e for e in native if e['type']=='science_move']
        counts=collections.Counter();counts['native_click_probe_available']=int(bool(clicks));examples=[];upgrades=[];last_fresh=None
        for e in ours:
            kind=classify(e);counts[kind+'_attempts']+=1
            if kind=='upgrade':
                upgrades.append(e)
                if e['attacker_before']['sprint'] and not e['attacker_after']['sprint']:
                    counts['upgrade_consumed_sprint']+=1
                    if 'velocity_packet' in e:counts['upgrade_with_velocity_packet']+=1
            if kind=='fresh':
                if not e['attacker_before']['sprint']:
                    counts['fresh_without_sprint']+=1
                    consumed=[u for u in upgrades if u['attacker_before']['sprint'] and not u['attacker_after']['sprint']]
                    actions=[] if not consumed else [x['action'] for x in raw[consumed[-1]['log_index']+1:e['log_index']]
                        if x['type']=='replication_server_input' and x['player']==player and x['packet_kind']=='is']
                    if consumed:
                        counts['fresh_without_sprint_after_upgrade']+=1
                        if 'START_SPRINTING' not in actions:counts['fresh_without_sprint_after_upgrade_no_restart']+=1
                    if len(examples)<5:
                        examples.append({'previous_fresh_ms':None if last_fresh is None else last_fresh['time']-game['start'],
                            'upgrade_ms':[u['time']-game['start'] for u in consumed],
                            'fresh_ms':e['time']-game['start'],
                            'server_actions_after_upgrade':actions})
                last_fresh=e;upgrades=[]
        # Match every C02 arrival, including native handler rejections that never
        # enter EntityPlayer.attackTargetEntityWithCurrentItem. Skipping them
        # would shift all subsequent contact/result associations.
        inputs=[(i,e) for i,e in enumerate(raw) if e['type']=='replication_server_input']
        boundaries=[i for i,e in inputs];attack_indices=[e['log_index'] for e in ours]
        incoming=[(i,e) for i,e in inputs if e['player']==player and e['packet_kind']=='in' and e['action']=='ATTACK']
        linked={};valid=len(incoming)<=len(contacts)
        for contact,(idx,received) in zip(contacts,incoming):
            delay=received['time']-contact['time']
            # Client after-hook can finish after send/receipt; use a generous
            # diagnostic check, never fit a timing offset to repair FIFO order.
            if delay < -50 or delay>1000:valid=False
            boundary=boundaries[bisect.bisect_right(boundaries,idx)] if bisect.bisect_right(boundaries,idx)<len(boundaries) else len(raw)
            ai=bisect.bisect_right(attack_indices,idx)
            attack=ours[ai] if ai<len(ours) and ours[ai]['log_index']<boundary else None
            if 'click_attempt' in contact:linked[contact['click_attempt']]=attack
            if attack is None:counts['server_rejected_before_attack']+=1
        counts['fifo_link_valid']=int(valid)
        if not valid:linked={}
        def click_outcome(c):
            if c['miss_cooldown_before']>0:return 'cooldown'
            if not c['selected_target']:return 'ray_miss'
            if c['sequence'] not in linked:return 'unmatched_contact'
            a=linked[c['sequence']]
            return 'server_rejected' if a is None else classify(a)
        release_lengths=[];reset_outcomes=[]
        for reset in (e for e in native if e['type']=='wtap'):
            start=next((i for i,m in enumerate(movements) if
                        (m['tick']==reset['native_tick'] if 'native_tick' in reset else m['time']>=reset['time'])),None)
            if start is None:continue
            end=start
            while end<len(movements) and movements[end]['forward']==0:end+=1
            length=end-start;release_lengths.append(length)
            tick=movements[start]['tick'];cs=[e for e in clicks if e['tick']==tick]
            outcomes=[]
            for c in cs:
                outcomes.append(click_outcome(c))
            reset_outcomes.append({'time_ms':reset['time']-game['start'],'cue_age_ms':reset['time']-reset['cue_time'],
                                   'movement_ticks_released':length,'click_outcomes':outcomes})
            for outcome in outcomes:counts['reset_on_'+outcome]+=1
            if clicks and not outcomes:counts['reset_without_queued_click']+=1
        click_outcomes=[]
        for c in clicks:
            a=linked.get(c['sequence']);outcome=click_outcome(c)
            counts['native_click_'+outcome]+=1
            own,target=c['own'],c['target']
            eye=[own['posX'],own['posY']+struct.unpack('f',struct.pack('f',1.62))[0],own['posZ']]
            border=struct.unpack('f',struct.pack('f',.1))[0];box=target['box']
            d=math.sqrt(sum(max(box[i]-border-eye[i],eye[i]-box[i+3]-border,0)**2 for i in range(3)))
            click_outcomes.append({'t':c['time']-game['start'],'tick':c['tick'],'outcome':outcome,'selected':c['selected_target'],
                'cooldown':c['miss_cooldown_before'],'closest_box_distance':d,
                'own_y':own['posY'],'target_y':target['posY'],
                'server_t':None if a is None else a['time']-game['start'],
                'server_target_immunity':None if a is None else a['target_before']['hurtResistantTime']})
        report['sides'][side]={'counts':dict(counts),'release_tick_histogram':dict(collections.Counter(release_lengths)),
                               'sprint_loss_examples':examples,'reset_outcomes':reset_outcomes,'native_clicks':click_outcomes}
    return report


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('suite');a=p.parse_args()
    source=json.loads((OUT/(a.suite+'.json')).read_text())
    report={'suite':a.suite,'results':[analyze(r['game']) for r in source['results']]}
    (OUT/(a.suite+'-causes.json')).write_text(json.dumps(report,indent=2)+'\n')
    for r in report['results']:
        print(json.dumps({'match':r['match'],'sides':{s:{'counts':x['counts'],'release_tick_histogram':x['release_tick_histogram']} for s,x in r['sides'].items()}}))
