"""Explain where W-tap and adaptive opponents take trades, then test ordinary-key counterfactuals."""
from collections import Counter,defaultdict
import argparse
import gzip
import json
import math
from pathlib import Path
import shutil
import subprocess
import tempfile
from adaptation import dump,freeze
from focus import BASE,CURRENT,EVIDENCE,REFERENCE,TARGET,WT
from run import HERE,ROOT,calendars,compile_model,java
from tournament import roster

def fork(work):
    compile_model()
    folder=EVIDENCE/'trades';folder.mkdir(exist_ok=True,parents=True)
    with tempfile.TemporaryDirectory(dir=work) as temp:
        t=Path(temp);roster(t/'arm',{CURRENT:BASE[CURRENT]});roster(t/'foes',{'wtap':None,TARGET:HERE/'adaptive-frozen/round-01/pair.net'})
        for idx in (1,2):
            out=folder/f'exchange-{idx}.jsonl.gz'
            if not out.exists():
                flags=(['--second-trade'] if idx==2 else [])+['--mouse']
                with subprocess.Popen(java('TradeWords',t/'arm',t/'foes',*flags,*calendars('training_clocks')),stdout=subprocess.PIPE) as p:
                    raw=t/f'{idx}.gz'
                    with raw.open('wb') as f,gzip.GzipFile(fileobj=f,mode='wb',mtime=0) as dest:shutil.copyfileobj(p.stdout,dest)
                    if p.wait():raise RuntimeError('Trade-word fork diverged')
                freeze(out,raw.read_bytes())
            groups=defaultdict(list)
            with gzip.open(out,'rt') as f:
                for r in map(json.loads,f):groups[r['opponent']].append(r)
            stats={}
            for op,rows in groups.items():
                reached=[r for r in rows if 'continuations' in r];stats[op]=dict(starts=len(rows),reached=len(reached),branches={})
                for n in reached[0]['continuations'] if reached else []:
                    pairs=[(r['continuations'][n][0],r['base']) for r in reached]
                    stats[op]['branches'][n]=dict(points=sum(v for v,_ in pairs),improves=sum(v>b for v,b in pairs),
                            loses=sum(v<b for v,b in pairs),unchanged=sum(v==b for v,b in pairs),
                            per_rtt={str(p):sum(r['continuations'][n][0] for r in reached if r['rtt']==p) for p in (0,80)})
            dump(folder/f'exchange-{idx}.json',stats);print(idx,{n:s['reached'] for n,s in stats.items()},flush=True)

def bout(row):
    mid=row['mid'];g=json.loads((ROOT/'runtime/results'/(mid+'.json')).read_text());us=g[row['side']];offset=g['sumo_arena'].get('center_offset',0);center=(g['arena']+1)*4096+offset
    hits=[];kb=[];head_by={}
    with (ROOT/'runtime/replays'/(mid+'.jsonl')).open() as stream:
        for e in map(json.loads,stream):
            if e['type']=='hit' and not e['rod']:hits.append(e)
            if e['type']=='mmc_knockback':kb.append(e)
    fresh=[h for h in hits if not h['damage_upgrade']]
    for h,k in zip(fresh,kb,strict=True):
        if h['target']!=k['target'] or abs(h['time']-k['time'])>30:raise ValueError('Fresh impulse pairing differs '+mid)
        h['knockback']=[math.trunc(v*8000)/8000 for v in k['velocity']];h['bonus']=bool(k['extra_levels'])
    for s in 'ab':
        f,=(ROOT/'runtime/pov'/mid).glob(s+'-client-*-events.jsonl.gz')
        with gzip.open(f,'rt') as stream:
            head_by[s]=[e for e in map(json.loads,stream) if e['type'] in ('duel_network','legacy_head') and e.get('match')==mid and g['start']<=e['time']<=g['finish']]
    if any(not lst for lst in head_by.values()):raise ValueError('Missing executed input '+mid)
    seq=[]
    for h in hits:
        own=h['attacker']==us;pos=h['attacker_state' if own else 'target_state']
        detail=dict(t=h['time']-g['start'],own=own,upgrade=h['damage_upgrade'],tick=h['server_tick'],gap=math.hypot(
            h['attacker_state']['posX']-h['target_state']['posX'],h['attacker_state']['posZ']-h['target_state']['posZ']),
            our_xyz=[pos['posX']-center,pos['posY']-g['sumo_arena']['top_y'],pos['posZ']-offset])
        if 'knockback' in h:detail.update(wire=h['knockback'],bonus=h['bonus'],horizontal=math.hypot(h['knockback'][0],h['knockback'][2]))
        seq.append(detail)
    heads=head_by[row['side']];net=[h for h in heads if h['type']=='duel_network'];streak=0
    for h in reversed(hits):
        if h['attacker']==us:break
        if not h['damage_upgrade']:streak+=1
    def fresh_counts(ours):
        f=[h for h in fresh if (h['attacker']==us)==ours]
        return dict(fresh=len(f),sprint=sum(h['bonus'] for h in f),upgrades=sum(h['damage_upgrade'] for h in hits if (h['attacker']==us)==ours))
    return dict(mid=mid,arm=row['arm'],opponent=row['opponent'],rtt=row['rtt'],side=row['side'],win=row['win'],draw=row['draw'],
                duration_ms=g['finish']-g['start'],own=fresh_counts(True),other=fresh_counts(False),
                first_fresh_ours=None if not fresh else fresh[0]['attacker']==us,trailing_unanswered=streak,sequence=seq,
                physical_press=row['press'],braking=row['contact_braking'],heads=len(heads),
                selector_heads=dict(Counter(h.get('expert') for h in net if 'expert' in h)),
                closed_gate_heads=sum(not (h['action']//12&1) for h in net) if net else sum(not h['keys'][-1] for h in heads))

def analyze(src,dest):
    with gzip.open(src,'rt') as f:raw=json.load(f)['matches']
    rows=[bout(r) for r in raw if r['opponent'] in (WT,TARGET,CURRENT)]
    info={}
    for row in rows:
        group=info.setdefault(row['arm']+'/'+row['opponent'],{})
        outcome='draw' if row['draw'] else 'win' if row['win'] else 'loss'
        s=group.setdefault(outcome,Counter());s['bouts']+=1;s['opened_first']+=row['first_fresh_ours'] or 0
        for side in ('own','other'):
            for k,v in row[side].items():s[side+'_'+k]+=v
        s['closed_gate_heads']+=row['closed_gate_heads'];s['heads']+=row['heads']
    freeze(dest,gzip.compress(json.dumps(dict(bouts=rows,groups=info),separators=(',',':')).encode(),mtime=0))
    print(len(rows),'native bouts explained',flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);s=p.add_subparsers(dest='cmd',required=True)
    s.add_parser('fork').add_argument('work',type=Path)
    a=s.add_parser('native');a.add_argument('source',type=Path);a.add_argument('dest',type=Path);o=p.parse_args()
    if o.cmd=='fork':fork(o.work)
    else:analyze(o.source,o.dest)
