"""Audit frozen legacy opponents at their own settings under the shared regulation overrides."""
import argparse
from collections import defaultdict
import gzip
import json
from pathlib import Path
import random
from adaptation import properties,dump,sha
from history_cup import CUP,FIELD,counts
from ladder_transfer import WT
from native_trials import run_batch
from run import ROOT

# Only settings explicitly standardized by every request and descriptive metadata collapse.
COMMON={'description','attack_reach','click_cps','reaction_ms','aim_input','aim_delay_ms','aim_noise_deg','aim_turn_rate_dps'}
def signature(p):
    return tuple(sorted((k,v) for k,v in properties(p).items() if k not in COMMON and not k.startswith('league_')))

def freeze_index(dest):
    original=json.loads((FIELD/'policy-index.json').read_text());covered=json.loads((FIELD/'field.json').read_text())
    represented={v['native'] for v in covered.values() if not v.get('scope')}
    groups=defaultdict(list)
    for n,record in original.items():
        p=ROOT/'policies'/(n+'.properties')
        if sha(p)!=record['config']:raise ValueError('Historical opponent changed since roster freeze: '+n)
        groups[signature(p)].append(n)
    result={}
    for group in groups.values():
        known=sorted(represented.intersection(group))
        name=known[0] if known else sorted(group)[0];p=ROOT/'policies'/(name+'.properties')
        d=properties(p)
        required={k:v for k,v in d.items() if k in ('reference_table','input_word')}
        missing=[v for v in required.values() if not (ROOT/v).is_file()]
        if missing:raise ValueError('Missing historical policy input: '+str(missing))
        result[name]=dict(aliases=sorted(group),sha256=sha(p),kind=d['kind'],regulation_family=(
            'controlled drill' if d.get('kind') in ('standstill','scripted','input-word') or any(k in d for k in ('seed_plan_admission','click_start_ms','duel_network_landmark')) and
            (d.get('duel_network_landmark','0')!='0' or int(d.get('click_start_ms',0))>0 or 'seed_plan_admission' in d) else 'active fighter'),
            shared_model=bool(known),dependencies={v:sha(ROOT/v) for v in required.values()})
    indexed=[n for g in result.values() for n in g['aliases']]
    if len(set(indexed))!=len(original) or len(indexed)!=len(original):raise AssertionError('Historical names lost or duplicated')
    # Distribute families across slices. New timings on this exposed roster are still development evidence.
    by=defaultdict(list);rng=random.Random(809211)
    for n,g in result.items():
        if not g['shared_model']:by[g['kind']].append(n)
    for names in by.values():rng.shuffle(names)
    order=[]
    while any(by.values()):
        for kind in sorted(by):
            if by[kind]:order.append(by[kind].pop())
    dump(dest,dict(names=len(original),distinct=len(result),field=result,order=order))

def schedule(tag,arms,field):
    rows=[]
    for oi,(opp,g) in enumerate(field.items()):
        for rtt in (0,80):
            for seat in 'ab':
                for arm in arms:
                    request=f'{tag}-{arm}-{opp}-{rtt}-{seat}';a,b=(arm,opp) if seat=='a' else (opp,arm)
                    rows.append((request,dict(arm=arm,opponent=opp,side=seat,rtt=rtt,rep=0,mode='sumo',profile='mmc',aim_input='mouse',
                         command=f'lab sumo {a} {b} {rtt} {rtt} {request} preset=mmc reach=3 cps=20 tapdelay=0 aimdelay=0 aimnoise=0 aimrate=0 aiminput=mouse')))
    random.Random(tag).shuffle(rows);return dict(rows)

def summarize(trials,manifest,expected):
    from audit import MouseAudit,captures
    rows=[];m=json.loads(manifest.read_text())
    if m['plan']!=trials or m['matches'].keys()!=trials.keys():raise ValueError('Incomplete historical slice')
    for req,mid in m['matches'].items():
        t=trials[req];side=t['side'];other='b' if side=='a' else 'a'
        g=json.loads((ROOT/f'runtime/results/{mid}.json').read_text())
        if g['policy_'+side]!=t['arm'] or g['policy_'+other]!=t['opponent'] or g['practice_preset']!='mmc' or g['reason'] not in ('ring_out','double_ring_out','timeout_draw'):
            raise ValueError('Unexpected native match '+mid)
        if g['combat_profile']['knockback_model']!='mmc_pre_attack_sprint_v2' or g['sumo_arena']['diameter']!=t.get('diameter',10) or g['sumo_arena']['start_gap']!=t.get('start_gap',6):
            raise ValueError('Historical ring or knockback differs')
        checks={}
        for s in 'ab':
            p=g['parameters_'+s]
            if (float(p['attack_reach'])!=3 or int(p['click_cps'])!=20 or float(p['reaction_ms'])!=0 or p.get('aim_input')!='mouse' or
                any(float(p[k])!=0 for k in ('aim_delay_ms','aim_noise_deg','aim_turn_rate_dps')) or g['initial_ping_'+s]!=t['rtt']):
                raise ValueError('Historical court controls differ in '+mid)
            source=ROOT/'policies'/(g['policy_'+s]+'.properties')
            if s==other and sha(source)!=expected[t['opponent']]['sha256']:raise ValueError('Historical policy changed')
            config=properties(source)
            for key,setting in config.items():
                if key not in COMMON and not key.startswith('league_') and p.get(key)!=setting:
                    raise ValueError('Native policy field differs in '+mid+'/'+s+': '+key)
            f,=(ROOT/'runtime/pov'/mid).glob(s+'-client-*-events.jsonl.gz')
            audit=MouseAudit();last=None;presses=0;minimum=None;footer=None;heads=0
            head_type='duel_network' if 'duel_network' in config else 'legacy_head'
            for e in captures.events(f):
                if e['type']=='capture_end':footer=e
                if not g['start']<=e.get('time',0)<=g['finish']:continue
                audit.record(e)
                if e['type']==head_type and e.get('match')==mid:heads+=1
                if e['type']=='click':
                    now=e['monotonic_ns'];presses+=1
                    if last is not None:
                        if now-last<50_000_000:raise ValueError('CPS cap exceeded in '+mid)
                        minimum=now-last if minimum is None else min(minimum,now-last)
                    last=now
            if not footer or not footer['writer_complete'] or any(footer[k] for k in ('dropped','size_capped','continues')):raise ValueError('Incomplete POV '+mid)
            if not heads:raise ValueError('Missing executed policy inputs in '+mid+'/'+s)
            checks[s]=dict(mouse=audit.summary(),heads=heads,head_type=head_type,presses=presses,minimum_interval_ns=minimum)
        rows.append(dict(mid=mid,arm=t['arm'],opponent=t['opponent'],side=side,rtt=t['rtt'],rep=0,
                         win=g['winner']==g[side],draw=not g['winner'],checks=checks,end=g['reason']))
    return rows

def run(work,index,champion):
    from historical_model import head_replay
    record=json.loads(index.read_text());remaining=record['order'];destination=CUP/'legacy';destination.mkdir(exist_ok=True,parents=True)
    if champion==WT:raise ValueError('Legacy census needs a distinct challenger and W-tap')
    dump(destination/'title.json',dict(champion=champion,config=sha(ROOT/'policies'/(champion+'.properties')),index_sha256=sha(index)))
    for i in range(0,len(remaining),12):
        n=i//12;tag=f'eold{n:02d}';dest=destination/(tag+'.json.gz')
        heads=destination/(tag+'-input-replay.json')
        if dest.exists() and heads.exists():continue
        field={op:record['field'][op] for op in remaining[i:i+12]}
        manifest=work/(tag+'-native.json');trials=schedule(tag,[WT,champion],field)
        run_batch(trials,manifest)
        if not heads.exists():head_replay(manifest,heads)
        if dest.exists():continue
        rows=summarize(trials,manifest,field)
        with dest.open('xb') as f:f.write(gzip.compress(json.dumps(dict(rows=rows,results=counts(rows),trials=trials),separators=(',',':')).encode(),mtime=0))
        print('Historical slice',n+1,len(rows),'native games audited',flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);s=p.add_subparsers(dest='cmd',required=True)
    k=s.add_parser('freeze');k.add_argument('index',type=Path)
    k=s.add_parser('run');k.add_argument('work',type=Path);k.add_argument('index',type=Path);k.add_argument('champion')
    a=p.parse_args()
    if a.cmd=='freeze':freeze_index(a.index)
    else:run(a.work,a.index,a.champion)
