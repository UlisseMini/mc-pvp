"""Open the sealed geometries once, after all three default-map checkpoints are selected."""
import subprocess
import sys
from pathlib import Path
from validation import register, LEAGUE

if __name__=='__main__':
    register('sealed-final')
    run=Path(__file__).with_name('validation.py')
    cmd=[sys.executable,'-u',run,'sealed-final']
    subprocess.run([sys.executable,LEAGUE.parent/'cpu_limit.py','--terrain','nice','-n','8',*cmd,'model'],check=True)
    subprocess.run([*cmd,'native'],check=True)
