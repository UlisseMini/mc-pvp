"""Export prospective exogenous schedules and run all frozen actors under identical simulated conditions."""
import subprocess
import sys
import time
from study import D,BUILD,ROOT,NEW,OLD,WT,read,freeze
from train_response import java
from specialist_native import names
def main():
    F=D/'model';F.mkdir(exist_ok=True)
    rows={};mids={}
    for b in range(4,33,4):
        tag=f'g{b:02}';path=D/'verified'/(tag+'.json')
        while not path.exists():time.sleep(5)
        e=read(path);plan=read(D/'native'/(tag+'.json'))['plan']
        for q,t in plan.items():
            if t['arm']==WT and t['opponent']==WT:
                rows[q]=t;mids[q]=e['rows'][q]['mid']
    assert len(mids)==32 and len(set(mids.values()))==32
    manifest=F/'donors.json';freeze(manifest,dict(plan=rows,matches=mids))
    subprocess.run([sys.executable,D.parent/'cpu_limit.py',sys.executable,ROOT/'research/sumo-rl/calendars.py',F/'clocks','--frozen-manifest',manifest],check=True)
    clocks=sorted((F/'clocks').glob('m*.txt.gz'));assert len(clocks)==32
    (F/'clocks.txt').write_text(''.join(str(c.relative_to(ROOT))+'\n' for c in clocks))
    while not (D/'specialist/ready.json').exists():time.sleep(2)
    sel=read(D/'specialist/ready.json')
    entries={'wtap':None,NEW:D/'frozen'/(NEW+'.net'),OLD:D/'frozen'/(OLD+'.net')}
    for method,r in sel['methods'].items():entries['sumo-frozen-'+method]=D/'specialist/fits'/(r['id']+'.net')
    (F/'entrants.tsv').write_text(''.join(n+('\t'+str(p.relative_to(ROOT)) if p else '')+'\n' for n,p in entries.items()))
    if not (F/'transfer.csv').exists():subprocess.run(java('InputProbe',F),cwd=ROOT,check=True)
if __name__=='__main__':main()
