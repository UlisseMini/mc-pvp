"""Prepare matched simulation interventions, the additional opening tournament and verified native traces."""
import os
import subprocess
import sys
import time
from study import D,BUILD,ROOT,OLD,read,freeze
from train_response import java
from native_opening import extract
from specialist_native import names
from check_games import check
from live import WT
from opening_native import O
from pathlib import Path

def wait(path):
    while not path.exists():time.sleep(3)
    return read(path)

def actor_args(selected):
    return sum((['sumo-frozen-'+label,D/'specialist/fits'/(r['id']+'.net')] for label,r in selected.items()),[])

def first_mechanisms():
    sel=wait(D/'specialist/ready.json')
    dest=D/'specialist/selection-mechanisms.jsonl'
    args=actor_args(sel['methods'])
    if not dest.exists():
        subprocess.run(java('FrozenResponseMechanics',D/'specialist/selection-clocks.txt',dest,'frozen',D/'frozen'/(OLD+'.net'),*args),check=True)
    # Also collect the same measured interventions prospectively on every fourth chronologically
    # scheduled generalist W-tap mirror. The selector never sees these outcomes.
    clocks=D/'model/clocks.txt'
    while not clocks.exists():time.sleep(4)
    selected_clocks=D/'model/mechanism-clocks.txt'
    selected_clocks.write_text('\n'.join(clocks.read_text().splitlines()[::4])+'\n')
    dest=D/'model/mechanisms.jsonl'
    if not dest.exists():
        subprocess.run(java('FrozenResponseMechanics',selected_clocks,dest,'frozen',D/'frozen'/(OLD+'.net'),*args),check=True)

def additional():
    chosen=wait(O/'ready.json')
    if not chosen['run']:return
    sel=read(D/'specialist/ready.json');folder=O/'model';folder.mkdir(exist_ok=True)
    while not (O/'native-plan.json').exists():time.sleep(2)
    plans=read(O/'native-plan.json');rows={};mids={}
    for block,plan in plans.items():
        path=D/'verified'/(block+'.json');verified=wait(path)
        for q,t in plan.items():
            if t['arm']==WT:rows[q]=t;mids[q]=verified['rows'][q]['mid']
    assert len(set(mids.values()))==16
    manifest=folder/'donors.json';freeze(manifest,dict(plan=rows,matches=mids))
    subprocess.run([sys.executable,D.parent/'cpu_limit.py',sys.executable,ROOT/'research/sumo-rl/calendars.py',folder/'clocks','--frozen-manifest',manifest],check=True)
    clocks=sorted((folder/'clocks').glob('m*.txt.gz'));assert len(clocks)==16
    (folder/'clocks.txt').write_text(''.join(str(p.relative_to(ROOT))+'\n' for p in clocks))
    args=actor_args(chosen['methods']);primary=sel['primary'];first=D/'specialist/fits'/(sel['methods'][primary]['id']+'.net')
    with (folder/'entrants.tsv').open('w') as out:
        out.write('wtap\n')
        for name,p in [('sumo-frozen-'+primary,first),(OLD,D/'frozen'/(OLD+'.net')),*zip(args[::2],args[1::2])]:
            out.write(name+'\t'+str(p.relative_to(ROOT))+'\n')
    if not (folder/'transfer.csv').exists():subprocess.run(java('InputProbe',folder),check=True)
    if not (folder/'mechanisms.jsonl').exists():
        subprocess.run(java('FrozenResponseMechanics',folder/'clocks.txt',folder/'mechanisms.jsonl','sumo-frozen-'+primary,first,*args),check=True)

def native_details(letters="eos"):
    done=set()
    while True:
        for manifest in sorted((D/'native').glob('['+letters+'][0-9][0-9].json')):
            game=read(manifest);tag=manifest.stem
            verified=D/'verified'/(tag+'.json')
            if tag in done or (tag[0]=='s' and not verified.exists()):continue
            checks={}
            for q,mid in game['matches'].items():
                trial=game['plan'][q]
                if trial['opponent']!=OLD and tag[0]=='s':continue
                try:row=check(mid,trial)
                except (FileNotFoundError,EOFError):
                    if time.time()-read(ROOT/'runtime/results'/(mid+'.json'))['finish']/1000>120:raise
                    continue
                if row['valid']:
                    assert read(ROOT/'runtime/results'/(mid+'.json'))['request']==q
                    extract(row)
                checks[q]=row
            if verified.exists():done.add(tag)
            if tag[0] in 'oecklqht' and not verified.exists() and checks.keys()==game['plan'].keys():
                if all(x['valid'] for x in checks.values()):freeze(verified,dict(original=game['matches'],rows=checks,invalid=[]))
                else:freeze(verified.with_name(tag+'-invalid.json'),{q:r for q,r in checks.items() if not r['valid']})
        if (D/'assessment.json').exists():return
        time.sleep(12)
if __name__=='__main__':
    {'mechanisms':first_mechanisms,'opening':additional,'native':native_details,'branch-audit':lambda:native_details('c'),'seven-audit':lambda:native_details('kl'),'pilot-audit':lambda:native_details('q'),'pilot-twenty-audit':lambda:native_details('h'),'curriculum-audit':lambda:native_details('t')}[sys.argv[1]]()
