"""Switch the main lab between completed native cohorts, preserving its checked Forge launch command."""
import asyncio
import fcntl
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
    # Bring up one Forge JVM at a time within the shared CPU budget.
    if slot:
        def previous_online():
            beat=json.loads((ROOT/f'runtime/bots/{slot-1}/heartbeat.json').read_text())
            return beat['time']>after
        wait_file(previous_online,45+30*slot,'Previous Forge client did not come online')
    os.execvp(str(command[0]),list(map(str,command)))

def serve(settings):
    start=time.time()*1000
    orig=forge_runtime.bot_command
    snapshot=settings.get('cohort_snapshot')
    if settings.get('bridge_file'):
        forge_runtime.BRIDGE=Path(settings['bridge_file'])
    if snapshot:
        snapshot=Path(snapshot)
        forge_runtime.BRIDGE=snapshot/'bridge.jar'
        forge_runtime.MODULE=snapshot/'module.jar'
    def stagger(slot,*args):
        selected=snapshot;launch_epoch=start
        selector=settings.get('cohort_selector')
        if selector:
            choice=json.loads(Path(selector).read_text())
            selected=Path(choice['snapshot']).resolve()
            if str(selected) not in settings['cohort_options']:
                raise ValueError('Unknown Forge cohort snapshot')
            if sha(selected/'bridge.jar')!=settings['cohort_options'][str(selected)]:
                raise ValueError('Forge cohort bridge changed')
            forge_runtime.BRIDGE=selected/'bridge.jar'
            forge_runtime.MODULE=selected/'module.jar'
            launch_epoch=choice['epoch']
        command=orig(slot,*args)
        if selected:
            shutil.copyfile(selected/f'settings-{slot}.properties',ROOT/f'runtime/bots/{slot}/config/pvp-practice/settings.properties')
        return [sys.executable,__file__,'gate',str(slot),str(launch_epoch),*map(str,command)]
    forge_runtime.bot_command=stagger
    asyncio.run(lab_runtime.serve(frame_sleep_ms=settings['frame_sleep_ms'],slot_backends=','.join(settings['bot_backends']),
                                  bot_slots=settings['bot_slots'],port_offset=settings['port_offset'],client_start_timeout=285))

def set_artifacts(folder,hashes,previous):
    for n,r in hashes.items():
        dest=Path(r['base']);source=folder/(n+('-previous.jar' if previous else '.jar'))
        if sha(source)!=r['original' if previous else 'new']:raise ValueError('Staged artifact changed')
        tmp=dest.with_name(dest.name+'.league-switch')
        shutil.copyfile(source,tmp);tmp.replace(dest)

def launch(folder,settings):
    # Forge initialization is burstier than a live duel; keep training off the CPU throughout startup.
    with (ROOT/'build/elite-league/rollout.lock').open('a') as lock:
        fcntl.flock(lock.fileno(),fcntl.LOCK_EX)
        launch_locked(folder,settings)

def launch_locked(folder,settings):
    log=(folder/'serve.log').open('a')
    try:
        proc=subprocess.Popen([sys.executable,'-u',__file__,'serve',json.dumps(settings)],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
    finally:log.close()
    def healthy():
        if proc.poll() is not None:raise RuntimeError(f'Main lab exited with {proc.returncode}; see {folder}/serve.log')
        return ready(settings.get('expected_bridge')) and json.loads((ROOT/'runtime/manager.json').read_text())['pid']==proc.pid
    try:wait_file(healthy,300,'Main Forge lab startup timed out')
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
        if not ready(hashes['bridge']['new']) or sha(Path(hashes['agent']['base']))!=hashes['agent']['new']:raise ValueError('Recorded deployment differs from running lab')
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
        set_artifacts(folder,hashes,False)
        launch(folder,dict(settings,bridge_file=str(folder/'bridge.jar'),expected_bridge=hashes['bridge']['new']))
    except BaseException:
        set_artifacts(folder,hashes,True)
        launch(folder,dict(settings,bridge_file=str(folder/'bridge-previous.jar'),expected_bridge=hashes['bridge']['original']))
        raise
    registry.write_text(json.dumps(dict(time=time.time(),stage=str(folder),hashes=hashes,settings=settings,players=status['players']),indent=2)+'\n')

def resume(folder,manifest):
    if (ROOT/'runtime/manager.json').exists():raise ValueError('A lab manager is already present')
    pin=json.loads(manifest.with_suffix('.build.json').read_text())
    if sha(ROOT/'build/lab-agent.jar')!=pin['server_agent_sha256']:raise ValueError('Server differs from this cohort')
    folder.mkdir(parents=True,exist_ok=False)
    status_file=ROOT/'runtime/status.json'
    status=json.loads(status_file.read_text()) if status_file.exists() else {'matches':[]}
    (folder/'interrupted.json').write_text(json.dumps(dict(time=time.time(),active=[
        dict(id=m['id'],request=m['request']) for m in status['matches']]),indent=2)+'\n')
    mods=set()
    for i in range(8):
        slot=ROOT/f'runtime/bots/{i}'
        b=slot/'mods/practice-bridge.jar';mod=slot/'config/pvp-practice/practice-module.jar'
        info=json.loads((slot/'client-runtime.json').read_text())
        if sha(b)!=pin['bridge_sha256'] or sha(mod)!=info['module_sha256'] or sha(forge_runtime.HEADLESS)!=info['headless_sha256'] or sha(forge_runtime.JAVA8)!=info['java_sha256']:
            raise ValueError('Slot artifact no longer matches the cohort')
        mods.add(info['module_sha256'])
        if i==0:shutil.copyfile(b,folder/'bridge.jar');shutil.copyfile(mod,folder/'module.jar')
        shutil.copyfile(slot/'config/pvp-practice/settings.properties',folder/f'settings-{i}.properties')
    if len(mods)!=1:raise ValueError('Slot modules differ')
    launch(folder,dict(bot_slots=8,frame_sleep_ms=10,port_offset=0,bot_backends=['forge']*8,
                       expected_bridge=pin['bridge_sha256'],cohort_snapshot=str(folder.resolve())))

if __name__=='__main__':
    op=sys.argv[1]
    if op=='gate':gated_launch(int(sys.argv[2]),float(sys.argv[3]),sys.argv[4:])
    elif op=='serve':serve(json.loads(sys.argv[2]))
    elif op=='switch':switch(Path(sys.argv[2]).resolve())
    elif op=='resume':resume(Path(sys.argv[2]).resolve(),Path(sys.argv[3]))
    else:raise SystemExit('Expected switch, serve or gate')
