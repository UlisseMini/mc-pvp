"""Interleave the ID and memoryless comparator updates under the lab's aggregate CPU governor."""
import fcntl
import sys
import time
from dedicated import *
def launch(script,*args):
    subprocess.run([sys.executable,HERE.parent/'cpu_limit.py','--policy','nice','-n','8',sys.executable,HERE/script,*map(str,args)],check=True)
def progress(folder,key):
    p=folder/'progress.json'
    return json.loads(p.read_text())[key] if p.exists() else 0

def main():
    DIR.mkdir(exist_ok=True,parents=True)
    with (BUILD/'id-runner.lock').open('a') as guard:
        fcntl.flock(guard,fcntl.LOCK_EX|fcntl.LOCK_NB)
        plan();p=protocol()
        while not (DIR/'shared-mlp/trainer.pt').exists():time.sleep(5)
        roles=['informed','blind','wide','routed']
        # Keep arms close in exposure. Promote no diagnostic branch into the legal league.
        for end in range(16,257,16):
            for role in roles:
                if progress(DIR/role,'update')<end:launch('opponent_id.py','train',role,end)
            folder=DIR/('dedicated-'+INCUMBENT)
            while progress(folder,'duels')<end*64:
                remaining=end*64-progress(folder,'duels')
                launch('dedicated.py',INCUMBENT,min(16,(remaining+63)//64))
            print(f'Four mixed arms reached {end*64} duels each; specialist reached {progress(folder,"duels")}',flush=True)
            if end in (64,256):launch('id_evaluate.py',end)
if __name__=='__main__':main()
