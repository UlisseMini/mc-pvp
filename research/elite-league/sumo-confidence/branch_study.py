"""Learn from paired counterfactual rollouts and test the distilled actor prospectively."""
import csv
import random
import subprocess
import sys
import time
from study import D,ROOT,BUILD,OLD,WT,ping,play,freeze,read,sha,props
from train_response import java,champions
from specialist_native import names
from postprocess import wait
F=D/'specialist'
B=D/'branches'
def train():
    parent=wait(F/'ready.json')['methods']['ppo']
    if float(parent['merit'])>=.85:
        freeze(B/'ready.json',dict(run=False,reason='PPO winner reached threshold'));return
    net=F/'fits'/(parent['id']+'.net')
    names();ids=[]
    for seed in (1907,3491,6101,9833):
        name=f'counterfactual-{seed}';ids.append(name)
        if not (F/'fits'/(name+'-branches.csv')).exists():subprocess.run(java('FrozenBranchLearning',name,seed,net),check=True)
    if not (B/'selection.csv').exists():subprocess.run(java('FrozenOpeningSearch','select-dir',B,*ids),check=True)
    scores=list(csv.DictReader((B/'selection.csv').open()));best=min(scores,key=lambda r:(-float(r['merit']),r['id']))
    path=F/'fits'/(best['id']+'.net');champions.install('sumo-frozen-branches',path,'Counterfactual best-response learner '+best['id'],'frozen-v4-branches')
    freeze(B/'ready.json',dict(run=True,primary='branches',methods={'branches':best},parent=parent['id'],
        weights={n:sha(F/'fits'/(n+'.net')) for n in ids}))
    print('Counterfactual specialist:',best,flush=True)
def plans():
    sel=read(B/'ready.json')
    if not sel['run']:return {}
    label='sumo-frozen-branches';r=sel['methods']['branches'];src=F/'fits'/(r['id']+'.net')
    assert sha(src)==sel['weights'][r['id']] and props(label)['duel_network']==src.read_text().strip()
    cells=[(s,a,b) for s in 'ab' for a,b in [(31,109),(109,31),(80,80)]];out={}
    for i in range(1,17):
        batch=[]
        for k,(s,own,other) in enumerate(cells):
            arms=[('c',label)]
            if k==(i-1)%6:arms+=[('p','sumo-frozen-ppo'),('w',WT)]
            for tag,arm in arms:
                q=f'cf20-c{i:02}-{tag}-{s}{own}';batch.append((q,ping.trial(q,arm,OLD,s,own,other)))
        random.Random(98487+i).shuffle(batch);out[f'c{i:02}']=dict(batch)
    freeze(B/'native-plan.json',out);return out
def fill(n):
    if n<=0 or not (B/'ready.json').exists():return
    all_plans=plans()
    for k in list(all_plans)[:n]:
        p=D/'native'/(k+'.json')
        if p.exists() and len(read(p)['matches'])==len(all_plans[k]):continue
        play(k,all_plans[k])
def model():
    sel=wait(B/'ready.json')
    if not sel['run']:return
    full=wait(B/'native-plan.json');rows={};mids={}
    for tag,plan in full.items():
        v=wait(D/'verified'/(tag+'.json'))
        for q,g in plan.items():
            if g['arm']==WT:rows[q]=g;mids[q]=v['rows'][q]['mid']
    assert len(set(mids.values()))==16
    folder=B/'model';folder.mkdir(exist_ok=True);manifest=folder/'donors.json'
    freeze(manifest,dict(plan=rows,matches=mids))
    subprocess.run([sys.executable,D.parent/'cpu_limit.py',sys.executable,ROOT/'research/sumo-rl/calendars.py',folder/'clocks','--frozen-manifest',manifest],check=True)
    clocks=sorted((folder/'clocks').glob('m*.txt.gz'));assert len(clocks)==16
    (folder/'clocks.txt').write_text(''.join(str(p.relative_to(ROOT))+'\n' for p in clocks))
    ppo=F/'fits'/(sel['parent']+'.net');net=F/'fits'/(sel['methods']['branches']['id']+'.net')
    (folder/'entrants.tsv').write_text('wtap\n'+f'sumo-frozen-ppo\t{ppo.relative_to(ROOT)}\nsumo-frozen-branches\t{net.relative_to(ROOT)}\n')
    if not (folder/'transfer.csv').exists():subprocess.run(java('InputProbe',folder),check=True)
    if not (folder/'mechanisms.jsonl').exists():subprocess.run(java('FrozenResponseMechanics',folder/'clocks.txt',folder/'mechanisms.jsonl','sumo-frozen-ppo',ppo,'sumo-frozen-branches',net),check=True)
if __name__=='__main__':{'train':train,'model':model}[sys.argv[1]]()
