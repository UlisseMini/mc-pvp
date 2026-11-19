"""Stress the two learned champions against every portable historical policy under recorded MMC."""
import gzip
import json
from collections import Counter,defaultdict
from adaptation import dump
import focus
from recorded_mmc_league import DEST,TEMP,evaluate,publish
from mmc_league import groups
from mmc_trade_fit import pool,ACTOR
from mmc_click_bias import PARENT
from recorded_mmc_league import FROZEN
from run import calendars,compile_model

def field():
    trained={n:p for group in groups().values() for n,p in group.items()}|pool()|focus.BASE
    trained[PARENT]=FROZEN/'mmc13-trades'/(PARENT+'.net')
    students=[]
    for i in (1,2):
        sub=DEST/f'round-{i:02d}'
        n=json.loads((sub/'selection.json').read_text())['student']
        trained[n]=sub/(n+'.net');students.append(n)
    return trained,students

def run():
    trained,students=field();arms={n:trained[n] for n in ['wtap',ACTOR,PARENT,*students]}
    foes=focus.unique(trained)
    d=DEST/'archive';d.mkdir(exist_ok=True)
    clocks=calendars('development_selection_clocks')
    dump(d/'protocol.json',dict(arms={n:focus.identity(n,p) for n,p in arms.items()},
        foes={n:focus.identity(n,p) for n,p in foes.items()},clocks=[p.name for p in clocks],
        scope='All portable archived development policies, deduplicated by exact weights or builtin script identity. Both seats, 0 and 80 ms. Pre-existing calendar selection panel.'))
    compile_model()
    dest=d/'model.jsonl.gz';rank=evaluate(dest,arms,foes,clocks)
    dump(d/'ranks.json',rank);publish('minemen-v3-archive-model',rank[0]['name'],arms)
    cells=defaultdict(lambda:defaultdict(lambda:defaultdict(Counter)))
    with gzip.open(dest,'rt') as f:
        for row in map(json.loads,f):
            c=cells[row['arm']][row['opponent']][row['side']+str(row['rtt'])]
            c[{1:'W',.5:'D',0:'L'}[row['score']]]+=1
    dump(d/'cells.json',cells)
    print('Archive model',len(foes),'opponents;',rank[0]['name'],rank[0]['weakest'],flush=True)

if __name__=='__main__':run()
