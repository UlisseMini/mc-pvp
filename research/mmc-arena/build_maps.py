"""Pin native and model geometry overlays without modifying prior tournament runtimes."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile
from platforms import OUT,HERE,record
from pack import pack

ROOT=HERE.parents[1]
BUILD=ROOT/'build/mmc-arena'
LEAGUE=ROOT/'research/elite-league/single-sumo'
LBUILD=ROOT/'build/elite-league/single-sumo'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def sources(names):return [ROOT/'src/lab'/(n+'.java') for n in names.split()]
def compile_java(cp,dest,files):
    subprocess.run(['javac','--release','17','-J-XX:ActiveProcessorCount=1','-proc:none','-sourcepath','','-cp',cp,'-d',dest,*files],check=True)

def server(base):
    pack();agent=ROOT/'build/agents'/(base+'.jar')
    assert sha(agent)==base
    packed=sorted((OUT/'packed').glob('*.bin.gz'))
    assert len(packed)==len(json.loads((OUT/'catalog.json').read_text()))
    with tempfile.TemporaryDirectory(dir=BUILD) as temp:
        t=Path(temp);classes=t/'classes';classes.mkdir()
        java=sources('RecordedSumoMap RecordedSumoArena SumoArena DuelSettings BotMatchSettings Referee EntryDrills ServerReplay LabHelp')
        compile_java(agent,classes,java)
        new={str(p.relative_to(classes)):p for p in classes.rglob('*.class')}
        new.update({'lab/mmc-maps/'+p.name:p for p in packed})
        jar=t/'agent.jar'
        with zipfile.ZipFile(agent) as old,zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as out:
            for item in old.infolist():
                if item.filename not in new:out.writestr(item,old.read(item))
            for name,path in sorted(new.items()):out.writestr(zipfile.ZipInfo(name,(2026,1,1,0,0,0)),path.read_bytes(),zipfile.ZIP_DEFLATED)
        stamp=sha(jar);record(ROOT/'build/agents'/(stamp+'.jar'),jar.read_bytes())
        receipt=dict(server=stamp,base=base,changed={name:sha(p) for name,p in new.items()})
        record(BUILD/('server-'+stamp+'.json'),json.dumps(receipt,sort_keys=True,indent=2).encode())
        return stamp

def model():
    base=LBUILD/json.loads((LBUILD/'training-runtime.json').read_text())['directory']
    java=[*sources('RecordedSumoMap RecordedCollisions ExactTravel'),ROOT/'tests/PeriodicComboFightModel.java',ROOT/'research/sumo-rl/DuelEnv.java',
          HERE/'RecordedMapClock.java',LEAGUE/'LeagueWorker.java']
    with tempfile.TemporaryDirectory(dir=BUILD) as temp:
        t=Path(temp);p=t/'classes';shutil.copytree(base,p);compile_java(base,p,java)
        resources=p/'lab/mmc-maps';resources.mkdir(exist_ok=True)
        for native in sorted((OUT/'packed').glob('*.bin.gz')):
            shutil.copy2(native,resources/native.name)
            shape=native.name.split('.')[0]; proof=OUT/'collision'/(shape+'.boxes.gz')
            import gzip
            (resources/(shape+'.boxes')).write_bytes(gzip.decompress(proof.read_bytes()))
        manifest={str(f.relative_to(p)):sha(f) for f in sorted(p.rglob('*')) if f.is_file()}
        stamp=hashlib.sha256(json.dumps(manifest,sort_keys=True).encode()).hexdigest()
        out=BUILD/('model-'+stamp[:20])
        if not out.exists():p.rename(out)
        record(out/'map-runtime.json',json.dumps(dict(parent=base.name,files=manifest),sort_keys=True).encode())
        return out


def register_model(runtime):
    # Preserve the first runtime used for this campaign even when rebuilding for another replay adapter.
    receipt=json.dumps(dict(directory=str(runtime)),sort_keys=True).encode()
    baseline=BUILD/'model-runtime.json'
    if not baseline.exists():record(baseline,receipt)
    with tempfile.NamedTemporaryFile(dir=BUILD,delete=False) as f:f.write(receipt)
    os.replace(f.name,BUILD/'model-latest.json')
