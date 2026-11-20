"""Run a generation's two roles and the matched memoryless control to their fixed budgets."""
import argparse
import fcntl
import json
import sys
import subprocess
import time
from pathlib import Path
from run_training import train
HERE=Path(__file__).resolve().parent

def operate(op,g):
    subprocess.run([sys.executable,HERE.parent/'cpu_limit.py','nice','-n','8',sys.executable,
                    HERE/'generation.py',op,str(g)],check=True)

def run(g,attach):
    p=HERE/'evidence'/f'g{g:03d}';p.mkdir(parents=True,exist_ok=True)
    with (p/'training.lock').open('a') as lock:
        fcntl.flock(lock.fileno(),fcntl.LOCK_EX)
        if (p/'tournament.json').exists():return
        operate('prepare',g)
        if attach:
            while True:
                progress=p/'exploiter/progress.json'
                if progress.exists() and json.loads(progress.read_text())['update']>=256:break
                other=subprocess.run(['ps','-p',str(attach),'-o','command='],capture_output=True,text=True)
                if 'run_training.py' not in other.stdout:break
                time.sleep(2)
        train(p/'exploiter',256);operate('counter',g)
        train(p/'main',512);train(p/'mlp',512)
        operate('compete',g)

if __name__=='__main__':
    a=argparse.ArgumentParser();a.add_argument('gen',type=int);a.add_argument('--attach',type=int,default=0)
    args=a.parse_args();run(args.gen,args.attach)
