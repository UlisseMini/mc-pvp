"""Populate the fixed archive portion of the common tournament in short governed batches."""
import sys
import subprocess
from league_tournament import *
def batch(left):
    field=historical();names=sorted(field);w=Worker()
    try:
        for right in names[names.index(left):]:games(w,field,left,right)
    finally:w.close()
if __name__=='__main__':
    if len(sys.argv)>1:batch(sys.argv[1])
    else:
        for n in sorted(historical()):
            subprocess.run([sys.executable,HERE.parent/'cpu_limit.py','nice','-n','8',sys.executable,__file__,n],check=True)
