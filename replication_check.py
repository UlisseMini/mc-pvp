#!/usr/bin/env python3
"""Offline exact component checks and transport timing, never policy input.

Uses recorded tracker pre-state or packet payload only for the corresponding
conditional test. This is deliberately not called a full forward duel replay.
"""
import argparse
import collections
import json
import math
import statistics
from pathlib import Path
from parity import bits
from science import OUT, client_events, server_events


def stats(values):
    values=sorted(values)
    return {'n':len(values),**({'min':values[0],'median':statistics.median(values),'p95':values[min(len(values)-1,int(.95*len(values)))],'max':values[-1]} if values else {})}


def validate(result):
    counts=collections.Counter(); failures=collections.Counter(); examples=[]
    delays=collections.defaultdict(list)
    def check(label,predicted,actual,event):
        counts[label]+=1
        if isinstance(actual,list): same=len(predicted)==len(actual) and all(bits(a)==bits(b) for a,b in zip(predicted,actual))
        elif isinstance(actual,bool): same=predicted==actual
        else: same=bits(predicted)==bits(actual)
        if not same:
            failures[label]+=1
            if len(examples)<10:examples.append({'check':label,'time':event['time'],'predicted':predicted,'native':actual})
    raw=server_events(result['id']); before={}
    for e in raw:
        typ=e['type']
        if typ=='replication_tracker_before':before[e['tracker']['entity_id']]=e
        elif typ=='replication_tracker_after':
            t=e['tracker'];b=before.pop(t['entity_id'])['tracker']
            if e['time']<result['start']:continue
            counter=int(b['updateCounter']);gate=counter%int(b['updateFrequency'])==0 or b['isAirBorne'] or b['watcher_dirty']
            check('tracker_counter',counter+1,t['updateCounter'],e)
            counts['tracker_gate_open' if gate else 'tracker_gate_closed']+=1
            if counter%int(b['updateFrequency']) and gate:counts['tracker_extra_gate_open']+=1
            pos=[math.floor(x*32) for x in b['position']]
            old=[b['encodedPos'+a] for a in 'XYZ']
            moved=any(abs(x-y)>=4 for x,y in zip(pos,old)) or counter%60==0
            check('tracker_position_accumulator',pos if gate and moved else old,[t['encodedPos'+a] for a in 'XYZ'],e)
        elif typ=='replication_send' and 'tracker_at_send' in e and e['time']>=result['start']:
            t=e['tracker_at_send'];p=e['packet'];k=p['kind']
            if k.startswith('gv') or k=='hz':
                gate=int(t['updateCounter'])%int(t['updateFrequency'])==0 or t['isAirBorne'] or t['watcher_dirty']
                check('position_send_gate',True,gate,e)
                if int(t['updateCounter'])%int(t['updateFrequency']):counts['off_period_position_sends']+=1
                if k in ('gv$a','gv$b','hz'):
                    pos=[math.floor(x*32) for x in t['position']]
                    predicted=pos if k=='hz' else [x-t['encodedPos'+a] for x,a in zip(pos,'XYZ')]
                    check('position_packet_coordinates',predicted,[p['pos'+a] for a in 'XYZ'],e)
    for side in ('a','b'):
        records=client_events(result,side)
        receives={e['packet_id']:e for e in records if e['type']=='replication_receive'}
        applying={};applied=[]
        for e in records:
            typ=e['type']
            if typ=='replication_trace_drop':failures['trace_dropped']+=e['count']
            if typ=='replication_apply_before':
                applying[e['packet_id']]=e
                r=receives.get(e['packet_id'])
                if r:
                    wait=(e['monotonic_ns']-r['monotonic_ns'])/1e6
                    delays[side+'_receipt_to_apply_ms'].append(wait)
                    check('receipt_precedes_apply',True,wait>=0,e)
                else: failures['apply_missing_receive']+=1
            elif typ=='replication_apply_after':
                b=applying.pop(e['packet_id'],None)
                if b is None:failures['apply_missing_before']+=1;continue
                p=e['packet'];k=p['kind'];applied.append(e)
                if k.startswith('gv') or k=='hz':
                    pos=[p['pos'+a] for a in 'XYZ']
                    if k.startswith('gv'):pos=[x+y for x,y in zip(b['server_position'],pos)]
                    check('client_position_accumulator',pos,e['server_position'],e)
                    check('packet_ground_flag',p['onGround'],e['state']['ground'],e)
                    if 'interpolation_steps' in e:
                        goal=[x/32 for x in pos]
                        own=[b['state']['pos'+a] for a in 'XYZ']
                        if k=='hz' and all(abs(x-y)<bound for x,y,bound in zip(own,goal,(.03125,.015625,.03125))):goal=own
                        check('packet_interpolation_goal',goal,e['interpolation_goal'],e)
                        check('packet_interpolation_steps',3,e['interpolation_steps'],e)
                        check('packet_position_deferred',own,[e['state']['pos'+a] for a in 'XYZ'],e)
                elif k=='hm':
                    check('velocity_overwrite_bits',[p['motion'+a]/8000 for a in 'XYZ'],[e['state']['motion'+a] for a in 'XYZ'],e)
            elif typ=='science_interpolation':
                own=[e['before']['pos'+a] for a in 'XYZ'];n=e['steps']
                predicted=[p+(g-p)/n for p,g in zip(own,e['goal'])] if n>0 else own
                check('interpolation_position_bits',predicted,[e['after']['pos'+a] for a in 'XYZ'],e)
                box=e['before']['box']
                if n>0:
                    # setPosition uses the player's float width/2 before widening.
                    half=e['before']['width']/2;h=e['before']['height'];x,y,z=predicted
                    box=[x-half,y,z-half,x+half,y+h,z+half]
                check('interpolation_box_bits',box,e['after']['box'],e)
        if applying:failures['apply_missing_after']+=len(applying)
        # FIFO payload matching is diagnostic only; boundary packets may be cut
        # by the match window. Server emission -> Netty receipt != nominal RTT/2.
        # Metadata is currently logged without its entries, so equal hk records
        # cannot identify payloads. Omit it from transport timing comparisons.
        sends=[e for e in raw if e['type']=='replication_send' and e['recipient']==result[side]
               and e['time']>=result['start'] and e['packet']['kind']!='hk']
        cursor=0
        for r in sorted(receives.values(),key=lambda e:e['monotonic_ns']):
            if r['packet']['kind']=='hk':continue
            candidates=[i for i in range(cursor,len(sends)) if sends[i]['packet']==r['packet'] and sends[i]['monotonic_ns']<=r['monotonic_ns']]
            if not candidates:counts['unjoined_receipts']+=1;continue
            if len(candidates)>1:
                counts['ambiguous_receipts']+=1
                # Do not invent transport delay from an indistinguishable pair.
                continue
            found=candidates[0];s=sends[found];cursor=found+1
            delays[side+'_send_invocation_to_receipt_ms'].append((r['monotonic_ns']-s['monotonic_ns'])/1e6)
            counts['joined_receipts']+=1
    required=('tracker_counter','tracker_position_accumulator','position_packet_coordinates','client_position_accumulator','packet_interpolation_goal','interpolation_position_bits','interpolation_box_bits')
    for k in required:
        if not counts[k]:failures['missing_'+k]+=1
    return {'counts':dict(counts),'failures':dict(failures),'examples':examples,'passed':not failures,
            'timing':{k:stats(v) for k,v in delays.items()},
            'scope':'Exact conditional tracker accumulator, packet application, interpolation position/AABB and velocity overwrite checks. Metadata contents, rotation interpolation, whole tracker packet selection and full duel rollout are not certified.'}


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('suite');args=p.parse_args()
    suite=json.loads((OUT/(args.suite+'.json')).read_text())
    report={'suite':args.suite,'results':[{'match':r['game']['id'],'replication':validate(r['game'])} for r in suite['results']]}
    (OUT/(args.suite+'-replication.json')).write_text(json.dumps(report,indent=2)+'\n')
    for r in report['results']:print(json.dumps(r))
