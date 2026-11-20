"""Run the registered learning curves after immutable captures are complete."""
import sys
import time

from specialist_diagnosis import DEST
from specialist_router import run


if __name__=='__main__':
    while not all((DEST/f'collection/{split}-{i}-of-{n}.json').exists() for split,n in [('train',2),('dev',3)] for i in range(n)):
        time.sleep(5)
    run(int(sys.argv[1]),int(sys.argv[2]))
