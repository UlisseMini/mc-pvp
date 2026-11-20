"""Generate the simulator's colliders using the locked Mojang server jar, with no fights or policies."""
import gzip
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from build_maps import register_model,ROOT,BUILD,HERE,OUT,compile_java,model,sha
from platforms import record

def generate():
    jar=ROOT/'.cache/server.jar'
    pin=json.loads((ROOT/'dependencies.lock.json').read_text())['.cache/server.jar']['sha256']
    if sha(jar)!=pin:raise ValueError('Minecraft jar differs from dependency lock')
    with tempfile.TemporaryDirectory(dir=BUILD) as tmp:
        t=Path(tmp);classes=t/'classes';classes.mkdir()
        compile_java(str(jar),classes,[ROOT/'src/lab/RecordedSumoMap.java',HERE/'BlockGeometry.java'])
        shutil.copytree(OUT/'packed',classes/'lab/mmc-maps')
        names=[r['name'] for r in json.loads((OUT/'catalog.json').read_text())]
        subprocess.run(['java','-XX:ActiveProcessorCount=1','-XX:+UseSerialGC','-Xmx256m','-cp',os.pathsep.join(map(str,[classes,jar])),
                        'BlockGeometry',t,*names],cwd=t,check=True)
        hashes={}
        for name in names:
            file=t/(name+'.boxes');hashes[name]=sha(file)
            record(OUT/'collision'/(name+'.boxes.gz'),gzip.compress(file.read_bytes(),mtime=0))
        record(OUT/'collision/proof.json',json.dumps(dict(stock_server_sha256=pin,collision_sha256=hashes),sort_keys=True,indent=2).encode())
    runtime=model()
    register_model(runtime)
    print('Model ready',runtime,flush=True)
if __name__=='__main__':generate()
