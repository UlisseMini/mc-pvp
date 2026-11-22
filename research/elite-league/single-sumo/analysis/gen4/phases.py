"""Separate the opening click decision and jumps near a visible hurt cue."""
from concurrent.futures import ThreadPoolExecutor
from collections import Counter
from review import G4, FOES, WORK, fixtures, invoke
import json

def run(foe):
    cases=fixtures(G4,foe)
    results={}
    for arm in ('click-opening','click-after','no-hurt-jump','no-other-jump','forward-after'):
        rs=invoke('phase-'+foe+'-'+arm,[[*r,arm,'-'] for r,*_ in cases])
        wins=[v['outcome']==1 for v in rs]
        results[arm]={'wins':sum(wins),'draws':sum(v['outcome']==.5 for v in rs),
                      'became_win':sum(x and w!=1 for x,(_,_,w,_) in zip(wins,cases)),
                      'lost_win':sum(not x and w==1 for x,(_,_,w,_) in zip(wins,cases))}
    (WORK/('phase-'+foe+'.json')).write_text(json.dumps(results,indent=2))
if __name__=='__main__':
    with ThreadPoolExecutor(max_workers=2) as pool:list(pool.map(run,FOES))
