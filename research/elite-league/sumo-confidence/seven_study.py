"""CPS-matched head-to-head cross-over. This changes the physical limiter on both actual clients."""
import csv
import random
import subprocess
import sys
import time
from study import D,BUILD,ROOT,CELLS,OLD,WT,freeze,read,sha,props,play,ping
from train_response import java,champions
from specialist_native import names
from postprocess import wait
S=D/'seven'
F=D/'specialist'
PRIOR='sumo-frozen-ppo'
SEEDS=(1801,3529,7919)
def fit(method,id,net,seed):
    p=F/'fits'/(id+'.csv')
    if not p.exists():subprocess.run(java('CappedFrozenResponse',method,id,net,seed),check=True)
    return max(float(r['merit']) for r in csv.DictReader(p.open()))
def train():
    previous=wait(F/'ready.json')['methods']['ppo'];names()
    proof=subprocess.check_output(java('CappedFrozenResponse','check'),text=True);(S/'model-cap-check.txt').write_text(proof)
    start={'specialist':F/'fits'/(previous['id']+'.net'),'generalist':D.parent/'sumo-inputs/fits/full32-66047.net'}
    ids=[]
    for seed in SEEDS:
        ranks={}
        for k,net in start.items():
            id=f'seven-ppo-{k}-{seed}';ranks[id]=fit('ppo',id,net,seed);ids.append(id)
        parent=min(ranks,key=lambda k:(-ranks[k],k));id=f'seven-evolve-{seed}'
        fit('evolve',id,F/'fits'/(parent+'.net'),seed);ids.append(id)
    if not (S/'selection.csv').exists():subprocess.run(java('CappedFrozenResponse','select',*ids),check=True)
    ranks=list(csv.DictReader((S/'selection.csv').open()));winners={}
    for label in ('ppo','evolve'):
        r=min((r for r in ranks if r['id'].startswith('seven-'+label+'-')),key=lambda r:(-float(r['merit']),r['id']))
        winners[label]=r
        champions.install('sumo-seven-'+label,F/'fits'/(r['id']+'.net'),'7 CPS best response to frozen v4, '+r['id'],'frozen-v4-seven-'+label)
    primary=min(winners,key=lambda label:(-float(winners[label]['merit']),label!='ppo'))
    freeze(S/'ready.json',dict(primary=primary,methods=winners,weights={n:sha(F/'fits'/(n+'.net')) for n in ids},old_ppo=previous['id']))
    print('Seven-CPS specialists',winners,flush=True)
def make(q,arm,foe,side,own,other,cap):
    trial=ping.trial(q,arm,foe,side,own,other)
    return dict(trial,cps_cap=cap,command=trial['command'].replace(' cps=20 ',f' cps={cap} '))
def plan(block,phase):
    # Shuffle the control and learned sub-blocks independently; run immediately adjacent
    # when the learner is ready. The protocol allows control precollection in earlier blocks.
    rows=[];primary=None
    if phase=='l':
        sel=read(S/'ready.json');label=sel['primary'];primary='sumo-seven-'+label
        net=F/'fits'/(sel['methods'][label]['id']+'.net')
        assert sha(net)==sel['weights'][net.stem] and props(primary)['duel_network']==net.read_text().strip()
    for i,(side,own,other) in enumerate(CELLS):
        for arm,tag in ([(PRIOR,'prior'),(WT,'wt')] if phase=='k' else [(primary,'fit')]):
            for cap in (7,20):
                q=f'cf7-{phase}{block:02}-{tag}-{cap}-{side}{own}'
                rows.append((q,make(q,arm,OLD,side,own,other,cap)))
        if phase=='l' and i==((block-1)//2)%4:
            foe=WT if block%2==1 else 'sumo-own-ping'
            for arm,tag in [(primary,'newfield'),(WT,'wtfield')]:
                q=f'cf7-{phase}{block:02}-{tag}-{side}{own}'
                rows.append((q,make(q,arm,foe,side,own,other,7)))
    random.Random(607711+block+(0 if phase=='k' else 17783)).shuffle(rows)
    return dict(rows)
def fill(n,only_ready=True):
    for i in range(1,min(16,n)+1):
        for phase in 'kl':
            if phase=='l' and not (S/'ready.json').exists():
                if only_ready:continue
                wait(S/'ready.json')
            name=f'{phase}{i:02}';p=D/'native'/(name+'.json');games=plan(i,phase)
            freeze(S/(name+'-plan.json'),games)
            if not p.exists() or len(read(p)['matches'])!=len(games):play(name,games)
def model():
    chosen=wait(S/'ready.json');folder=S/'model';folder.mkdir(exist_ok=True)
    rows={};mids={}
    for i in range(1,17):
        tag=f'k{i:02}';s,a,_=CELLS[(i-1)%4];q=f'cf7-{tag}-wt-7-{s}{a}'
        v=wait(D/'verified'/(tag+'.json'));rows[q]=plan(i,'k')[q];mids[q]=v['rows'][q]['mid']
    assert len(set(mids.values()))==16
    manifest=folder/'donors.json';freeze(manifest,dict(plan=rows,matches=mids))
    subprocess.run([sys.executable,D.parent/'cpu_limit.py',sys.executable,ROOT/'research/sumo-rl/calendars.py',folder/'clocks','--frozen-manifest',manifest],check=True)
    clocks=sorted((folder/'clocks').glob('m*.txt.gz'));assert len(clocks)==16
    (folder/'clocks.txt').write_text(''.join(str(p.relative_to(ROOT))+'\n' for p in clocks))
    opts=[(PRIOR,F/'fits'/(chosen['old_ppo']+'.net')),(OLD,D/'frozen'/(OLD+'.net'))]
    opts += [('sumo-seven-'+k,F/'fits'/(r['id']+'.net')) for k,r in chosen['methods'].items()]
    (folder/'entrants.tsv').write_text('wtap\n'+''.join(n+'\t'+str(p.relative_to(ROOT))+'\n' for n,p in opts))
    for cap in (7,20):
        if not (folder/f'transfer-{cap}.csv').exists():
            subprocess.run(java('CappedFrozenResponse','eval',folder,cap),check=True)
if __name__=='__main__':{'train':train,'model':model}[sys.argv[1]]()
