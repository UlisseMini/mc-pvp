#!/usr/bin/env python3
"""Attribute recorded trade returns without treating sampled misses as continuous denial.

All input/consumption associations use same-client FIFO and monotonic time.
Contacts map to server C02 ATTACK inputs in FIFO order, including rejected C02s.
No counterfactual packet arrival is invented for a native ray miss.
"""
import argparse
import bisect
import collections
import hashlib
import json
import math
import struct
from pathlib import Path
import trade_grounding as grounding

ROOT = Path(__file__).resolve().parent
F32 = lambda x: struct.unpack('f', struct.pack('f', x))[0]


def box_distance(click):
    eye = [click['own']['posX'], click['own']['posY']+F32(1.62), click['own']['posZ']]
    box = click['target']['box']; border = F32(.1)
    return math.sqrt(sum(max(box[i]-border-eye[i], eye[i]-box[i+3]-border, 0)**2 for i in range(3)))


def attack_kind(event):
    if event is None:
        return 'server_rejected_before_attack'
    if max(event['score_before']) >= 100:
        return 'after_winning_score'
    if event['score_before'] == event['score_after']:
        return 'immune'
    return 'fresh' if event['target_before']['hurtResistantTime'] <= 10 else 'upgrade'


def link_clicks(raw, native, game, side):
    player = game[side]; t0 = game['start']; errors = []
    injections = [e for e in native if e['type'] == 'click']
    clicks = [e for e in native if e['type'] == 'science_click']
    contacts = [e for e in native if e['type'] == 'engine_attack']
    inputs = [(i, e) for i, e in enumerate(raw) if e['type'] == 'replication_server_input']
    boundaries = [i for i, _ in inputs]
    receives = [(i, e) for i, e in inputs if e['player'] == player
                and e['packet_kind'] == 'in' and e['action'] == 'ATTACK']
    attacks = [(i, e) for i, e in enumerate(raw) if e['type'] == 'science_server_attack' and e['attacker'] == player]
    attack_indices = [i for i, _ in attacks]
    if len(clicks) > len(injections):
        errors.append('more_consumptions_than_injections')
    if len(receives) > len(contacts):
        errors.append('more_server_C02_than_local_contacts')
    injection_by_click = {}
    for injection, click in zip(injections, clicks):
        delay = click['monotonic_ns']-injection['monotonic_ns']
        if not 0 <= delay <= 1_000_000_000:
            errors.append('invalid_injection_consumption_FIFO_delay')
        injection_by_click[click['sequence']] = injection
    contact_by_click = {}; by_attack = {}
    for contact, (idx, receive) in zip(contacts, receives):
        if not -50 <= receive['time']-contact['time'] <= 1000:
            errors.append('invalid_contact_C02_FIFO_delay')
        end = bisect.bisect_right(boundaries, idx)
        boundary = boundaries[end] if end < len(boundaries) else len(raw)
        j = bisect.bisect_right(attack_indices, idx)
        attack = attacks[j] if j < len(attacks) and attacks[j][0] < boundary else None
        if 'click_attempt' not in contact:
            errors.append('contact_missing_click_id'); continue
        contact_by_click[contact['click_attempt']] = (contact, receive, attack)
    result = []
    for click in clicks:
        injection = injection_by_click.get(click['sequence'])
        linked = contact_by_click.get(click['sequence'])
        contact, receive, attack = linked if linked else (None, None, None)
        event = attack[1] if attack else None
        if click['miss_cooldown_before'] > 0:
            kind = 'cooldown'
        elif not click['selected_target']:
            kind = 'ray_miss'
        elif linked is None:
            kind = 'unmatched_contact'
        else:
            kind = attack_kind(event)
        row = {'sequence': click['sequence'], 'native_tick': click['tick'],
               'injection_ms': None if injection is None else injection['time']-t0,
               'injection_monotonic_ns':None if injection is None else injection['monotonic_ns'],
               'consumption_ms': click['time']-t0, 'consumption_monotonic_ns':click['monotonic_ns'],
               'injection_to_consumption_ms': None if injection is None else (click['monotonic_ns']-injection['monotonic_ns'])/1e6,
               'outcome': kind, 'cached_ray_selected_target': click['selected_target'],
               'miss_cooldown': click['miss_cooldown_before'],
               'current_box_min_distance': box_distance(click),
               'own_y': click['own']['posY'], 'remote_y': click['target']['posY'],
               'horizontal_gap': math.hypot(click['own']['posX']-click['target']['posX'],click['own']['posZ']-click['target']['posZ']),
               'local_sprint': click['own']['sprint'],
               'server_receive_ms': None if receive is None else receive['time']-t0,
               'server_attack_ms': None if event is None else event['time']-t0,
               'server_attack_tick': None if event is None else event['server_tick'],
               'exact_server_victim_H': None if event is None else event['target_before']['hurtResistantTime'],
               'server_attack_index': None if attack is None else attack[0]}
        if contact:
            row['native_cached_ray_distance']=contact.get('native_raycast_distance')
            row['contact_drag'] = {k: contact[k] for k in ('vx_before','vz_before','vx_after','vz_after','sprint_before','sprint_after')}
        if attack:
            by_attack[attack[0]] = row
        result.append(row)
    return result, by_attack, {'errors': sorted(set(errors)), 'injections':len(injections),
                              'consumptions':len(clicks),'contacts':len(contacts),'server_C02':len(receives),
                              'unconsumed_tail':len(injections)-len(clicks),'unreceived_tail':len(contacts)-len(receives)}


def return_status(click, a_repeat):
    if click is None or a_repeat is None:
        return 'unavailable'
    deadline = a_repeat['server_attack_ms']
    if click['server_attack_tick'] == a_repeat['server_attack_tick']:
        return 'same_server_tick_return'
    if click['server_attack_ms'] <= deadline:
        return 'return_already_accepted'
    if click['server_receive_ms'] <= deadline:
        return 'server_received_pending_attack'
    if click['consumption_ms'] <= deadline:
        return 'contact_already_sent_in_flight'
    if click['injection_ms'] <= deadline:
        return 'physical_input_already_queued'
    return 'physical_input_not_yet_issued'


def analyze(match, reply_ms=150, horizon_ms=850):
    ground = grounding.analyze(match, 75, reply_ms, horizon_ms)
    raw, _ = grounding.read(match, 'server')
    natives = {s: grounding.read(match, s)[0] for s in ('a','b')}
    game = next(e['match'] for e in raw if e['type'] == 'start'); t0 = game['start']
    finish = next(e['result']['finish'] for e in raw if e['type'] == 'finish')-t0
    linked, attack_links, diagnostics = {}, {}, {}
    for s in ('a','b'):
        linked[s], attack_links[s], diagnostics[s] = link_clicks(raw, natives[s], game, s)
    if any(d['errors'] for d in diagnostics.values()):
        raise ValueError(f'{match}: invalid event linkage {diagnostics}')
    fresh = [(i,e) for i,e in enumerate(raw) if grounding.fresh(e)]
    tick_states = [(e['time']-t0,e['a']['hurtResistantTime'],i) for i,e in enumerate(raw) if e['type']=='tick' and e['time']>=t0]
    tick_times = [t for t,_,_ in tick_states]
    def H_at(t):
        j=bisect.bisect_right(tick_times,t)-1
        return None if j<0 else {'snapshot_ms':tick_states[j][0],'H':tick_states[j][1],
                                'server_log_index':tick_states[j][2]}
    # Include status-2 sends so non-sword fresh damage (notably falling) also
    # invalidates an old low-H snapshot. Server log order resolves equal-ms
    # snapshot/send ties; ordering relative to a different JVM remains bounded
    # only by the recorded wall clock, never by cross-JVM monotonic timestamps.
    a_damage_events = [(i,e['time']-t0) for i,e in enumerate(raw)
                       if ((grounding.fresh(e) and e['target']==game['a'])
                           or (e['type']=='replication_send' and e.get('target')==game['a']
                               and e.get('packet',{}).get('kind')=='gi'
                               and e['packet'].get('status')==2))]
    def snapshot_eligible(t):
        h=H_at(t)
        return (h is not None and h['H']<=10
                and not any(i>h['server_log_index'] and d<=t for i,d in a_damage_events))
    b_velocity_links,_=grounding.link_side(raw,natives['b'],game,'b')
    b_apply_by_id={e['packet_id']:e for e in natives['b'] if e['type']=='replication_apply_after'}
    b_moves_by_tick={e['tick']:e for e in natives['b'] if e['type']=='science_move'}
    pairs=[]
    for pair in ground['pairs']:
        ai,a=next((i,e) for i,e in fresh if e['attacker']==game['a'] and e['time']-t0==pair['A_fresh_ms'])
        bi,b=next((i,e) for i,e in fresh if e['attacker']==game['b'] and e['time']-t0==pair['B_fresh_ms'])
        end_index=max(ai,bi); begin=max(pair['A_fresh_ms'],pair['B_fresh_ms'])
        groups=[]
        for idx,e in fresh:
            if idx<=end_index:continue
            if not groups or groups[-1][0]['server_tick']!=e['server_tick']:groups.append([])
            groups[-1].append(e)
        strong=len(groups)>=2 and all(all(e['attacker']==game['a'] for e in g) for g in groups[:2])
        strong_censored=(not groups or (len(groups)==1 and all(e['attacker']==game['a'] for e in groups[0])))
        strong_end=(max(e['time'] for e in groups[1])-t0 if len(groups)>=2 else finish)
        strong_short=strong and groups[0][0]['time']-t0-begin<=horizon_ms and groups[1][0]['time']-groups[0][0]['time']<=horizon_ms
        na=next(((i,e) for i,e in fresh if i>end_index and e['attacker']==game['a']),None)
        nb=next(((i,e) for i,e in fresh if i>end_index and e['attacker']==game['b']),None)
        ac=attack_links['a'].get(na[0]) if na else None;bc=attack_links['b'].get(nb[0]) if nb else None
        end=min(finish,begin+horizon_ms,(na[1]['time']-t0+reply_ms) if na else finish)
        clicks=[]
        for c in linked['b']:
            if begin<c['consumption_ms']<=end:
                cc=dict(c);cc['A_last_completed_server_tick']=H_at(c['consumption_ms'])
                cc['A_eligible_by_prior_server_snapshot']=snapshot_eligible(c['consumption_ms'])
                clicks.append(cc)
        eligible_ticks=[(t,h) for t,h,_ in tick_states if begin<t<=end and h<=10]
        first_open=eligible_ticks[0][0] if eligible_ticks else None
        probes=[{'time_ms':p['time']-t0,'in_range':p['in_range'],'could_attempt':p['could_attempt'],
                 'miss_cooldown':p['miss_cooldown'],'A_eligible_by_prior_server_snapshot':snapshot_eligible(p['time']-t0)}
                for p in natives['b'] if p['type']=='reach_probe' and begin<p['time']-t0<=end]
        witnesses=[p for p in probes if p['could_attempt'] and p['A_eligible_by_prior_server_snapshot']]
        misses=[c for c in clicks if c['outcome']=='ray_miss' and c['A_eligible_by_prior_server_snapshot']]
        if pair['outcome']!='A_double_with_denial':
            classification='return_or_no_timely_A_repeat'
        elif witnesses:
            classification='double_despite_observed_nonclick_reach_opportunity'
        elif misses:
            classification='double_with_observed_eligible_click_misses_only'
        else:
            classification='double_without_confirmed_eligible_click_sample'
        # Link A-repeat's S12 to the defender; compare the fresh return contact to
        # application, not merely its eventual server scoreboard event.
        repeat_apply=None;repeat_travel=None;contact_phase=None;return_at_apply=None
        if na:
            repetition=grounding.attach_hit(raw,na[0],na[1],b_velocity_links)
            if not repetition['flags']:
                repeat_apply=repetition['apply_ms'];repeat_travel=repetition['first_travel_ms']
                if bc:
                    app_ns=b_apply_by_id[repetition['packet_id']]['monotonic_ns']
                    move_ns=b_moves_by_tick[repetition['first_travel_tick']]['monotonic_ns']
                    c_ns=bc['consumption_monotonic_ns']
                    contact_phase=('before_S12_application' if c_ns<app_ns else
                                   'after_S12_before_first_travel' if c_ns<move_ns else
                                   'at_or_after_first_post_S12_travel')
                    if bc['server_attack_ms']<=repeat_apply:return_at_apply='return_already_accepted'
                    elif bc['server_receive_ms']<=repeat_apply:return_at_apply='server_received_pending_attack'
                    elif c_ns<app_ns:return_at_apply='contact_already_sent_in_flight'
                    elif bc['injection_monotonic_ns']<=app_ns:return_at_apply='physical_input_already_queued'
                    else:return_at_apply='physical_input_not_yet_issued'
        row={'A_fresh_ms':pair['A_fresh_ms'],'B_fresh_ms':pair['B_fresh_ms'],
             'post_S12_ground_A_B':pair['state_A_B'],'outcome':pair['outcome'],
             'return_window_class':classification,'window_start_ms':begin,'window_end_ms':end,
             'first_completed_server_tick_A_H_le_10_ms':first_open,
             'A_repeat':ac,'B_next_fresh':bc,'B_return_status_at_A_repeat':return_status(bc,ac),
             'cross_JVM_ms_ties_at_A_repeat':[] if not bc or not ac else [k for k in ('injection_ms','consumption_ms','server_receive_ms','server_attack_ms') if abs(bc[k]-ac['server_attack_ms'])<=1],
             'A_repeat_S12_applied_to_B_ms':repeat_apply,
             'A_repeat_first_post_S12_B_travel_ms':repeat_travel,
             'B_fresh_contact_phase_relative_to_A_repeat_KB':contact_phase,
             'B_return_status_at_A_repeat_S12_apply':return_at_apply,
             'B_fresh_contact_before_A_repeat_S12':None if bc is None or repeat_apply is None else bc['consumption_ms']<repeat_apply,
             'B_clicks':clicks,'B_range_probes':probes,'eligible_range_witness_count':len(witnesses),
             'confirmed_eligible_ray_miss_count':len(misses),
             'reset_ms':[e['time']-t0 for e in natives['a'] if e['type']=='wtap' and begin<e['time']-t0<=end],
             'strong_true_double':strong,'strong_right_censored':strong_censored,
             'strong_both_gaps_le_850ms':strong_short,
             'next_fresh_tick_groups':[[{'time_ms':e['time']-t0,'server_tick':e['server_tick'],
                  'side':'a' if e['attacker']==game['a'] else 'b','sprint':e['attacker_before']['sprint']} for e in g] for g in groups[:3]],
             'strong_window_end_ms':strong_end,
             'B_clicks_through_two_next_fresh_groups':[dict(c,A_eligible_by_prior_server_snapshot=snapshot_eligible(c['consumption_ms']))
                  for c in linked['b'] if begin<c['consumption_ms']<=strong_end],
             'strong_window_positive_reach_witnesses':[{'time_ms':p['time']-t0,'miss_cooldown':p['miss_cooldown']}
                  for p in natives['b'] if p['type']=='reach_probe' and begin<p['time']-t0<=strong_end
                  and p['could_attempt'] and snapshot_eligible(p['time']-t0)]}
        pairs.append(row)
    return {'match':match,'policy_a':game['policy_a'],'rtt':[game['ping_a'],game['ping_b']],
            'trace_sha256':ground['trace_sha256'],'click_link_diagnostics':diagnostics,'pairs':pairs}


def focused_geometry():
    """Fixed-eye/current-rendered-box geometric requirement, not a trajectory counterfactual."""
    match='m0404';raw,_=grounding.read(match,'server')
    game=next(e['match'] for e in raw if e['type']=='start');t0=game['start']
    a,_=grounding.read(match,'a');b,_=grounding.read(match,'b')
    c=next(e for e in b if e['type']=='science_click' and e['time']-t0==13250)
    eye=[c['own']['posX'],c['own']['posY']+F32(1.62),c['own']['posZ']]
    box=[c['target']['box'][i]+(-1 if i<3 else 1)*F32(.1) for i in range(6)]
    point=[min(max(eye[i],box[i]),box[i+3]) for i in range(3)]
    v=[point[i]-eye[i] for i in range(3)]
    length=math.hypot(v[0],v[2]);nearest=[v[0]/length,0,v[2]/length]
    movement=next(e for e in a if e['type']=='science_move' and e['time']-t0==13100)
    yaw=movement['before']['rotationYaw']
    radians=F32(F32(F32(yaw)*F32(math.pi))/F32(180))
    angle=F32(radians*F32(10430.378))
    si=int(angle)&65535;ci=int(F32(angle+F32(16384)))&65535
    sine=F32(math.sin(si*math.pi*2/65536));cosine=F32(math.sin(ci*math.pi*2/65536))
    length=math.hypot(sine,cosine);coast=[sine/length,0,-cosine/length]
    def threshold(direction):
        def distance(delta):
            return math.sqrt(sum(max(box[i]+delta*direction[i]-eye[i],eye[i]-box[i+3]-delta*direction[i],0)**2 for i in range(3)))
        lo,hi=0.,1.
        for _ in range(80):
            mid=(lo+hi)/2
            if distance(mid)<=3:lo=mid
            else:hi=mid
        return hi
    d1,d2=threshold(nearest),threshold(coast)
    return {'match':match,'B_click_ms':13250,'eye':eye,'expanded_A_box':box,
            'nearest_point':point,'nearest_distance':math.sqrt(sum(x*x for x in v)),
            'native_cached_ray_distance':next(e['native_raycast_distance'] for e in b if e['type']=='engine_attack' and e['time']-t0==13250),
            'vertical_gap':abs(v[1]),'nearest_horizontal_away_direction':nearest,
            'translation_to_distance_3_along_nearest_horizontal':d1,
            'A_13100_yaw':yaw,'A_native_table_coast_away_direction':coast,
            'translation_to_distance_3_along_A_coast_axis':d2,
            'coast_axis_translation_vector':[d2*x for x in coast],
            'qualification':'Strict denial requires distance>3. These are translations of B-rendered A box with B eye fixed. Actual own-body movement must propagate through generated packets and native interpolation; cached-ray miss alone is weaker.'}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--matches',nargs='+',default=['m0396']+[f'm{i:04d}' for i in range(403,409)])
    p.add_argument('--output',type=Path,default=ROOT/'research/trade-return-windows.json')
    a=p.parse_args();results=[analyze(m) for m in a.matches]
    counts=collections.defaultdict(collections.Counter)
    for r in results:
        for q in r['pairs']:
            group='case_m0396' if r['match']=='m0396' else 'confirmation'
            counts[group]['paired_trades']+=1
            counts[group]['strong_true_double']+=int(q['strong_true_double'])
            counts[group]['strong_right_censored']+=int(q['strong_right_censored'])
            counts[group]['strong_both_gaps_le_850ms']+=int(q['strong_both_gaps_le_850ms'])
            if q['strong_true_double']:
                counts[group]['strong_with_positive_reach_witness']+=int(bool(q['strong_window_positive_reach_witnesses']))
            counts[group]['delay_proxy:'+q['outcome']]+=1
            counts[group][q['return_window_class']]+=1
            if q['A_repeat'] and q['B_next_fresh']:
                counts[group]['at_A_repeat:'+q['B_return_status_at_A_repeat']]+=1
            if q['outcome']=='prompt_return' and q['B_fresh_contact_phase_relative_to_A_repeat_KB']:
                counts[group]['prompt_return_contact:'+q['B_fresh_contact_phase_relative_to_A_repeat_KB']]+=1
                counts[group]['prompt_return_at_S12:'+q['B_return_status_at_A_repeat_S12_apply']]+=1
    report={'description':'Exact recorded physical clicks/contacts/packet outcomes; sampled reach is not continuous denial.',
            'script_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            'definitions':{'trade':'Unique paired fresh attacks within75ms, from trade_grounding.',
                'primary_strong_true_double':'After the paired trade, the next two nonempty fresh-attack server-tick groups contain A only: A2 then A3 before B2. Any same-tick B reply defeats this definition. No time horizon; intervals and an additional850ms-both-gaps flag are reported.',
                'delay_proxy_only':'A repeats within850ms after the trade and B does not repeat through A+150ms; same-server-tick returns excluded.',
                'eligibility':'Exact H is known at actual server attacks. For misses/probes, only the preceding completed server-tick H is shown; no hypothetical server arrival is fitted.',
                'reach_witness':'A positive native probe with cooldown clear, while the preceding completed server tick has A.H<=10 and no intervening recorded fresh attack or status-2 send to A (including non-sword damage). This witnesses a local contact opportunity, not a guaranteed hypothetical fresh packet.',
                'limitations':'Probes are sampled, cached ray differs from newly recomputed geometry, and server snapshots/probes are aligned by cross-JVM wall milliseconds. Hypothetical packet-arrival acceptance is untested. Negative probes never certify continuous denial. Initial click-phase changes also change drag and future trajectory.'},
            'aggregate':{k:dict(v) for k,v in counts.items()},'focused_geometry':focused_geometry(),'results':results}
    a.output.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'output':str(a.output),'aggregate':report['aggregate']},indent=2))


if __name__=='__main__':main()
