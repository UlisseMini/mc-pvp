"""Check the lateral-input intervention with new action seeds on the same development clocks."""
import json,random
from concurrent.futures import ThreadPoolExecutor
from review import FIELD,FOES,WORK,G4,WT,CLOCKS,invoke

def run(foe):
    cells=[];rng=random.Random(71047593)
    for c in CLOCKS:
        for rep in range(2):
            for s in 'ab':
                for a,b in [(31,31),(109,109),(31,109),(109,31)]:
                    cells.append([FIELD[foe]['reference'],c,s,a,b,rng.getrandbits(60),rng.getrandbits(60)])
    result={}
    for actor,arm in [(G4,'intact'),(G4,'no-strafe'),(WT,'intact')]:
        rows=invoke('check-'+foe+'-'+actor+'-'+arm,[[FIELD[actor]['reference'],*c,arm,'-'] for c in cells])
        result[actor+'/'+arm]=[r['outcome'] for r in rows]
    (WORK/('check-'+foe+'.json')).write_text(json.dumps(result))

if __name__=='__main__':
    with ThreadPoolExecutor(max_workers=2) as pool:
        list(pool.map(run,[*FOES,'remote-002-main-gru','hold','sumo-frozen-ppo','remote-004-main-mlp']))
