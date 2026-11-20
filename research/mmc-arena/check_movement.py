"""Independent native-entity movement comparisons for every copied build."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
from build_maps import BUILD,HERE,OUT,ROOT,compile_java,sha
from platforms import record
def run():
    jar=ROOT/'.cache/server.jar'
    runtime=Path(json.loads((BUILD/'model-runtime.json').read_text())['directory'])
    with tempfile.TemporaryDirectory(dir=BUILD) as tmp:
        d=Path(tmp);cp=os.pathsep.join(map(str,[runtime,jar]));dest=d/'classes';dest.mkdir()
        compile_java(cp,dest,[HERE/'MapMovementCheck.java',HERE/'BlockGeometry.java',HERE/'RecordedMapClock.java'])
        names=[r['name'] for r in json.loads((OUT/'catalog.json').read_text())]
        raw=subprocess.check_output(['java','-XX:ActiveProcessorCount=1','-XX:+UseSerialGC','-Xmx400m','-cp',str(dest)+os.pathsep+cp,'MapMovementCheck',*names],cwd=d,text=True)
        result={}
        for line in raw.splitlines():
            if 'GEOMETRY-CHECK ' in line:
                n,steps,side,land=line.split('GEOMETRY-CHECK ')[1].split()
                result[n]=dict(steps=int(steps),horizontal_clips=int(side),landings=int(land))
        assert set(names)==set(result)
        record(OUT/'collision/move-parity.json',json.dumps(dict(stock_server=sha(jar),seed=751239,cases=result,
            scope='exact AABB endpoints and ground/horizontal/vertical collision flags after native Entity.moveEntity on generated offsets at seven approach/landing heights and both ground flags'),sort_keys=True,indent=2).encode())
        print('Exact native movement steps',sum(r['steps'] for r in result.values()),flush=True)
if __name__=='__main__':run()
