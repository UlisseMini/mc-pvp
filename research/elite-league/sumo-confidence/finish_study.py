"""Render the assessed reports after the last originally scheduled capture and simulation."""
import subprocess
import sys
import time
from study import D,read
def complete():
    v=D/'verified'
    required=[D/'specialist/ready.json',D/'opening/ready.json',D/'branches/ready.json',D/'model/transfer.csv',D/'model/mechanisms.jsonl']
    if not all(p.exists() for p in required):return False
    counts={'g':32,'s':24,'e':12}
    for folder,n,tag in [('opening',16,'o'),('branches',16,'c')]:
        if read(D/folder/'ready.json')['run']:
            if not all((D/folder/'model'/f).exists() for f in ('transfer.csv','mechanisms.jsonl')):return False
            counts[tag]=n
    return all(all((v/(f'{tag}{i:02}.json')).exists() for i in range(1,n+1)) for tag,n in counts.items())
if __name__=='__main__':
    while not complete():time.sleep(10)
    subprocess.run([sys.executable,D/'confidence_analysis.py'],check=True)
    subprocess.run([sys.executable,D/'confidence_report.py'],check=True)
    print('Analysis and report complete',flush=True)
