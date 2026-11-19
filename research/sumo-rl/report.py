"""Bundle the frozen outcomes, validation splits and matched baseline into a reproducible report."""
import argparse
from collections import Counter
import gzip
import json
from pathlib import Path
import statistics
from score_runs import score, ROOT, HERE

METHODS = ('return_only', 'active')
K = ('3', '5', '10')
NAMES = {'research-wtap20':'wtap', 'research-hold20':'hold', 'trade-wtap':'trade',
         'jump-reset':'reset', 'combo-wake-2000':'wtap','research-zest-strafe':'zest-strafe'}

def read(path):
    return json.loads(path.read_text())

def measure(games, side):
    known = [g[side] for g in games if g.get(side) is not None]
    if not known:
        return None
    c = Counter(g['outcome'] for g in known)
    out = dict(games=len(known), wins=c['win'], losses=c['loss'], draws=c['draw'],
               points=c['win']+c['draw']*.5)
    return out

def native_outcome(game):
    return 'win' if game['win'] else 'draw' if game['draw'] else 'loss'

def outcomes(games):
    """Present W-tap and the learned arm side by side, including each RTT and seat."""
    labels = ('wtap','rl','movement_only','jump_only')
    result = {}
    for mode, profile, opp in sorted({(r['mode'],r['profile'],r['opponent']) for r in games}):
        group=[r for r in games if (r['mode'],r['profile'],r['opponent'])==(mode,profile,opp)]
        if mode!='sumo':continue
        target = {}
        for part, rows in [('all',group)]+[(f'{ms}ms',[r for r in group if r['rtt']==ms]) for ms in (0,80)]+[
                (seat,[r for r in group if r['side']==seat]) for seat in 'ab']:
            target[part]={s:measure(rows,s) for s in labels if any(s in r for r in rows)}
            target[part]['unknown']=sum('unknown'in r for r in rows)
        result[profile+' '+opp]=target
    return result

def collect_maintenance(groups):
    out={}
    for k in K:
        out[k]={}
        for method in METHODS:
            cells=[r[k][method] for r in groups]
            terms=Counter();dist=Counter();times=[]
            for c in cells:
                terms.update(c['terminals']);dist.update(c['remaining_fresh_distribution']);times.extend(c['remaining_ms'])
            out[k][method]=dict(entries=sum(c['entries'] for c in cells),cycles=sum(c['completed_cycles'] for c in cells),
              terminals=terms,fresh_distribution=dist,exposure_ms=round(sum(times),2),remaining_ms=sorted(times),
              median_ms=round(statistics.median(times),2) if times else None)
    return out

def model_game(r):
    if 'unknown' in r:return r
    row={k:r[k] for k in ('mode','profile','rtt','opponent','side','clock')}
    first=r['side']=='a'
    for arm in ('wtap','rl','movement_only','jump_only'):
        if arm not in r:continue
        g=r[arm];win=g['winner']
        row[arm]={'outcome':('draw' if win<=0 else 'win' if (win==1)==first else 'loss'),
                  'windows':g.get('fresh_windows'),'jumps':g['jumps']}
    if r['mode']=='boxing':
        row['forks']={}
        for k,cut in r['forks'].items():
            box=row['forks'][k]={}
            for arm in ('wtap','rl'):
                g=cut[arm]
                hs=[(cut['landmark'],False,False)]+score.signed_hits(cut['committed']+g['hits'],first)
                box[arm]={}
                for method in METHODS:
                    ep=score.episodes(hs,cut['landmark'],g['end'],9,1,method=='active')
                    box[arm][method]=score.summarize_episodes(ep[:1])
    return row

def native_game(g):
    arm={'research-wtap20':'wtap','research-hold20':'movement_only'}.get(g['arm'],'rl')
    fields=('mid','mode','profile','rtt','side','rep')
    return {**{k:g[k] for k in fields},'opponent':NAMES.get(g['opponent'],g['opponent']),
        arm:{'outcome':native_outcome(g),'timed_jumps':len(g['jump_inputs']),
        'takeoffs':g['confirmed_takeoffs'],'maintenance':g['maintenance'],'windows':g['windows']}}

def summarize(raw_native, raw_model):
    ns=[native_game(r) for r in raw_native];ms=[model_game(r) for r in raw_model]
    blank=dict(entries=0,completed_cycles=0,terminals={},remaining_ms=[],remaining_fresh_distribution={})
    boxing={}
    for arm in ('wtap','rl'):
        n=[g[arm] for g in ns if g['mode']=='boxing' and arm in g]
        model=[g for g in ms if g['mode']=='boxing' and 'forks' in g]
        boxing[arm]={'native_independent':collect_maintenance([g['maintenance'] for g in n]),
                     'native_bouts':len(n),
                     'native_bouts_entering_3':sum(g['maintenance']['3']['return_only']['entries']>0 for g in n),
                     'model_common_forks':collect_maintenance([{k:g['forks'][k][arm] if k in g['forks'] else
                            {m:blank for m in METHODS} for k in K} for g in model]),
                     'model_common_starts':len(model)}
    return {'ringouts':{'model':outcomes(ms),'native':outcomes(ns)},'boxing':boxing,
            'native_jumps':{arm: {k:sum(g[arm][k] for g in ns if arm in g) for k in ('timed_jumps','takeoffs')} for arm in ('wtap','rl')},
            'native_rtt_seat':[{k:g[k] for k in ('mid','mode','profile','rtt','side','opponent','arm','rep','win','draw')} for g in raw_native]}

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('tag',choices=('v1','v2','v3','lateral'))
    parser.add_argument('model',type=Path);parser.add_argument('manifest',type=Path);parser.add_argument('scores',type=Path)
    parser.add_argument('output',type=Path)
    a=parser.parse_args();manifest=read(a.manifest)
    if manifest['plan'].keys()!=manifest['matches'].keys():raise ValueError('Incomplete native cohort')
    native=[read(a.scores/(mid+'.json')) for mid in manifest['matches'].values()]
    assert {g['mid'] for g in native}==set(manifest['matches'].values())
    with a.model.open() as f:model=[json.loads(l) for l in f]
    # Keep every individual game and fork so anyone can choose a different estimator later.
    summary=summarize(native,model)
    bundle={'revision':a.tag,'summary':summary,'model':model,'native':native,'manifest':manifest}
    with a.output.open('xb') as f:f.write(gzip.compress(json.dumps(bundle,separators=(',',':')).encode(),mtime=0))
    print(a.tag,len(native),'native,',len(model),'model starts')
    for g,row in summary['ringouts']['native'].items():
        print(g,{arm:(v['wins'],v['draws'],v['games']) for arm,v in row['all'].items() if isinstance(v,dict)})
if __name__=='__main__':main()
