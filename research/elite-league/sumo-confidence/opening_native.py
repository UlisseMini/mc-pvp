import random
from study import D,OLD,WT,CELLS,freeze,read,ping,play,props,sha
from specialist_native import names
O=D/'opening'

def plans():
    selected=read(O/'ready.json')
    if not selected['run']:return {}
    label=selected['primary'];net=D/'specialist/fits'/(selected['methods'][label]['id']+'.net')
    primary='sumo-frozen-'+label;first,_=names()
    assert sha(net)==selected['weights'][net.stem] and props(primary)['duel_network']==net.read_text().strip()
    plans={}
    for i in range(1,17):
        games=[]
        for c,(side,own,other) in enumerate(CELLS):
            pairs=[('p0',primary),('p1',primary)]
            if (i-1)%4==c:pairs += [('first',first),('wt',WT)]
            for tag,arm in pairs:
                q=f'cf20-o{i:02}-{tag}-{side}{own}'
                games.append((q,ping.trial(q,arm,OLD,side,own,other)))
        random.Random(22519+i).shuffle(games);plans[f'o{i:02}']=dict(games)
    freeze(O/'native-plan.json',plans);return plans

def fill(i):
    if not (O/'ready.json').exists():return
    all_plans=plans()
    for k in list(all_plans)[:max(0,i)]:
        p=D/'native'/(k+'.json')
        if p.exists() and len(read(p)['matches'])==len(all_plans[k]):continue
        play(k,all_plans[k])
    from equal_links import fill as equal_fill
    equal_fill(i-4)
