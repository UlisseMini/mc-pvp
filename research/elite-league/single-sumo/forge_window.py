"""Short, drained client-version windows on the main server; preserve previously pinned native cohorts."""
import contextlib
import fcntl
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
BUILD=ROOT/'build/elite-league/single-sumo'
sys.path.insert(0,str(HERE.parent))
import deploy_runtime
import lab_runtime
from stage_runtime import sha
from native_trials import ready,active_bridge

def read(p):return json.loads(p.read_text())
def reliable(fn):
    try:return fn()
    except (FileNotFoundError,ValueError,KeyError):return False
def wait(fn,limit,label):
    deadline=time.monotonic()+limit
    while time.monotonic()<deadline:
        if reliable(fn):return
        time.sleep(.4)
    raise RuntimeError(label)
def process_cmd(pid):
    return subprocess.run(['ps','-p',str(pid),'-o','command='],text=True,capture_output=True).stdout.strip()
def server_hash():
    manager=read(ROOT/'runtime/manager.json')
    if manager.get('server_agent_sha256'):return manager['server_agent_sha256']
    template=ROOT/'build/lab-agent.jar'
    children=subprocess.run(['pgrep','-P',str(manager['pid'])],capture_output=True,text=True).stdout.split()
    if template.stat().st_mtime<=manager['started'] and any(
            '-Dlab.role=server' in (cmd:=process_cmd(pid)) and '-javaagent:'+str(template) in cmd for pid in children):
        return sha(template)
    raise RuntimeError('Cannot verify the agent loaded by this main-server process')
def schedulers():
    listings=subprocess.check_output(['ps','-axo','pid=,command='],text=True);out={}
    for line in listings.splitlines():
        pid,cmd=line.strip().split(None,1)
        if int(pid)==os.getpid() or not ('/Python ' in cmd or '/python3' in cmd):continue
        if cmd.endswith(' research/elite-league/sumo-confidence/study.py') or cmd.endswith(' research/elite-league/single-sumo/native_league.py baseline'):
            out[int(pid)]=cmd
    return out
def snapshot(dest,bridge,settings_source=None):
    import shutil
    dest.mkdir(exist_ok=True,parents=True)
    expected={'bridge.jar':bridge,'module.jar':settings_source/'module.jar' if settings_source else ROOT/'runtime/bots/0/config/pvp-practice/practice-module.jar'}
    for i in range(8):
        name=f'settings-{i}.properties'
        expected[name]=settings_source/name if settings_source else ROOT/f'runtime/bots/{i}/config/pvp-practice/settings.properties'
    for n,path in expected.items():
        target=dest/n
        if target.exists():
            if sha(target)!=sha(path):raise RuntimeError('Frozen client snapshot changed '+n)
        else:shutil.copyfile(path,target)

def manager_accepts(command,selector,target,digest):
    try:settings=json.loads(command.split(' serve ',1)[1])
    except (ValueError,IndexError):return False
    return (settings.get('cohort_selector')==str(selector) and
            settings.get('cohort_options',{}).get(str(target.resolve()))==digest)

def switch(target,settings,server=None):
    mgr=ROOT/'runtime/manager.json';hashcode=sha(target/'bridge.jar')
    if server:
        agent=ROOT/'build/agents'/(server+'.jar')
        if sha(agent)!=server:raise RuntimeError('Archived server artifact changed')
        settings=dict(settings,server_agent=str(agent))
    def correct_server():return not server or server_hash()==server
    with (BUILD.parent/'rollout.lock').open('a') as slot,(BUILD.parent/'policy-training.lock').open('a') as private_training:
        fcntl.flock(slot,fcntl.LOCK_EX)
        fcntl.flock(private_training,fcntl.LOCK_EX)
        if reliable(lambda:ready(hashcode) and correct_server()):return
        # Require a completely drained lab including user and boxing duels.
        if mgr.exists():
            wait(lambda:not read(ROOT/'runtime/status.json')['matches'],190,'Waiting for an active duel to finish')
        selector=target.parent/'client-cohort.json'
        options={str(p.parent.resolve()):sha(p) for p in target.parent.glob('*/bridge.jar')}
        record={'snapshot':str(target.resolve()),'epoch':time.time()*1000}
        temp=selector.with_suffix('.tmp');temp.write_text(json.dumps(record));temp.replace(selector)
        if mgr.exists() and correct_server() and manager_accepts(process_cmd(read(mgr)['pid']),selector,target,hashcode):
            lab_runtime.send('reload-bots')
            wait(lambda:ready(hashcode) and active_bridge()==hashcode,300,'Forge clients did not enter the chosen cohort')
            return
        if mgr.exists():
            lab_runtime.send('shutdown')
            wait(lambda:not mgr.exists(),65,'Main lab did not stop')
        deploy_runtime.launch_locked(target,dict(settings,cohort_snapshot=str(target),expected_bridge=hashcode,
                  cohort_selector=str(selector),cohort_options=options))
        if not correct_server():raise RuntimeError('Native cohort loaded an unexpected server agent')
def resume_paused(paused):
    for pid,cmd in paused.items():
        if process_cmd(int(pid))==cmd:os.kill(int(pid),signal.SIGCONT)
@contextlib.contextmanager
def window(bridge_path=None,server=None,admit=None):
    b=BUILD/'forge-window';b.mkdir(exist_ok=True)
    bridge=bridge_path or BUILD/'bridge.jar';newhash=sha(bridge)
    target=b/('league' if bridge==BUILD/'bridge.jar' else 'league-'+newhash[:20])
    with (BUILD.parent/'main-cohort.lock').open('a') as access:
        fcntl.flock(access,fcntl.LOCK_EX)
        journal=b/'lease.json'
        pinned=read(HERE.parent/'sumo-confidence/pins.json')
        if journal.exists():
            prior=read(journal)
            switch(Path(prior.get('restore',b/'old')),prior['settings'],prior.get('old_server',pinned['server']))
            resume_paused(prior['schedulers']);journal.unlink()
        wait(active_bridge,25,'Cannot identify the current live client cohort')
        oldhash=active_bridge();oldserver=server_hash();settings=read(ROOT/'runtime/manager.json')
        if admit is not None and not admit():
            yield None
            return
        if server is None:server=oldserver
        if bridge_path is None:
            # A newer mod release may already contain the complete controller implementation.
            import zipfile
            live=ROOT/'runtime/bots/0/mods/practice-bridge.jar'
            with zipfile.ZipFile(live) as actual,zipfile.ZipFile(bridge) as required:
                classes=('lab/SumoLeaguePolicy.class','lab/DuelNetwork.class','lab/DuelHudPing.class')
                if all(c in actual.namelist() and actual.read(c)==required.read(c) for c in classes):newhash=oldhash
        if oldhash==newhash and oldserver==server:
            yield newhash;return
        live=ROOT/'runtime/bots/0/mods/practice-bridge.jar'
        origin=b/('old' if oldhash==pinned['bridge'] else 'live-'+oldhash[:16]+'-'+str(settings['started']).replace('.','_'))
        snapshot(origin,live)
        if newhash==pinned['bridge']:target=b/'old'
        elif newhash==oldhash:target=origin
        else:snapshot(target,bridge,settings_source=b/'old')
        public_agent=ROOT/'build/lab-agent.jar'
        public_was_origin=oldserver!=server and sha(public_agent)==oldserver
        paused=schedulers()
        journal.write_text(json.dumps(dict(settings=settings,schedulers=paused,at=time.time(),old=oldhash,new=newhash,
            restore=str(origin),old_server=oldserver,requested_server=server)))
        try:
            for pid,cmd in paused.items():
                if process_cmd(pid)==cmd:os.kill(pid,signal.SIGSTOP)
            wait(lambda:not read(ROOT/'runtime/status.json')['matches'] and
                 not any((ROOT/'runtime/manager/inbox').glob('*.json')),195,'Waiting for earlier admissions to drain')
            switch(target,settings,server)
            yield newhash
        finally:
            # Preserve an unrelated release that independently took over the main process.
            active=reliable(active_bridge)
            replaced=active and (active not in (oldhash,newhash) or server_hash() not in (oldserver,server))
            restore_server=server if public_was_origin and sha(public_agent)==server else oldserver
            if not replaced:switch(origin,settings,restore_server)
            resume_paused(paused);journal.unlink(missing_ok=True)


def cohort_window(folder,bridge_path=None):
    """Resume an admitted panel on its frozen binary. Fresh panels use the current main release."""
    stamp=folder/'native-build.json'
    return window(bridge_path,server=read(stamp)['server'] if stamp.exists() else None)
