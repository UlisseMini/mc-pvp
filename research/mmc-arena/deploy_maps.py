"""Short geometry-only window on the main lab. No policy outcomes for sealed maps."""
import gzip
import json
import os
import sys
import time
from build_maps import register_model,ROOT,BUILD,LBUILD,LEAGUE,server,sha,model
from platforms import OUT,record
sys.path.insert(0,str(LEAGUE))
from forge_window import window,server_hash,lab_runtime

def export_command(name,command,suffix):
    file=ROOT/'runtime/map-geometry'/(name+suffix)
    before=file.stat().st_mtime_ns if file.exists() else 0
    start=time.monotonic();last_send=start-10
    while not file.exists() or file.stat().st_mtime_ns==before:
        now=time.monotonic()
        if now-start>45:raise RuntimeError(f'Missing native {suffix} for {name}; inspect server.log')
        if now-last_send>=7:
            lab_runtime.send('lab '+command+' '+name);last_send=now
        time.sleep(.25)
    return file

def run():
    release=BUILD/'registered-chunk-server.json'
    if not release.exists():
        base=sha(ROOT/'build/lab-agent.jar');new=server(base)
        record(release,json.dumps(dict(base=base,server=new),sort_keys=True).encode())
    ids=json.loads(release.read_text())
    with window(LBUILD/'bridge.jar',server=ids['server']) as bridge:
        for entry in json.loads((OUT/'catalog.json').read_text()):
            name=entry['name']
            export_command(name,'mapprepare','.ready')
            time.sleep(1.4)
            file=export_command(name,'mapgeometry','.boxes')
            record(OUT/'collision'/(name+'.boxes.gz'),gzip.compress(file.read_bytes(),mtime=0))
            print('Native geometry verified',name,flush=True)
        from validation import smoke
        smoke(bridge)
        # Preserve all prior server binaries for their original native cohorts.
        default=ROOT/'build/lab-agent.jar'
        if sha(default)==ids['base']:
            tmp=default.with_suffix('.map-tmp');tmp.write_bytes((ROOT/'build/agents'/(ids['server']+'.jar')).read_bytes());os.replace(tmp,default)
        if sha(default)!=ids['server']:raise RuntimeError('The public server changed during map verification')
        record(OUT/'collision/live-verification.json',json.dumps(dict(server=ids['server'],minimum_native_ticks_after_placement=20,
            maps=[r['name'] for r in json.loads((OUT/'catalog.json').read_text())]),sort_keys=True).encode())
    runtime=model()
    register_model(runtime)
    print('Model ready',runtime,flush=True)
if __name__=='__main__':run()
