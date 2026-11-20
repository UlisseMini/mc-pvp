import subprocess
from pathlib import Path
from live import D,ROOT,B,WT,ping,wait,read,sha
from train import java
def main():
    wait(lambda:(D/'confirmation-selection.json').exists())
    e=read(D/'entrants.json');c=read(D/'confirmation-selection.json')
    arms={'wtap':None,ping.OLD:D.parent/'recorded-mmc-frozen/range-model/v4-ppo-g0496.net','sumo-two-ping':D.parent/'ping-ppo/two-ping.net'}
    for key in (c['model_id'],e['development_best'],e['representatives']['sumo-input-base8']):arms[key]=D/'fits'/(key+'.net')
    (D/'archive-contenders.tsv').write_text('\n'.join(n+('\t'+str(p.relative_to(ROOT)) if p else '') for n,p in arms.items())+'\n')
    for l in (D/'archive-field.tsv').read_text().splitlines():
        f=l.split('\t')
        if len(f)>1 and sha(ROOT/f[1])!=f[2]:raise ValueError('Historical policy changed '+f[0])
    if not (D/'archive.csv.gz').exists():subprocess.run(java('InputArchive'),cwd=ROOT,check=True)
if __name__=='__main__':main()
