import random
import sys
from seven_pilot import P,NAME,PRIOR,WT,OLD,CELLS,make,read,D,freeze,play,register
def run():
    register()
    for b in range(1,9):
        tag=f'h{b:02}';rows=[]
        for i,(s,a,o) in enumerate(CELLS):
            arms=[('fit',NAME)]
            if i==(b-1)%4:arms += [('prior',PRIOR),('wt',WT)]
            for num,n in arms:
                q=f'cf7-{tag}-{num}-{s}{a}';rows.append((q,make(q,n,OLD,s,a,o,20)))
        random.Random(72059+b).shuffle(rows);plan=dict(rows)
        freeze(P/(tag+'.json'),plan);play(tag,plan)
if __name__=='__main__':run()
