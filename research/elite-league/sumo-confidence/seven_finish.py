import time
import subprocess
import sys
from study import D
if __name__=='__main__':
    while not all((D/'verified'/f'{s}{i:02}.json').exists() for s in 'kl' for i in range(1,17)) or not (D/'seven/model/transfer-20.csv').exists():
        time.sleep(8)
    subprocess.run([sys.executable,D/'seven_report.py'],check=True)
