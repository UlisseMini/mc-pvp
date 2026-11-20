"""Interleave an independent native best-response test with the generalist blocks."""
import random
import time
from study import D,FOES,WT,OLD,CELLS,ping,play,freeze,read,props
from live import sha
N=24

def names():
    sel=read(D/'specialist/ready.json')
    for method,row in sel['methods'].items():
        net=D/'specialist/fits'/(row['id']+'.net')
        assert sha(net)==sel['weights'][row['id']]
        assert props('sumo-frozen-'+method)['duel_network']==net.read_text().strip()
    return 'sumo-frozen-'+sel['primary'],'sumo-frozen-'+sel['alternate']

def fixtures(b):
    pri,alt=names();rows=[]
    def add(tag,arm,foe,s,a,o):
        q=f'cf20-s{b:02}-{tag}-{s}{a}'
        rows.append((q,ping.trial(q,arm,foe,s,a,o)))
    for cell,(s,a,o) in enumerate(CELLS):
        for i in range(2):add('p'+str(i),pri,OLD,s,a,o)
        add('alt',alt,OLD,s,a,o)
        add('field',pri,FOES[(b-1)%4],s,a,o)
        if cell==(b-1)%4:
            add('wt',WT,OLD,s,a,o);add('mirror',OLD,OLD,s,a,o)
    random.Random(638529+b).shuffle(rows)
    return dict(rows)

def fill_until(b):
    from recover import pending
    pending()
    full={f's{i:02}':fixtures(i) for i in range(1,N+1)}
    freeze(D/'specialist-plan.json',full)
    for i in range(1,max(0,min(N,3*max(0,b-2)))+1):
        tag=f's{i:02}';p=D/'native'/(tag+'.json')
        if p.exists() and len(read(p)['matches'])==len(full[tag]):continue
        play(tag,full[tag])
    from opening_native import fill
    fill(min(16,2*max(0,b-3)))
    from branch_study import fill as branch_fill
    branch_fill(min(16,2*max(0,b-5)))

def finish():
    while not (D/'specialist/ready.json').exists():time.sleep(3)
    fill_until(N+4)
    while not (D/"opening/ready.json").exists():time.sleep(3)
    from opening_native import fill
    fill(16)
    while not (D/"branches/ready.json").exists():time.sleep(3)
    from branch_study import fill as branch_fill
    branch_fill(16)
