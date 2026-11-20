"""Schedule matched ordinary Minecraft bouts on the main lab and preserve exact fixture IDs."""
import csv
import json
import random
import shutil
import subprocess
import sys
from collections import defaultdict
from pathlib import Path
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
sys.path.insert(0,str(HERE.parent))
from native_trials import load_batch
import champions
WT='research-wtap20'
OLD='v4-ppo-g0496'
def trial(q,arm,foe,side,own,other):
    a,b,pa,pb=(arm,foe,own,other) if side=='a' else (foe,arm,other,own)
    return dict(mode='sumo',profile='mmc',diameter=13,start_gap=8,fresh_hit_ticks=10,
        knockback_model='mmc_recorded_range_v4',aim_input='mouse',
        arm=arm,opponent=foe,side=side,rtt=own,opponent_rtt=other,ping_a=pa,ping_b=pb,
        command=f'lab sumo {a} {b} {pa} {pb} {q} preset=mmc reach=3 cps=20 tapdelay=0 aimdelay=0 aimnoise=0 aimrate=0 aiminput=mouse')
def play(name,rows):
    path=HERE/(name+'.json')
    found=json.loads(path.read_text())['matches'] if path.exists() else {}
    load_batch().run(rows,2,path,2,True,found,2)
    return json.loads(path.read_text())['matches']
def donors():
    rows={}
    for k,(own,other) in enumerate([(23,97),(97,23),(0,0),(80,80)]):
        q=f'pingppo-clock-{k}';rows[q]=trial(q,WT,WT,'a',own,other)
    mids=play('donors',rows)
    out=ROOT/'build/elite-league/ping-ppo-clocks'
    subprocess.run([sys.executable,str(ROOT/'research/sumo-rl/calendars.py'),out,'--frozen-manifest',HERE/'donors.json'],check=True)
    for k,q in enumerate(rows):shutil.copyfile(out/(mids[q]+'.txt.gz'),HERE/f'sealed-{k}.txt.gz')
def native():
    scores=defaultdict(list)
    with (HERE/'transfer.csv').open() as f:
        for r in csv.DictReader(f):
            if r['arm']=='two-ping' and r['foe']!='wtap':scores[r['foe']].append(float(r['reward']))
    # Explicit field order tie-break, shared with the panel.
    weakest=min(scores,key=lambda n:sum(scores[n])/len(scores[n]))
    alias={'hold':'research-hold20','trade':'trade-wtap','zest-strafe':'research-zest-strafe'}
    foes=[WT,alias.get(weakest,weakest)]
    for arm in ('two-ping','own-ping'):
        champions.install('sumo-'+arm,HERE/(arm+'.net'),'Independent RTT PPO, '+arm,'ping-ppo-'+arm,'direct')
    rows=[]
    for arm in ['sumo-two-ping','sumo-own-ping',OLD,WT]:
        for i,foe in enumerate(foes):
            for j,(own,other) in enumerate([(23,97),(97,23)]):
                for side in 'ab':
                    q=f'pingppo-hud2-{arm}-{i}-{j}-{side}';rows.append((q,trial(q,arm,foe,side,own,other)))
    random.Random(92764).shuffle(rows)
    play('native-v2',dict(rows))
def remembered():
    # Keep every control selected in advance and replace all subject bouts.
    base=json.loads((HERE/'native-v2.json').read_text())
    rows={};completed={}
    for q,r in base['plan'].items():
        if r['arm']=='sumo-two-ping':
            key=q.replace('pingppo-hud2','pingppo-hud3')
            rows[key]=dict(r,command=r['command'].replace(q,key))
        else:
            rows[q]=r;completed[q]=base['matches'][q]
    path=HERE/'native-final.json'
    if path.exists():completed.update(json.loads(path.read_text())['matches'])
    load_batch().run(rows,2,path,2,True,completed,2)
if __name__=='__main__':
    {'donors':donors,'native':native,'remembered':remembered}[sys.argv[1]]()
