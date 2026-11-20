"""Train in checkpointed tranches, yielding the shared CPU slot between them."""
import argparse
import fcntl
import subprocess
import sys
from pathlib import Path

def train(path,total):
    import json
    here=Path(__file__).resolve().parent
    with (path/'fit.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        while True:
            progress=path/'progress.json'
            step=json.loads(progress.read_text())['update'] if progress.exists() else 0
            if step>=total:return
            subprocess.run([sys.executable,here.parent/'cpu_limit.py','nice','-n','8',sys.executable,
                            here/'train.py',path,str(min(total,step+8))],check=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('path',type=Path);p.add_argument('total',type=int)
    a=p.parse_args();train(a.path.resolve(),a.total)
