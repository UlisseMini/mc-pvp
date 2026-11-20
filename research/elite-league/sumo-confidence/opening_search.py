"""Select and publish two families of specialists, using only already exposed development data."""
import csv
import subprocess
import time
from study import D,ROOT,freeze,read,sha
from train_response import java,champions
O=D/'opening'
F=D/'specialist'

def main():
    while not (F/'ready.json').exists():time.sleep(4)
    prior=read(F/'ready.json')
    if float(prior['methods'][prior['primary']]['merit'])>=.80:
        freeze(O/'ready.json',dict(run=False,reason='First-stage development reached threshold'));return
    seeds=(1327,2011,4271,6047,7429,8147,9161,10513)
    ids=[]
    for i,seed in enumerate(seeds):
        mode=1+i%2;name=f'opening-{mode}-{seed}';ids.append(name)
        parents=sorted((F/'fits').glob('ppo-old-*.net'))
        assert len(parents)==4
        if not (F/'fits'/(name+'.csv')).exists():
            subprocess.run(java('FrozenOpeningSearch',name,mode,seed,*parents),cwd=ROOT,check=True)
    if not (O/'selection.csv').exists():
        subprocess.run(java('FrozenOpeningSearch','select',*ids),cwd=ROOT,check=True)
    results=list(csv.DictReader((O/'selection.csv').open()))
    selected={}
    for mode,label in [(1,'cue'),(2,'gate')]:
        r=min((r for r in results if r['id'].startswith(f'opening-{mode}-')),key=lambda r:(-float(r['merit']),r['id']))
        selected[label]=r
        champions.install('sumo-frozen-'+label,F/'fits'/(r['id']+'.net'),'Learned opening and continuation against frozen best-sumo, '+r['id'],'frozen-v4-'+label)
    primary=min(selected,key=lambda label:(-float(selected[label]['merit']),label))
    freeze(O/'ready.json',dict(run=True,primary=primary,methods=selected,
        weights={r['id']:sha(F/'fits'/(r['id']+'.net')) for r in results},target=prior['target_sha256']))
    print('Opening specialists',selected,flush=True)
if __name__=='__main__':main()
