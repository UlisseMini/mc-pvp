"""Switch the main lab between completed native cohorts, preserving its checked Forge launch command."""
import asyncio
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
from stage_runtime import ROOT,sha,stage,source_hashes
sys.path.insert(0,str(ROOT))
import forge_runtime
import lab_runtime
from native_trials import ready,active_bridge

def wait_file(fn,seconds,message):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        try:
            if fn():return
        except (OSError,ValueError,KeyError):pass
        time.sleep(.5)
    raise RuntimeError(message)

def gated_launch(slot,after,command):
    # Limit Forge initialization to one pair at a time. The issued Java sandbox command is untouched.
    if slot>=2:
        def previous_online():
            for i in (slot//2*2-2,slot//2*2-1):
                beat=json.loads((ROOT/f'runtime/bots/{i}/heartbeat.json').read_text())
                if beat['time']<=after:return False
            return True
        wait_file(previous_online,100,'Previous pair of Forge clients did not come online')
    os.execvp(str(command[0]),list(map(str,command)))

def serve(settings):
    start=time.time()*1000
    orig=forge_runtime.bot_command
    def stagger(slot,*args):
        return [sys.executable,__file__,'gate',str(slot),str(start),*map(str,orig(slot,*args))]
    forge_runtime.bot_command=stagger
    asyncio.run(lab_runtime.serve(frame_sleep_ms=settings['frame_sleep_ms'],slot_backends=','.join(settings['bot_backends']),
                                  bot_slots=settings['bot_slots'],port_offset=settings['port_offset']))

def set_artifacts(folder,hashes,previous):
    for n,r in hashes.items():
        dest=Path(r['base']);source=folder/(n+('-previous.jar' if previous else '.jar'))
        if sha(source)!=r['original' if previous else 'new']:raise ValueError('Staged artifact changed')
        tmp=dest.with_name(dest.name+'.league-switch')
        shutil.copyfile(source,tmp);tmp.replace(dest)

def launch(folder,settings):
    log=(folder/'serve.log').open('a')
    try:
        proc=subprocess.Popen([sys.executable,'-u',__file__,'serve',json.dumps(settings)],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
    finally:log.close()
    def healthy():
        if proc.poll() is not None:raise RuntimeError(f'Main lab exited with {proc.returncode}; see {folder}/serve.log')
        return ready() and json.loads((ROOT/'runtime/manager.json').read_text())['pid']==proc.pid
    try:wait_file(healthy,160,'Main Forge lab startup timed out')
    except BaseException:
        if proc.poll() is None:proc.terminate();proc.wait(timeout=30)
        raise

def switch(folder):
    registry=folder/'deployed.json'
    approved=folder
    hashes=json.loads((folder/'hashes.json').read_text())
    if registry.exists():
        hashes=json.loads(registry.read_text())['hashes']
    elif (not (folder/'sources.json').is_file() or source_hashes()!=json.loads((folder/'sources.json').read_text()) or
            any(sha(Path(r['base']))!=r['original'] for r in hashes.values())):
        # A separate local build can run during a many-hour campaign. Keep its unrelated changes.
        from runtime_checks import run as verify
        versions='-'.join(sha(Path(hashes[k]['base']))[:12] for k in ('agent','bridge'))
        import hashlib
        source_id=hashlib.sha256(json.dumps(source_hashes(),sort_keys=True).encode()).hexdigest()[:12]
        folder=approved/('rebased-'+versions+'-'+source_id)
        if not folder.exists():stage(folder)
        verify(folder,ROOT/'research/elite-league/ladder-frozen')
        hashes=json.loads((folder/'hashes.json').read_text())
    if registry.exists():
        if not all(sha(Path(r['base']))==r['new'] for r in hashes.values()) or not ready():raise ValueError('Recorded deployment differs from running lab')
        return
    status=json.loads((ROOT/'runtime/status.json').read_text());mgr=ROOT/'runtime/manager.json'
    settings=json.loads(mgr.read_text())
    if settings['bot_slots']!=8 or settings['bot_backends']!=['forge']*8 or settings['port_offset']!=0 or not active_bridge():raise ValueError('Unexpected main lab state')
    if any(sha(Path(r['base']))!=r['original'] for r in hashes.values()):raise ValueError('Live artifact changed since staging. Rebuild on current artifacts.')
    if any(m.get('a','').startswith('LabSlot') and m.get('b','').startswith('LabSlot') for m in status['matches']):
        raise ValueError('Another native study is still playing')
    lab_runtime.send('shutdown')
    wait_file(lambda:not mgr.exists(),45,'Main lab did not stop')
    try:
        set_artifacts(folder,hashes,False);launch(folder,settings)
    except BaseException:
        set_artifacts(folder,hashes,True);launch(folder,settings);raise
    registry.write_text(json.dumps(dict(time=time.time(),stage=str(folder),hashes=hashes,settings=settings,players=status['players']),indent=2)+'\n')

if __name__=='__main__':
    op=sys.argv[1]
    if op=='gate':gated_launch(int(sys.argv[2]),float(sys.argv[3]),sys.argv[4:])
    elif op=='serve':serve(json.loads(sys.argv[2]))
    elif op=='switch':switch(Path(sys.argv[2]).resolve())
    else:raise SystemExit('Expected switch, serve or gate')
