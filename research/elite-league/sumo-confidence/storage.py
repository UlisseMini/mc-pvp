"""Recover space from closed lab replay files while keeping their names and exact contents."""
import os
import shutil
import time
from study import D,ROOT,read
from pack_replays import pack

def main():
    os.nice(15)
    cutoff=(D/'protocol.md').stat().st_mtime-3600
    candidates=[]
    for p in (ROOT/'runtime/replays').glob('m*.jsonl'):
        s=p.stat()
        if s.st_mtime<cutoff and s.st_blocks*512>.75*s.st_size and s.st_size>1<<19:
            candidates.append((s.st_size,p))
    candidates.sort()
    while not (D/'assessment.json').exists():
        free=shutil.disk_usage(ROOT).free
        if free < 32*(1<<30):
            while candidates and shutil.disk_usage(ROOT).free<42*(1<<30):
                _,p=candidates.pop()
                meta=ROOT/'runtime/results'/(p.stem+'.json')
                if meta.is_file() and 0<read(meta).get('finish',0)<cutoff*1000:
                    pack(p)
                    time.sleep(.25)
        if not candidates:return
        time.sleep(20)
if __name__=='__main__':main()
