import csv
import shutil
import subprocess
import sys
from live import D,B,ROOT,WT,ping,read,wait,freeze,champions,fixtures,run_rows,native_name
from train import java
F=D/'refine'
def main():
    wait(lambda:(D/'entrants.json').exists());e=read(D/'entrants.json');parent=e['development_best'];F.mkdir(exist_ok=True)
    (F/'parent.txt').write_text(parent+'\n')
    sels={};arms={}
    for arm in ['uniform','focused']:
        choices=[]
        for seed in (4711,5821,6911):
            id=f'{arm}-{seed}';p=F/(id+'.csv')
            if not p.is_file():subprocess.run(java('HardMatchStudy',arm,seed),check=True)
            choices.append((max(float(r['dev']) for r in csv.DictReader(p.open())),id))
        sels[arm]=sorted(choices,key=lambda v:(-v[0],v[1]))[0][1]
        name='sumo-input-'+arm;arms[name]=sels[arm];champions.install(name,F/(sels[arm]+'.net'),'Second-round matched '+arm+' PPO',sels[arm],'direct')
    freeze(F/'selection.json',dict(parent=parent,checkpoints=sels))
    path=D/'screen.json';keys=[f'sinput-s-sumo-input-nofoe8-0-{i}-{s}' for i in range(2) for s in 'ab']
    wait(lambda:path.exists() and set(keys)<=read(path)['matches'].keys())
    info=read(path);manifest=F/'donors.json';freeze(manifest,dict(plan={q:info['plan'][q] for q in keys},matches={q:info['matches'][q] for q in keys}))
    subprocess.run([sys.executable,ROOT/'research/sumo-rl/calendars.py',B/'refine-clocks','--frozen-manifest',manifest],check=True)
    clocks=[]
    for src in sorted((B/'refine-clocks').glob('m*.txt.gz')):
        shutil.copyfile(src,F/src.name);clocks.append(str((F/src.name).relative_to(ROOT)))
    assert len(clocks)==4;(F/'clocks.txt').write_text('\n'.join(clocks)+'\n')
    if not (F/'transfer.csv').exists():subprocess.run(java('HardMatchStudy','test'),check=True)
    wait(lambda:(D/'masks-audit.json').exists())
    parent_alias=next(n for n,id in e['representatives'].items() if id==parent)
    weak=native_name((F/'hardest.txt').read_text().splitlines()[0]);run_rows('refinement',fixtures('r',[*arms,parent_alias,WT,ping.OLD],[WT,weak]))
    subprocess.run([sys.executable,D.parent/'cpu_limit.py','--audit',sys.executable,'-c',
        "import sys;sys.path.insert(0,sys.argv[1]);from audit_run import certify;certify('refinement')",str(D)],check=True)
if __name__=='__main__':main()
