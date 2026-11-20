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
            last=json.loads(progress.read_text()) if progress.exists() else {}
            step=last.get('update',0)
            if step>=total:return
            # Aim for a 12-second turn on the shared CPU. Short bouts can fit more updates per JVM startup.
            chunk=max(4,min(64,int(12/max(.1,last.get('seconds',.65)))))
            subprocess.run([sys.executable,here.parent/'cpu_limit.py','nice','-n','8',sys.executable,
                            here/'train.py',path,str(min(total,step+chunk))],check=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('path',type=Path);p.add_argument('total',type=int)
    a=p.parse_args();train(a.path.resolve(),a.total)
