"""Fit stage-dependent transportation overhead on development recordings and freeze a real-time forecast."""
import argparse
import bisect
from collections import Counter,defaultdict
import json
from pathlib import Path
import statistics
import sys
from fidelity_panel import load_strict,pick,ROOT,HERE,SUMO
from input_replay import compare
from calendars import server_stage
from run import compile_model

CELLS=('up','other','own-hurt','target-hurt','velocity','enqueue-other','enqueue-own-hurt','enqueue-target-hurt','enqueue-velocity')

def quantile(xs,q):
    a=sorted(xs);z=(len(a)-1)*q;i=int(z)
    return a[i]+(a[min(i+1,len(a)-1)]-a[i])*(z-i)

def anchor(stages,at):
    i=bisect.bisect_left(stages,at);candidates=stages[max(0,i-1):i+1]
    if not candidates:return None
    t=min(candidates,key=lambda v:abs(v-at))
    return t if abs(t-at)<=15_000_000 else None

def sample(mid):
    strict=load_strict();record=json.loads((ROOT/f'runtime/results/{mid}.json').read_text())
    events=[]
    with (ROOT/f'runtime/replays/{mid}.jsonl').open() as f:
        for l in f:
            e=json.loads(l)
            if e['type'] in ('replication_server_clock','replication_tracker_before'):events.append(e)
    stages=defaultdict(list);entities={}
    for e in events:
        k=server_stage(e)
        if k:stages[k].append(e['monotonic_ns'])
        if e['type']=='replication_tracker_before':entities[e['tracker']['player']]=e['tracker']['entity_id']
    for v in stages.values():v.sort()
    shared=strict.archive(mid,'server');data=defaultdict(list);coverage=Counter()
    for side in 'ab':
        client=strict.archive(mid,side);name=record[side];ping=int(record['initial_ping_'+side]);prefix=str(ping)+'/'
        local={k:sorted(e['monotonic_ns'] for e in client if e['type']==k and 'monotonic_ns' in e) for k in ('tick_end','native_attack_loop')}
        enqueue={e['data']['packet_id']:e['monotonic_ns'] for e in client if e['type']=='packet_task_enqueued'}
        for outgoing in (True,False):
            left,right=(strict.wire(client,'out'),strict.wire(shared,'in',name)) if outgoing else (strict.wire(shared,'out',name),strict.wire(client,'in'))
            pairs,missed,extra=strict.pair_payloads(left,right)
            def active(e):
                t=e.get('time_ms',e.get('time',0));return record['start']<=t<=min(record['finish'],record['start']+8000)
            coverage['unpaired_sends']+=sum(active(x['event']) for x in missed);coverage['unpaired_receipts']+=sum(active(x['event']) for x in extra)
            for s,r in pairs:
                if not active(s['event']):continue
                p=s['packet'];pid=p['id']
                if outgoing:
                    cell='up';which=local['native_attack_loop' if pid==2 else 'tick_end']
                else:
                    if pid==0x1a:
                        if p.get('status',p.get('opcode')) not in (2,None):continue
                        cell='own-hurt' if p['entity_id']==entities[name] else 'target-hurt'
                    elif pid==0x12:
                        if p['entity_id']!=entities[name]:continue
                        cell='velocity'
                    else:cell='other'
                    which=stages['SERVER_TRACK' if cell=='other' else 'SERVER_DRAIN']
                t=anchor(which,s['ns'])
                if t is None:coverage['no_near_'+cell]+=1;continue
                residual=(r['ns']-t)/1e6-ping/2
                data[prefix+cell].append(residual);coverage['observed_'+cell]+=1
                if outgoing:continue
                ready=enqueue.get(r['packet_id'])
                if ready is None:coverage['no_enqueue_'+cell]+=1;continue
                if ready<r['ns']:coverage['enqueue_precedes_receipt']+=1;continue
                data[prefix+'enqueue-'+cell].append((ready-r['ns'])/1e6)
    return data,dict(coverage)

def specification(donors,q,enqueue):
    result={}
    for ping in (0,80):
        v=[]
        for cell in CELLS:
            values=[quantile(d[f'{ping}/{cell}'],q) for d in donors.values() if d.get(f'{ping}/{cell}')]
            if not values:values=[quantile(xs,q) for d in donors.values() for key,xs in d.items() if key.split('/')[1]==cell and xs]
            if not values:raise ValueError(f'No training observation for {cell}')
            v.append(max(0,statistics.median(values)) if enqueue or not cell.startswith('enqueue-') else 0)
        result[str(ping)]='shape='+','.join(f'{n:.9f}' for n in v)
    return result

def trials(ids,specs):
    rows={}
    for mid in ids:
        r=json.loads((ROOT/f'runtime/results/{mid}.json').read_text());ping=str(int(r['initial_ping_a']))
        if r['initial_ping_a']!=r['initial_ping_b']:raise ValueError('Only symmetric RTT is registered')
        settings=[(.35,.35),*[s[ping] for s in specs]]
        try:
            d=compare(mid,settings)
            if not isinstance(d,list):rows[mid]={'unknown':d};continue
            rows[mid]=[dict(exact=x['exact'],max_error=x['max_position_error'],moves=x['moves']) for x in d]
        except (ValueError,KeyError,IndexError,StopIteration,FileNotFoundError) as exc:rows[mid]={'unknown':str(exc)}
    return rows

def fit(out):
    ids=json.loads((HERE/'protocol.json').read_text())['training_clocks'];donors={};cover={}
    for mid in ids:
        try:donors[mid],cover[mid]=sample(mid)
        except (ValueError,KeyError,IndexError,FileNotFoundError) as exc:cover[mid]={'unknown':str(exc)}
    names=[];specs=[]
    for q in (.25,.50,.75):
        for queue in (False,True):
            names.append([q,queue]);specs.append(specification(donors,q,queue))
    rows=trials(ids,specs);ok=[r for r in rows.values() if isinstance(r,list)]
    if not ok:raise ValueError('No paired physical forecasts')
    stats=[dict(exact=sum(r[i]['exact'] for r in ok),n=len(ok),mean_max_error=statistics.mean(r[i]['max_error'] for r in ok)) for i in range(1+len(specs))]
    best=min(range(len(specs)),key=lambda i:(-stats[i+1]['exact'],stats[i+1]['mean_max_error'],i))
    return dict(selected=specs[best],candidate=names[best],rows=rows,ranks=stats,labels=[['fixed',False],*names],
                counts=cover,donor_quantiles={mid:{k:[len(v),*[quantile(v,q) for q in (.25,.5,.75)]] for k,v in d.items()} for mid,d in donors.items()})

if __name__=='__main__':
    from cpu_limit import supervise_script
    supervise_script()
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('out',type=Path);p.add_argument('--manifest',type=Path);p.add_argument('--frozen',type=Path);a=p.parse_args();compile_model()
    if a.manifest:
        ids=pick(a.manifest,['research-wtap20','research-hold20','elite-v1-hybrid','elite-ppo','elite-population'])
        result=dict(selected=ids,rows=trials(ids.values(),[json.loads(a.frozen.read_text())['selected']]))
    else:result=fit(a.out)
    with a.out.open('x') as f:json.dump(result,f,indent=2)
