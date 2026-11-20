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
def schedulers():
    listings=subprocess.check_output(['ps','-axo','pid=,command='],text=True);out={}
    for line in listings.splitlines():
        pid,cmd=line.strip().split(None,1)
        if not ('/Python ' in cmd or '/python3' in cmd):continue
        if cmd.endswith(' research/elite-league/sumo-confidence/study.py') or cmd.endswith(' research/elite-league/single-sumo/native_league.py baseline'):
            out[int(pid)]=cmd
    return out
def snapshot(dest,bridge):
    import shutil
    dest.mkdir(exist_ok=True,parents=True)
    expected={'bridge.jar':bridge,'module.jar':ROOT/'runtime/bots/0/config/pvp-practice/practice-module.jar'}
    for i in range(8):expected[f'settings-{i}.properties']=ROOT/f'runtime/bots/{i}/config/pvp-practice/settings.properties'
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

def switch(target,settings):
    mgr=ROOT/'runtime/manager.json';hashcode=sha(target/'bridge.jar')
    with (BUILD.parent/'rollout.lock').open('a') as slot:
        fcntl.flock(slot,fcntl.LOCK_EX)
        if reliable(lambda:ready(hashcode)):return
        # Require a completely drained lab including user and boxing duels.
        wait(lambda:not read(ROOT/'runtime/status.json')['matches'],190,'Waiting for an active duel to finish')
        selector=target.parent/'client-cohort.json'
        options={str(p.parent.resolve()):sha(p) for p in target.parent.glob('*/bridge.jar')}
        record={'snapshot':str(target.resolve()),'epoch':time.time()*1000}
        temp=selector.with_suffix('.tmp');temp.write_text(json.dumps(record));temp.replace(selector)
        if mgr.exists() and manager_accepts(process_cmd(read(mgr)['pid']),selector,target,hashcode):
            lab_runtime.send('reload-bots')
            wait(lambda:ready(hashcode) and active_bridge()==hashcode,300,'Forge clients did not enter the chosen cohort')
            return
        if mgr.exists():
            lab_runtime.send('shutdown')
            wait(lambda:not mgr.exists(),65,'Main lab did not stop')
        deploy_runtime.launch_locked(target,dict(settings,cohort_snapshot=str(target),expected_bridge=hashcode,
                  cohort_selector=str(selector),cohort_options=options))
def resume_paused(paused):
    for pid,cmd in paused.items():
        if process_cmd(int(pid))==cmd:os.kill(int(pid),signal.SIGCONT)
@contextlib.contextmanager
def window(bridge_path=None):
    b=BUILD/'forge-window';b.mkdir(exist_ok=True)
    bridge=bridge_path or BUILD/'bridge.jar';newhash=sha(bridge)
    target=b/('league' if bridge==BUILD/'bridge.jar' else 'league-'+newhash[:20])
    with (BUILD.parent/'main-cohort.lock').open('a') as access:
        fcntl.flock(access,fcntl.LOCK_EX)
        journal=b/'lease.json';settings=read(ROOT/'runtime/manager.json')
        pinned=read(HERE.parent/'sumo-confidence/pins.json')
        if sha(ROOT/'build/lab-agent.jar')!=pinned['server']:raise RuntimeError('Main server build differs before cohort switch')
        if journal.exists():
            prior=read(journal);switch(b/'old',prior['settings']);resume_paused(prior['schedulers']);journal.unlink()
        wait(active_bridge,25,'Cannot identify the current live client cohort')
        oldhash=active_bridge()
        if oldhash==newhash:
            yield newhash;return
        if oldhash!=pinned['bridge']:raise RuntimeError('Another experiment owns a different live Forge cohort')
        snapshot(b/'old',ROOT/'runtime/bots/0/mods/practice-bridge.jar')
        snapshot(target,bridge)
        paused=schedulers()
        journal.write_text(json.dumps(dict(settings=settings,schedulers=paused,at=time.time(),old=oldhash,new=newhash)))
        try:
            for pid,cmd in paused.items():
                if process_cmd(pid)==cmd:os.kill(pid,signal.SIGSTOP)
            wait(lambda:not read(ROOT/'runtime/status.json')['matches'] and
                 not any((ROOT/'runtime/manager/inbox').glob('*.json')),195,'Waiting for earlier admissions to drain')
            switch(target,settings)
            yield newhash
        finally:
            switch(b/'old',settings)
            resume_paused(paused);journal.unlink(missing_ok=True)
