"""Validate the public RTT, frozen network actions, ordinary inputs and complete native scores."""
import csv
import json
import re
import sys
import time
from collections import Counter,defaultdict
from pathlib import Path
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
sys.path.insert(0,str(HERE.parent))
import audit
from final_inputs import verify_match
CLASSES=ROOT/'build/elite-league/ping-ppo-classes'
if (CLASSES/'NetworkChecks.class').exists():audit.class_path=lambda:CLASSES

def verify(mid,row,folder):
    r=verify_match(mid,row,folder)
    if not r['valid']:return r,None
    game=json.loads((ROOT/'runtime/results'/(mid+'.json')).read_text())
    pub={}
    for s in 'ab':
        if game['policy_'+s]!='sumo-two-ping':continue
        source,=(ROOT/'runtime/pov'/mid).glob(s+'-client-*-events.jsonl.gz')
        obs=None;counts=Counter();tick=None
        for e in audit.captures.events(source,{'duel_network','duel_hud_ping'}):
            if e.get('match')!=mid:continue
            if e['type']=='duel_hud_ping':
                own=game['initial_ping_'+s];other=game['initial_ping_'+('b' if s=='a' else 'a')]
                assert e['own']==own
                pair=re.search(r'RTT (\d+)/(\d+) ms\b',e['hud'])
                if e['opponent']!=-1:
                    assert pair and tuple(map(int,pair.groups()))==(game['initial_ping_a'],game['initial_ping_b'])
                    assert e['opponent']==other
                obs=e['opponent'];tick=e['tick']
            elif game['start']<=e['time']<=game['finish']:
                assert tick is not None and tick<=e['tick']
                counts['unknown' if obs==-1 else 'correct']+=1
        assert counts['correct'] and not counts['unknown'], (mid,counts)
        pub[s]=counts
    return r,pub

def tally(values):
    values=list(values)
    return [sum(v==k for v in values) for k in (1,0,.5)]
def groups(rows,key):
    out=defaultdict(list)
    for row in rows:out[row[key]].append(row['reward'])
    return {k:tally(v) for k,v in out.items()}
def summarize(rows):
    by=defaultdict(list)
    for row in rows:by[row['arm']].append(row)
    return {a:dict(total=tally(r['reward'] for r in v),versus=groups(v,'foe'),seat=groups(v,'seat'),links=groups(v,'link'),
                   by_foe={f:{'seats':groups([r for r in v if r['foe']==f],'seat'),
                              'links':groups([r for r in v if r['foe']==f],'link')} for f in dict.fromkeys(r['foe'] for r in v)})
            for a,v in by.items()}
def main():
    manifest=HERE/'native-final.json';scores=ROOT/'build/elite-league/ping-ppo-scores'
    checked={};pings={};rows=[]
    while True:
        if manifest.exists():
            d=json.loads(manifest.read_text())
            for req,mid in d['matches'].items():
                if mid in checked:continue
                t=d['plan'][req];game=json.loads((ROOT/'runtime/results'/(mid+'.json')).read_text())
                try:result,public=verify(mid,t,scores)
                except (FileNotFoundError,EOFError):
                    if time.time()-game['finish']/1000>60:raise
                    continue
                checked[mid]=result
                if public:pings[mid]=public
                if not result['valid']:continue
                rows.append(dict(arm=t['arm'],foe=t['opponent'],seat=t['side'],link=f"{t['rtt']}/{t['opponent_rtt']}",
                            reward=.5 if not game['winner'] else float(game['winner']==game[t['side']])))
            if len(checked)==len(d['plan']):break
        time.sleep(1)
    raw=list(csv.DictReader((HERE/'transfer.csv').open()))
    model=[dict(**{k:r[k] for k in ('arm','foe','seat')},link=r['own_rtt']+'/'+r['opponent_rtt'],reward=float(r['reward'])) for r in raw]
    summary=dict(outcome_order=['wins','losses','draws'],model=summarize(model),native=summarize(rows),public_hud=pings,
                 native_verified=len(rows),native_invalid=[m for m,r in checked.items() if not r['valid']],
                 physical_movement_heads=sum(s['heads'] for r in checked.values() for s in r['sides'].values()),
                 min_physical_click_gap_ms=min(v[1] for mid in checked if (scores/(mid+'.json')).exists()
                    for v in json.loads((scores/(mid+'.json')).read_text())['press'].values() if v[1] is not None))
    (HERE/'results.json').write_text(json.dumps(summary,indent=2)+'\n')
if __name__=='__main__':main()
