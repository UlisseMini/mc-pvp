import csv
import random
import subprocess
import sys
from study import D,ROOT,OLD,WT,CELLS,play,read,freeze,props,sha
from seven_study import F,S,PRIOR,java,wait,make,champions
C=D/'curriculum'
def train():
    rel=wait(S/'ready.json');p=read(F/'ready.json')['methods']['ppo'];ids=[]
    paths={'first':F/'fits/seven-ppo-specialist-1801.net','selected':F/'fits'/(rel['methods']['ppo']['id']+'.net')}
    for seed in (2707,5081):
        for init,src in paths.items():
            for mode in ('direct','ramp'):
                id=f'cap-{mode}-{init}-{seed}';ids.append(id)
                if not (F/'fits'/(id+'.csv')).exists():subprocess.run(java('FrozenCapCurriculum',id,mode,seed,src),check=True)
    if not (C/'selection.csv').exists():subprocess.run(java('FrozenOpeningSearch','select-dir',C,*ids,p['id']),check=True)
    rows=list(csv.DictReader((C/'selection.csv').open()));winners={}
    for m in ('ramp','direct'):
        r=min((r for r in rows if r['id'].startswith('cap-'+m)),key=lambda r:(-float(r['merit']),r['id']));winners[m]=r
        champions.install('sumo-cap-'+m,F/'fits'/(r['id']+'.net'),'Learned transfer to 20 CPS, '+r['id'],'frozen-v4-cap-'+m)
    name=min(winners,key=lambda k:(-float(winners[k]['merit']),k));q=winners[name]
    base=next(r for r in rows if r['id']==p['id'])
    qualifies=float(q['merit'])-float(base['merit'])>=.025 and min(float(q[k]) for k in ('seat_a','seat_b'))>=.70
    freeze(C/'ready.json',dict(qualified=qualifies,primary=name,methods=winners,previous=base,weights={n:sha(F/'fits'/(n+'.net')) for n in ids}))
    print('Curriculum',name,q,qualifies,flush=True)
def plans():
    sel=read(C/'ready.json')
    if not sel['qualified']:return {}
    name='sumo-cap-'+sel['primary'];net=F/'fits'/(sel['methods'][sel['primary']]['id']+'.net')
    assert props(name)['duel_network']==net.read_text().strip() and sha(net)==sel['weights'][net.stem]
    out={}
    for b in range(1,17):
        games=[]
        for i,(s,a,o) in enumerate(CELLS+[('a' if b%2 else 'b',80,80)]):
            arms=[('fit',name)]
            if i==(b-1)%4:arms += [('wt',WT),('prior',PRIOR)]
            for code,n in arms:
                q=f'cf20-t{b:02}-{code}-{s}{a}';games.append((q,make(q,n,OLD,s,a,o,20)))
        random.Random(272081+b).shuffle(games);out[f't{b:02}']=dict(games)
    freeze(C/'native-plan.json',out);return out
def native():
    wait(C/'ready.json')
    for k,rows in plans().items():play(k,rows)
def model():
    s=wait(C/'ready.json')
    if not s['qualified']:return
    full=wait(C/'native-plan.json');rows={};mids={}
    for k,p in full.items():
        v=wait(D/'verified'/(k+'.json'));q=next(q for q in p if '-wt-' in q)
        rows[q]=p[q];mids[q]=v['rows'][q]['mid']
    assert len(set(mids.values()))==16
    folder=C/'model';folder.mkdir(exist_ok=True);m=folder/'donors.json';freeze(m,dict(plan=rows,matches=mids))
    subprocess.run([sys.executable,D.parent/'cpu_limit.py',sys.executable,ROOT/'research/sumo-rl/calendars.py',folder/'clocks','--frozen-manifest',m],check=True)
    clocks=sorted((folder/'clocks').glob('m*.txt.gz'));assert len(clocks)==16
    (folder/'clocks.txt').write_text(''.join(str(p.relative_to(ROOT))+'\n' for p in clocks))
    pairs=[(PRIOR,s['previous']['id'])]+[('sumo-cap-'+k,r['id']) for k,r in s['methods'].items()]
    (folder/'entrants.tsv').write_text('wtap\n'+''.join(n+'\t'+str((F/'fits'/(id+'.net')).relative_to(ROOT))+'\n' for n,id in pairs))
    if not (folder/'transfer-20.csv').exists():subprocess.run(java('CappedFrozenResponse','eval',folder,20),check=True)
if __name__=='__main__':{'train':train,'model':model,'native':native}[sys.argv[1]]()
