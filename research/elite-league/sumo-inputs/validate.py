"""Score the preregistered frozen policies on six prospective timing calendars."""
import csv
import gzip
import shutil
import subprocess
from live import B,D,read,wait,freeze
from train import java
def main():
    wait(lambda:(D/'entrants.json').exists())
    # Keep a portable copy of the actual clocks that were opened only for evaluation.
    for src in sorted((B/'clocks').glob('m*.txt.gz')):shutil.copyfile(src,D/src.name)
    import base64,struct
    from train import SEEDS
    from live import champions
    directory=D/'masks';directory.mkdir(exist_ok=True)
    med=read(D/'entrants.json')['representatives']['sumo-input-full32']
    for seed in SEEDS:
        n='full32-'+str(seed);src=(D/'fits'/(n+'.net')).read_text().strip()
        tag,encoded=src.split('.');raw=base64.b64decode(encoded)
        assert tag=='INPUT32' and struct.unpack('>f',raw[-4:])[0]==112
        for name,bit in [('motion',16),('edge',32),('timing',64)]:
            id=f'fixed-no{name}-{seed}';p=directory/(id+'.net')
            content=tag+'.'+base64.b64encode(raw[:-4]+struct.pack('>f',112-bit)).decode()+'\n'
            if p.exists() and p.read_text()!=content:raise ValueError(id)
            p.write_text(content)
            if n==med:champions.install('sumo-input-fixed-no'+name,p,'Matched feature intervention on '+n,id,'direct')
    if not (D/'transfer.csv').exists():subprocess.run(java('InputStudy','test'),check=True)
if __name__=='__main__':main()
