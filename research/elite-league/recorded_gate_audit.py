"""Determine whether the adaptive controller benefits from switching or merely contains a strong expert."""
from collections import Counter,defaultdict
import gzip
import json
from statistics import median
from adaptation import BLOCK,GATE,TYPES,encode,unpack,freeze,dump
from recorded_range_responses import prepare,OUT
from recorded_mmc_league import DEST,evaluate,publish
from run import calendars,compile_model
import focus

def read(p):return json.loads(p.read_text())
def main():
    adaptive=DEST/'range-adaptation';winner=read(adaptive/'results.json')['pair'];d=adaptive/'gate-audit';d.mkdir(exist_ok=True)
    pool,students,original=prepare();cup=read(OUT/'common/results.json')
    for track in ('reply','counter'):
        n=cup[track];pool[n]=OUT/track/(n+'.net')
    for f in (adaptive/'switches').glob('shift-check-*.net'):pool[f.stem]=f
    pair=adaptive/(winner+'.net');family,w=unpack(pair.read_text());assert family=='PAIR' and int(w[0])==2
    source=[];arms={'wtap':None,winner:pair,cup['reply']:pool[cup['reply']]}
    for i in range(2):
        family=TYPES[int(w[i+1])];at=8+GATE+i*BLOCK
        for n,p in pool.items():
            if p is None:continue
            typ,weights=unpack(p.read_text())
            if typ==family and weights==w[at:at+len(weights)]:
                source.append(n);arms[n]=p;break
        else:raise ValueError('Frozen expert identity missing')
    for name,indices in [('no-history',range(8+23,8+GATE)),('history-only',range(9,9+22))]:
        v=w.copy()
        for j in indices:v[j]=0.
        n='v4-gate-'+name;path=d/(n+'.net');freeze(path,(encode('PAIR',v)+'\n').encode());arms[n]=path
    choices=['wtap',original,cup['counter'],cup['reply'],*sorted(n for n in pool if n.startswith('shift-check-v4-'))];foes={n:pool[n] for n in choices}
    dump(d/'protocol.json',dict(pair=winner,experts=source,arms=list(arms),opponents=choices,
        intervention='Freeze both expert weights and all fitting. Remove the 14 history weights, remove the 22 current-view weights, or play each individual expert from the opening. Compare all actions against the same development clocks, seats and RTTs. The novel switch styles have now been exposed. No parameter is refit on them.'))
    compile_model();raw=d/'ablation.jsonl.gz';ranks=evaluate(raw,arms,foes,calendars('development_selection_clocks'),'mmc-range')
    # How often does the frozen selector actually change the applied expert?
    switches=defaultdict(lambda:defaultdict(list))
    for r in map(json.loads,gzip.open(raw,'rt')):
        switches[r['arm']][r['opponent']].append(r.get('our_switches',0))
    distributions={a:{o:dict(total=sum(v),median=median(v),min=min(v),max=max(v),ever=sum(x>0 for x in v)) for o,v in os.items()} for a,os in switches.items()}
    publish('minemen-v4-gate-audit',ranks[0]['name'],arms)
    # Reuse original 260 field and add the four genuine changing opponents. Both original experts stay in this common cup.
    full=focus.unique(pool);br=d/'archive.jsonl.gz';overall=evaluate(br,arms,full,calendars('development_selection_clocks'),'mmc-range')
    publish('minemen-v4-gate-archive',overall[0]['name'],arms)
    dump(d/'results.json',dict(experts=source,ablation=ranks,archive=overall,switches=distributions))
    print('Adaptive ablation',[(r['name'],round(r['mean'],3)) for r in ranks],'archive winner',overall[0]['name'],flush=True)
if __name__=='__main__':main()
