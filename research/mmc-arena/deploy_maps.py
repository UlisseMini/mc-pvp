"""Short geometry-only window on the main lab. No policy outcomes for sealed maps."""
import gzip
import json
import os
import sys
import time
from build_maps import ROOT,BUILD,LEAGUE,server,sha,model
from platforms import OUT,record
sys.path.insert(0,str(LEAGUE))
from forge_window import window,server_hash,lab_runtime

def run():
    release=BUILD/'registered-server.json'
    if not release.exists():
        base=server_hash();new=server(base)
        record(release,json.dumps(dict(base=base,server=new),sort_keys=True).encode())
    ids=json.loads(release.read_text())
    with window(server=ids['server']) as bridge:
        for entry in json.loads((OUT/'catalog.json').read_text()):
            name=entry['name'];request='lab mapgeometry '+name
            file=ROOT/'runtime/map-geometry'/(name+'.boxes')
            # Repeat at each verification to catch build changes or stale geometry.
            before=file.stat().st_mtime_ns if file.exists() else 0
            lab_runtime.send(request)
            start=time.monotonic()
            while not file.exists() or file.stat().st_mtime_ns==before:
                if time.monotonic()-start>35:raise RuntimeError('Missing native collision export for '+name+'; inspect server.log')
                time.sleep(.25)
            record(OUT/'collision'/(name+'.boxes.gz'),gzip.compress(file.read_bytes(),mtime=0))
            print('Native geometry verified',name,flush=True)
        # Preserve all prior server binaries for their original native cohorts.
        default=ROOT/'build/lab-agent.jar'
        if sha(default)==ids['base']:
            tmp=default.with_suffix('.map-tmp');tmp.write_bytes((ROOT/'build/agents'/(ids['server']+'.jar')).read_bytes());os.replace(tmp,default)
    record(OUT/'collision/live-verification.json',json.dumps(dict(server=ids['server'],maps=[r['name'] for r in json.loads((OUT/'catalog.json').read_text())]),sort_keys=True).encode())
    runtime=model()
    record(BUILD/'model-runtime.json',json.dumps(dict(directory=str(runtime)),sort_keys=True).encode())
    print('Model ready',runtime,flush=True)
if __name__=='__main__':run()
