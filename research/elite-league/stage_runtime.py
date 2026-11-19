"""Build the tested lab changes over the exact running artifacts. Staging never restarts the server."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT=Path(__file__).resolve().parents[2]
SERVER=['SumoArena','Referee','MmcKnockback','ServerReplay','DuelSettings','BotMatchSettings','LabHelp','BoxingLandmark']
CLIENT=['PairedExperts','MouseLook','Client','DuelNetwork','HumanTiming','ExpertRouter','CombatTactics','TradeResponse','DuelSettings','BoxingLandmark']

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def patched(base,classes,dest):
    members={str(p.relative_to(classes)):p for p in classes.rglob('*.class')}
    with zipfile.ZipFile(base) as old,zipfile.ZipFile(dest,'x',zipfile.ZIP_DEFLATED) as jar:
        for info in old.infolist():
            jar.writestr(info,members.pop(info.filename).read_bytes() if info.filename in members else old.read(info))
        for name,p in sorted(members.items()):jar.write(p,name)
    with zipfile.ZipFile(dest) as jar:
        if jar.testzip() is not None:raise ValueError('Corrupt staged archive')

def source_hashes():
    return {n:sha(ROOT/'src/lab'/(n+'.java')) for n in sorted(set(SERVER+CLIENT))}

def stage(out):
    out.mkdir(parents=True,exist_ok=False);result={}
    for name,base,files,target in [
        ('agent',ROOT/'build/lab-agent.jar',SERVER,17),('bridge',ROOT/'build/practice-client/practice-bridge.jar',CLIENT,8)]:
        classes=out/(name+'-classes');classes.mkdir()
        # Compile only the requested classes. Keep other deployed mods and jars intact.
        subprocess.run(['javac','-J-XX:ActiveProcessorCount=2','--release',str(target),'-proc:none','-cp',str(base),
            '-sourcepath','','-d',str(classes),*(str(ROOT/'src/lab'/(f+'.java')) for f in files)],check=True)
        dest=out/(name+'.jar');patched(base,classes,dest)
        backup=out/(name+'-previous.jar');shutil.copyfile(base,backup)
        result[name]=dict(base=str(base),original=sha(backup),new=sha(dest))
    (out/'sources.json').write_text(json.dumps(source_hashes(),indent=2)+'\n')
    (out/'hashes.json').write_text(json.dumps(result,indent=2)+'\n')

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('out',type=Path)
    stage(p.parse_args().out)
