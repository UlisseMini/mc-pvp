"""Fit a first-hurt expert choice using exact alternate continuations and paired W-tap controls."""
import argparse
import gzip
import json
import random
import shutil
import subprocess
import itertools
from pathlib import Path
from run import HERE,ROOT,java,compile_model,calendars
from tournament import roster,grid
from human_sparring import controls,samples,bucket
from native_trials import run_batch
from challenge_native import install


def opponents(out,which):
    seeds='prospective' if which=='prospective' else 'selection'
    proxies=samples(out,seeds)
    # In training, seeded sparring opponents are independent of all selection and prospective streams.
    if which=='training':
        from mechanism import unpack,encode
        private={}
        for n,p in proxies.items():
            f,w=unpack(p.read_text());w[-2]+=3000000
            q=out/(n+'-train.net');q.write_text(encode(f,w)+'\n');private[n+'-train']=q
        proxies=private
    return controls()|dict.fromkeys(('pressure','trade','select','counterstrafe','reset','zest-strafe'))|proxies


def gather(out,which,clocks):
    path=out/(which+'-field.txt');roster(path,opponents(out,which))
    dest=out/(which+'.jsonl.gz')
    with dest.open('xb') as raw,gzip.GzipFile(fileobj=raw,mode='wb',mtime=0) as compressed:
        with subprocess.Popen(java('RouterData',which,path,*clocks),stdout=subprocess.PIPE,cwd=ROOT) as p:
            shutil.copyfileobj(p.stdout,compressed)
            if p.wait():raise RuntimeError('Counterfactual target generation failed')


def train(out):
    out.mkdir(exist_ok=False,parents=True)
    gather(out,'training',calendars('training_clocks'))
    gather(out,'selection',calendars('development_selection_clocks'))
    for split in ('training','selection'):
        with gzip.open(out/(split+'.jsonl.gz'),'rt') as src,gzip.open(out/(split+'.txt.gz'),'xt') as dst:
            for line in src:
                r=json.loads(line)
                g=bucket(r['opponent'].removesuffix('-train'))
                dst.write(' '.join(map(str,[g,r['default_full'],r['wtap_full'],*r.get('x',[]),*r.get('scores',[])]))+'\n')
    subprocess.run(java('RouterFit',out/'training.txt.gz',out/'selection.txt.gz',out),check=True,cwd=ROOT)


def validate(selected,out,clocks):
    out.mkdir(exist_ok=False,parents=True)
    arms=controls()|{'router':selected/'router.net'}
    grid(out,'regulation',arms,opponents(out,'prospective'),clocks)
    gather(out,'prospective',clocks)


def live(selected,out):
    install({'elite-first-hurt':selected/'router.net'})
    wt='research-wtap20';hold='research-hold20';ref='elite-population'
    fixed=[ref,'elite-v1-hybrid','elite-br-hybrid','elite-br-hold']
    arms=[wt,hold,*fixed,'elite-first-hurt']
    rows=[]
    for opp in (wt,hold,ref,'elite-br-hybrid','elite-br-hold','wtap-tick-window','human-pressure-151121'):
        for n,rtt,side,arm in itertools.product(range(3),(0,80),'ab',arms):
            name=f'er1-{opp}-{arm}-{n}-{rtt}-{side}';a,b=(arm,opp) if side=='a' else (opp,arm)
            rows.append((name,dict(mode='sumo',profile='mmc',opponent=opp,arm=arm,rep=n,rtt=rtt,side=side,
                    command=f'lab sumo {a} {b} {rtt} {rtt} {name} preset=mmc reach=3 cps=20 tapdelay=0 aimdelay=0 aimnoise=0 aimrate=0')))
    random.Random(93907).shuffle(rows);run_batch(dict(rows),out)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('mode',choices=('train','validate','native'))
    p.add_argument('out',type=Path);p.add_argument('--selected',type=Path);p.add_argument('--clocks',type=Path)
    a=p.parse_args()
    if a.mode!='native':compile_model()
    if a.mode=='train':train(a.out)
    elif a.mode=='validate':validate(a.selected,a.out,sorted(a.clocks.glob('*.txt.gz')))
    else:live(a.selected,a.out)
