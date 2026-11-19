"""Measure which return attacks were already in flight when an established boxing combo began."""
import argparse
import bisect
import gzip
import json
from pathlib import Path
import sys
from run import ROOT
sys.path.insert(0,str(ROOT/'research'))
from network_pipeline_audit import wire,pair_payloads

def interval_order(at,begin,end):
    if at is None:return 'unknown'
    if at<begin:return 'before'
    if at>end:return 'after'
    return 'overlap'

def read_pov(mid,prefix,kinds):
    p,=(ROOT/'runtime/pov'/mid).glob(prefix+'-*-events.jsonl.gz')
    rows=[]
    with gzip.open(p,'rt') as f:
        for line in f:
            if any('"'+k+'"' in line for k in kinds):
                r=json.loads(line)
                if r['type'] in kinds:rows.append(r)
    return sorted(rows,key=lambda r:r.get('monotonic_ns',0))

def native(g):
    mid=g['mid'];side=g['side'];other='b' if side=='a' else 'a'
    meta=json.loads((ROOT/'runtime/results'/(mid+'.json')).read_text())
    cuts=g['first_boxing']['at_ms']
    if not cuts:return {}
    accepted=[];hits=[];enqueued={}
    with (ROOT/'runtime/replays'/(mid+'.jsonl')).open() as f:
        for line in f:
            if not any(s in line for s in ('"science_server_attack"','"hit"','"server_packet_task_enqueued"')):continue
            e=json.loads(line);kind=e['type']
            if kind=='hit' and not e['rod']:hits.append(e)
            if kind=='science_server_attack' and e['score_before']!=e['score_after']:accepted.append(e)
            if kind=='server_packet_task_enqueued':enqueued[e['packet_id']]=e['monotonic_ns']
    for h,a in zip(hits,accepted,strict=True):
        if (h['attacker'],h['hits_a'],h['hits_b'])!=(a['attacker'],*a['score_after']):raise ValueError('Ambiguous accepted attack in '+mid)
    heads=[e['monotonic_ns'] for e in read_pov(mid,side+'-client',{'native_input_head'})]
    sent=[r for r in wire(read_pov(mid,other+'-client',{'packet_wire'}),'out') if r['packet']['id']==2]
    received=[r for r in wire(read_pov(mid,'server',{'packet_wire'}),'in',meta[other]) if r['packet']['id']==2]
    pairs,missing,extra=pair_payloads(sent,received)
    # Attack payloads often repeat. A gap inside the bout prevents unique ordinal attribution.
    uncertain=any(meta['start']<=r['event'].get('time_ms',r['event'].get('time',0))<=meta['finish'] for r in missing+extra)
    packets={r['packet_id']:(s['ns'],r['ns']) for s,r in pairs} if not uncertain else {}
    out={};streak=0
    for i,h in enumerate(hits):
        if h['time']<meta['start']+g['wake_ms']:continue
        if h['attacker']==meta[other]:streak=0;continue
        if h['damage_upgrade']:continue
        streak+=1;k=str(streak)
        if k in out or k not in cuts:continue
        if h['time']-meta['start']!=cuts[k]:raise ValueError('Landmark differs from native scoring')
        a=accepted[i];begin=a['monotonic_ns'];end=a['monotonic_end_ns']
        j=next((t for t in range(i+1,len(hits)) if hits[t]['attacker']==meta[other]),None)
        if j is None:out[k]={'censored':True};continue
        ret=accepted[j];pid=ret['packet_id'];send,receipt=packets.get(pid,(None,None))
        queue=enqueued.get(pid);head=bisect.bisect_right(heads,begin)
        if send is not None and not send<=receipt<=ret['monotonic_ns']:raise ValueError('Wire and server handler ordering differs in '+mid)
        if queue is not None and queue>ret['monotonic_ns']:raise ValueError('Server enqueued after recorded attack '+mid)
        out[k]=dict(censored=False,cycles=sum(not z['damage_upgrade'] for z in hits[i+1:j]),
            upgraded=hits[j]['damage_upgrade'],return_ms=hits[j]['time']-h['time'],
            same_tick=h['server_tick']==hits[j]['server_tick'],before_next_head=ret['monotonic_end_ns']<heads[head] if head<len(heads) else None,
            emitted=interval_order(send,begin,end),received=interval_order(receipt,begin,end),enqueued=interval_order(queue,begin,end),
            emit_vs_k_ms=(send-begin)/1e6 if send is not None else None,handler_width_ms=(end-begin)/1e6,
            target_packet=pid,landmark_packet=a['packet_id'])
    if out.keys()!=cuts.keys():raise ValueError('Incomplete landmark attribution '+mid)
    return out

def model(rows,clocks):
    schedules={}
    for p in clocks.glob('*.txt.gz'):
        heads={'a':[],'b':[]}
        with gzip.open(p,'rt') as f:
            for line in f:
                v=line.split()
                if v[0]=='#':continue
                if len(v)==3:side,ofs,period=v[0],int(v[1]),int(v[2]);continue
                if v[1]=='TICK_HEAD':
                    heads[side].extend(t/1e6 for t in range(ofs+int(v[0]),20_100_000_001,period))
        schedules[p.name.split('.')[0]]={s:sorted(ts) for s,ts in heads.items()}
    out=[]
    for r in rows:
        heads=schedules[r['clock']][r['side']];s=r['side']=='a'
        for k,v in r['forks'].items():
            head=bisect.bisect_right(heads,v['fork_at'])
            for arm in ('wtap','rl'):
                hs=[*v['committed'],*v[arm]['hits']]
                j=next((i for i,h in enumerate(hs) if h[1]!=s),None)
                d=dict(subject=arm,candidate=r['arm'],clock=r['clock'],rtt=r['rtt'],side=r['side'],k=k,censored=j is None,
                       fork_after_ms=v['fork_at']-v['landmark'],heads_between_landmark_and_fork=head-bisect.bisect_right(heads,v['landmark']))
                if j is not None:
                    at,_,upgrade=hs[j];d.update(cycles=sum(not h[2] for h in hs[:j]),return_ms=at-v['landmark'],upgraded=upgrade,
                        same_drain=at==v['landmark'],before_next_head=at<heads[head] if head<len(heads) else None)
                out.append(d)
    return out

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('out',type=Path);p.add_argument('native',type=Path)
    p.add_argument('model',type=Path);p.add_argument('clocks',type=Path);a=p.parse_args()
    with gzip.open(a.native,'rt') as f:games=json.load(f)['matches']
    actual={g['mid']:dict(arm=g['arm'],rtt=g['rtt'],side=g['side'],returns=native(g)) for g in games if g['mode']=='boxing'}
    with gzip.open(a.model,'rt') as f:sim=model(map(json.loads,f),a.clocks)
    with a.out.open('xb') as f:f.write(gzip.compress(json.dumps(dict(native=actual,model=sim),separators=(',',':')).encode(),mtime=0))
