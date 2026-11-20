"""Build immutable simulator snapshots and a Java 8 overlay on the registered live Forge base."""
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
BUILD=ROOT/'build/elite-league/single-sumo'
sys.path.insert(0,str(HERE.parent))
from stage_runtime import patched,sha

def sim_paths(training=False):
    pin=BUILD/'training-runtime.json' if training and (BUILD/'training-runtime.json').exists() else BUILD/'sim-current.json'
    if pin.exists():
        return [BUILD/json.loads(pin.read_text())['directory']]
    return [BUILD/'classes',ROOT/'build/elite-league/sumo-confidence/classes',ROOT/'build/elite-league/sumo-inputs/classes']

def manifest(files):
    return hashlib.sha256(json.dumps(dict(sorted(files.items())),separators=(',',':')).encode()).hexdigest()

def run():
    frozen=json.loads((HERE.parent/'sumo-confidence/pins.json').read_text())
    base=ROOT/'build/elite-league/sumo-inputs-runtime/bridge.jar';assert sha(base)==frozen['bridge']
    old=[ROOT/'build/elite-league/sumo-confidence/classes',ROOT/'build/elite-league/sumo-inputs/classes']
    if not all(p.is_dir() for p in old):raise RuntimeError('Build the frozen sumo-confidence and sumo-inputs simulator dependencies first')
    BUILD.mkdir(exist_ok=True,parents=True);lab=ROOT/'src/lab';shared=[lab/'DuelNetwork.java',lab/'SumoLeaguePolicy.java']
    sources=[*shared,HERE.parent/'Arena.java',HERE.parent/'NetworkChecks.java',*(p for p in sorted(HERE.glob('*.java')) if p.name!='LeagueTrace.java')]
    for name in re.findall(r'^import lab\.(\w+);$',(HERE.parent/'Arena.java').read_text(),re.MULTILINE):
        dependency=lab/(name+'.java')
        if dependency not in sources:sources.append(dependency)
    inherited={}
    for folder in old:
        for file in folder.rglob('*.class'):inherited.setdefault(str(file.relative_to(folder)),file.read_bytes())
    pins={str(p.relative_to(ROOT)):sha(p) for p in sources}
    pins['inherited_classes']=manifest({k:hashlib.sha256(v).hexdigest() for k,v in inherited.items()})
    pins['compiler']=subprocess.check_output(['javac','-version'],text=True).strip()
    version=BUILD/('sim-'+manifest(pins)[:20])
    if not version.exists():
        with tempfile.TemporaryDirectory(dir=BUILD) as temp:
            classes=Path(temp)/'classes';classes.mkdir()
            for name,data in inherited.items():
                file=classes/name;file.parent.mkdir(exist_ok=True,parents=True);file.write_bytes(data)
            subprocess.run(['javac','-J-XX:ActiveProcessorCount=1','-proc:none','-cp',classes,'-d',classes,*sources],check=True)
            (classes/'source-pins.json').write_text(json.dumps(pins,sort_keys=True,indent=2)+'\n')
            try:classes.rename(version)
            except OSError:
                if not version.is_dir():raise
    with tempfile.NamedTemporaryFile(dir=BUILD,mode='w',delete=False) as f:
        json.dump(dict(directory=version.name),f)
    os.replace(f.name,BUILD/'sim-current.json')
    bridge=BUILD/'bridge.jar'
    with tempfile.TemporaryDirectory(dir=BUILD) as temp:
        classes=Path(temp)/'forge';classes.mkdir()
        subprocess.run(['javac','-J-XX:ActiveProcessorCount=1','--release','8','-proc:none','-sourcepath','','-cp',base,'-d',classes,*shared,lab/'DuelHudPing.java'],check=True)
        if not bridge.exists():patched(base,classes,bridge)
        def matches(path):
            with zipfile.ZipFile(path) as jar:
                return all(str(p.relative_to(classes)) in jar.namelist() and p.read_bytes()==jar.read(str(p.relative_to(classes))) for p in classes.rglob('*.class'))
        if not matches(bridge):
            digest=manifest({str(p.relative_to(classes)):sha(p) for p in classes.rglob('*.class')})
            bridge=BUILD/('forge-'+digest[:20]+'.jar')
            if not bridge.exists():patched(base,classes,bridge)
            if not matches(bridge):raise RuntimeError('Frozen Forge artifact does not match its class digest')
        with tempfile.NamedTemporaryFile(dir=BUILD,mode='w',delete=False) as f:
            json.dump(dict(jar=bridge.name,sha256=sha(bridge)),f)
        os.replace(f.name,BUILD/'forge-current.json')
    print('Simulator',version.name,'Forge base',sha(base),'overlay',sha(bridge),flush=True)
if __name__=='__main__':run()
