import os
import random
import subprocess
import sys
import time
from seven_study import D,ROOT,S,F,WT,OLD,PRIOR,CELLS,make,freeze,read,sha,props,play,java,champions,wait
P=S/'pilot'
NAME='sumo-seven-first'
ID='seven-ppo-specialist-1801'
def register():
    P.mkdir(exist_ok=True);src=F/'fits'/(ID+'.net')
    while not (F/'fits'/(ID+'.csv')).exists():time.sleep(2)
    champions.install(NAME,src,'First 7-CPS best response, trained specifically against frozen best-sumo','frozen-v4-seven-first','direct')
    freeze(P/'release.json',dict(actor=NAME,checkpoint=ID,weights=sha(src),target=sha(D/'frozen'/(OLD+'.net'))))
    assert props(NAME)['duel_network']==src.read_text().strip()
def run():
    register()
    for b in range(1,9):
        tag=f'q{b:02}';games=[]
        for i,(side,own,other) in enumerate(CELLS):
            arms=[('fit',NAME)]
            if i==(b-1)%4:arms += [('wt',WT),('prior',PRIOR)]
            for label,arm in arms:
                q=f'cf7-{tag}-{label}-{side}{own}';games.append((q,make(q,arm,OLD,side,own,other,7)))
        random.Random(71023+b).shuffle(games);games=dict(games);freeze(P/(tag+'.json'),games)
        path=D/'native'/(tag+'.json')
        if not path.exists() or len(read(path)['matches'])!=len(games):play(tag,games)
def model():
    wait(P/'release.json');rows={};mids={}
    for b in range(1,9):
        tag=f'q{b:02}';q=next(q for q in wait(P/(tag+'.json')) if '-wt-' in q)
        v=wait(D/'verified'/(tag+'.json'));rows[q]=read(P/(tag+'.json'))[q];mids[q]=v['rows'][q]['mid']
    assert len(set(mids.values()))==8
    manifest=P/'donors.json';freeze(manifest,dict(plan=rows,matches=mids))
    subprocess.run([sys.executable,D.parent/'cpu_limit.py',sys.executable,ROOT/'research/sumo-rl/calendars.py',P/'clocks','--frozen-manifest',manifest],check=True)
    clocks=sorted((P/'clocks').glob('m*.txt.gz'));assert len(clocks)==8
    (P/'clocks.txt').write_text(''.join(str(p.relative_to(ROOT))+'\n' for p in clocks))
    previous=read(F/'ready.json')['methods']['ppo']['id']
    (P/'entrants.tsv').write_text('wtap\n'+''.join(n+'\t'+str((F/'fits'/(id+'.net')).relative_to(ROOT))+'\n' for n,id in ((NAME,ID),(PRIOR,previous))))
    if not (P/'transfer-7.csv').exists():subprocess.run(java('CappedFrozenResponse','eval',P,7),check=True)
if __name__=='__main__':{'run':run,'model':model}[sys.argv[1]]()
