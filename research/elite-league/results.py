"""Bundle all registered matches, per-setting outcomes and actual input audits without discarding draws."""
import argparse
from collections import Counter, defaultdict
import gzip
import hashlib
import json
import math
from pathlib import Path
from statistics import median
import sys
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
sys.path.insert(0,str(ROOT/'research/sumo-rl'))
from report import collect_maintenance,model_game,K,METHODS,score

def read(path):return json.loads(path.read_text())
def gzip_json(path,data):
    with path.open('xb') as f:f.write(gzip.compress(json.dumps(data,separators=(',',':')).encode(),mtime=0))
def lines(path):
    with gzip.open(path,'rt') as f:return [json.loads(l) for l in f]

def outcome(scores):
    c=Counter(scores);n=sum(c.values());known=n-c[None]
    return dict(wins=c[1],draws=c[.5],losses=c[0],unknown=c[None],games=n,points=c[1]+.5*c[.5],
                known_rate=(c[1]+.5*c[.5])/known if known else None)

def rings(rows):
    cells=defaultdict(lambda:defaultdict(list))
    for r in rows:
        if r.get('mode','sumo')!='sumo':continue
        asym=r['rtt']!=r.get('opponent_rtt',r['rtt'])
        cell=(r['profile'],r['opponent'],'asymmetric' if asym else 'symmetric')
        if 'diameter' in r:cell+=('disk-'+str(r['diameter']),)
        item=cells[cell][r['arm']]
        item.append(r)
    result={}
    for cell,arms in cells.items():
        result[' '.join(cell)]={}
        for arm,items in arms.items():
            cuts=dict(all=items)
            for r in items:
                ping=f"{r['rtt']}/{r.get('opponent_rtt',r['rtt'])}ms"
                cuts.setdefault(ping,[]).append(r);cuts.setdefault('seat-'+r['side'],[]).append(r)
                if 'clock' in r:cuts.setdefault(r['clock'],[]).append(r)
                if 'rep' in r:cuts.setdefault('repeat-'+str(r['rep']),[]).append(r)
            result[' '.join(cell)][arm]={k:outcome([r.get('score') for r in records]) for k,records in cuts.items()}
    return result

def model_boxes(rows):
    byarm=defaultdict(list)
    for r in rows:byarm[r['arm']].append(model_game(r))
    blank=dict(entries=0,completed_cycles=0,terminals={},remaining_ms=[],remaining_fresh_distribution={})
    return {name:{arm:collect_maintenance([{k:r['forks'][k][arm] if k in r['forks'] else {m:blank for m in METHODS}
                                           for k in K} for r in games]) for arm in ('wtap','rl')} for name,games in byarm.items()}

def first_boxing(game):
    """Enroll at the same first post-wake server landmark for both survival definitions."""
    record=read(ROOT/'runtime/results'/(game['mid']+'.json'))
    first=record[game['side']]
    wake=record['start']+game['wake_ms'] if game['wake_ms'] is not None else record['finish']+1
    hits=[(e['time'],e['attacker']!=first,e['damage_upgrade'])
          for e in score.events(ROOT/'runtime/replays'/(game['mid']+'.jsonl'))
          if e['type']=='hit' and not e['rod'] and wake<=e['time']<=record['finish']]
    starts={};streak=0
    for i,(when,returned,upgrade) in enumerate(hits):
        if returned:streak=0
        elif not upgrade:
            streak+=1
            if str(streak) in K:starts.setdefault(str(streak),i)
    outcomes={}
    release=game['bindings'].get(game['side'],{}).get('first_released_ms')
    for k in K:
        partial={}
        for method in METHODS:
            episodes=[]
            if k in starts:
                # Keep the logged order for multiple attacks in one server drain.
                cut=starts[k];at=hits[cut][0]
                episodes=score.episodes(hits[cut:],at,record['finish'],9,1,method=='active')[:1]
            partial[method]=score.summarize_episodes(episodes)
        outcomes[k]=partial
    return dict(outcomes=outcomes,at_ms={k:hits[i][0]-record['start'] for k,i in starts.items()},
                local_release_ms=release)

def native(manifest,scores,out):
    m=read(manifest)
    if set(m['plan'])!=set(m['matches']) or len(set(m['matches'].values()))!=len(m['matches']):raise ValueError('Incomplete or duplicate native matches')
    games=[];score_rows=[]
    builds=set()
    for req,mid in m['matches'].items():
        g=read(scores/(mid+'.json'));trial=m['plan'][req]
        assert all(g[k]==v for k,v in trial.items() if k!='command') and g['mid']==mid
        builds.add(tuple(g['build']));games.append(g)
        score_rows.append(dict(g,score=1 if g['win'] else .5 if g['draw'] else 0))
    if len(builds)!=1:raise ValueError('Matched cohort includes different bridge/module/headless/Java versions')
    bindings=[x for g in games for x in g['bindings'].values()]
    aggregate=Counter();disagreements=[]
    for x in bindings:
        for k in ('replayed','active_heads','verified_move_heads','back','strafe','jump','gate_closed'):aggregate[k]+=x[k]
        disagreements.extend(x['mismatches'])
    boxes=defaultdict(list)
    for g in games:
        if g['mode']=='boxing':
            g['first_boxing']=first_boxing(g)
            boxes[g['arm']].append(g)
    summary=dict(matches=len(games),build=games[0]['build'],rings=rings(score_rows),
        boxing={k:dict(bouts=len(v),bouts_entering=sum(x['maintenance']['3']['return_only']['entries']>0 for x in v),
                       maintenance=collect_maintenance([x['maintenance'] for x in v]),
                       common_first_landmark=collect_maintenance([x['first_boxing']['outcomes'] for x in v])) for k,v in boxes.items()},
        binding_checks=aggregate,binding_disagreements=disagreements,
        physical_clicks=sum(x[0] for g in games for x in g['press'].values()),
        minimum_physical_interval_ms=min(x[1] for g in games for x in g['press'].values() if x[1] is not None),
        max_server_gap_ms=max(g['max_server_gap_ms'] or 0 for g in games))
    gzip_json(out,dict(summary=summary,matches=games,manifest=m))
    return summary

if __name__=='__main__':
    p=argparse.ArgumentParser();s=p.add_subparsers(dest='cmd',required=True)
    n=s.add_parser('native');n.add_argument('manifest',type=Path);n.add_argument('scores',type=Path);n.add_argument('out',type=Path)
    mod=s.add_parser('model');mod.add_argument('out',type=Path);mod.add_argument('grids',type=Path,nargs='+')
    a=p.parse_args()
    if a.cmd=='native':d=native(a.manifest,a.scores,a.out);print(d['matches'],d['binding_checks'])
    else:
        rows=[r for path in a.grids for r in lines(path)]
        by_mode=dict(rings=rings([r for r in rows if r.get('mode')!='boxing']),boxing=model_boxes([r for r in rows if r.get('mode')=='boxing']))
        with a.out.open('x') as f:json.dump(by_mode,f,indent=2)
