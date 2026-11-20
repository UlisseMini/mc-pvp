"""Equal-ping supplemental test of the best development-selected response."""
import random
from opening_native import O
from study import D,WT,OLD,read,freeze,ping,play
from specialist_native import names
E=D/'equal'
def entrants():
    names()
    methods=list(read(D/'specialist/ready.json')['methods'].items())
    opening=read(O/'ready.json')
    if opening['run']:methods.extend(opening['methods'].items())
    label,data=min(methods,key=lambda pair:(-float(pair[1]['merit']),pair[0]))
    return 'sumo-frozen-'+label,data['id']
def schedule():
    actor,net=entrants();rows={};cells=[(s,p) for s in 'ab' for p in (0,80)]
    for i in range(1,13):
        block=[]
        for k,(s,p) in enumerate(cells):
            for tag,arm in [('p0',actor),('p1',actor),('wt',WT)]:
                q=f'cf20-e{i:02}-{tag}-{s}{p}';block.append((q,ping.trial(q,arm,OLD,s,p,p)))
        random.Random(59011+i).shuffle(block);rows[f'e{i:02}']=dict(block)
    freeze(E/'plan.json',dict(actor=actor,checkpoint=net,blocks=rows));return rows
def fill(count):
    if count<=0 or not (O/'ready.json').exists():return
    rows=schedule()
    for k in list(rows)[:count]:
        p=D/'native'/(k+'.json')
        if p.exists() and len(read(p)['matches'])==len(rows[k]):continue
        play(k,rows[k])
