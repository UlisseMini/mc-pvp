"""Keep finished native replays at the original paths using verified transparent APFS compression."""
import hashlib
import os
import subprocess
import tempfile
import time
from study import ROOT,D,BUILD,read
def digest(p):
    with p.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
def pack(p):
    before=p.stat()
    if before.st_size<1<<18 or before.st_blocks*512<before.st_size*.75:return
    with tempfile.TemporaryDirectory(dir=p.parent,prefix='.cf20-pack-') as tmp:
        dest=p.__class__(tmp)/p.name
        subprocess.run(['/usr/bin/ditto','--hfsCompression',p,dest],check=True)
        copied=dest.stat();h=digest(p)
        if before.st_size!=copied.st_size or digest(dest)!=h:raise ValueError('Replay changed while compressing '+p.name)
        after=p.stat()
        if (before.st_ino,before.st_mtime_ns,before.st_size)!=(after.st_ino,after.st_mtime_ns,after.st_size):
            raise ValueError('Replay still active '+p.name)
        if copied.st_blocks>=before.st_blocks: return
        os.replace(dest,p)
        with (BUILD/'compression.tsv').open('a') as f:f.write(f'{p.stem}\t{before.st_size}\t{before.st_blocks*512}\t{copied.st_blocks*512}\t{h}\n')
def main():
    os.nice(12);done=set()
    while True:
        for file in sorted((D/'native').glob('*.json')):
            for mid in read(file)['matches'].values():
                if mid in done:continue
                p=ROOT/'runtime/replays'/(mid+'.jsonl')
                if time.time()-p.stat().st_mtime<30:continue
                pack(p);done.add(mid)
        if (D/'assessment.json').exists():break
        time.sleep(10)
if __name__=='__main__':main()
