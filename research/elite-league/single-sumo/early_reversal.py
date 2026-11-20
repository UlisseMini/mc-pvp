"""Freeze the completed main before the matched MLP is finished and run development-only reversal checks."""
import sys
import time
from league_tournament import *
g=int(sys.argv[1]);p=EVIDENCE/f'g{g:03d}'
while not (p/'main/progress.json').exists() or json.loads((p/'main/progress.json').read_text())['update']<512:time.sleep(10)
pin_model(p/'main')
subprocess.run([sys.executable,HERE.parent/'cpu_limit.py','nice','-n','8',sys.executable,HERE/'stage_task.py','reversal',str(g)],check=True)
