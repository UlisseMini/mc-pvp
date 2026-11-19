"""Check newly published tournament aliases in the live registry and on the actual Forge JVM."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time

from adaptation import dump,properties,sha
from mmc_league import EVIDENCE,HERE,WORK
from playable_check import run as check_server
from policy_runtime_parity import run as check_actions
from run import ROOT

DEST=EVIDENCE/'title-checks'


def changed():
    previous=json.loads((EVIDENCE/'titles-after-hidden-strafe.json').read_text())
    known={(n,v['settings']) for n,v in previous['winners'].items()}
    titles=previous['current_titles'].copy()
    for p in sorted(DEST.glob('*/verified.json')):
        done=json.loads(p.read_text())
        known.update(done['policy_hashes'].items())
    current={}
    for p in (ROOT/'policies').glob('*.properties'):
        data=p.read_bytes()
        if b'league_stage=' in data:current[p.stem]=hashlib.sha256(data).hexdigest()
    delta={n:h for n,h in current.items() if (n,h) not in known}
    for n,old in titles.items():
        path=ROOT/'policies'/(n+'.properties');digest=sha(path)
        if properties(path)['title_source']!=old and (n,digest) not in known:delta[n]=digest
    return dict(sorted(delta.items()))


def audit():
    delta=changed()
    if not delta:return
    key=hashlib.sha256(json.dumps(delta,sort_keys=True).encode()).hexdigest()[:18]
    store=DEST/key;store.mkdir(parents=True,exist_ok=True)
    server=store/'server.json'
    if not server.exists():check_server(server)
    with tempfile.TemporaryDirectory(dir=WORK) as t:
        files=[]
        for n,h in delta.items():
            path=ROOT/'policies'/(n+'.properties')
            if sha(path)!=h:raise ValueError('Title changed during audit '+n)
            network=properties(path).get('duel_network')
            if network:
                f=Path(t)/(n+'.net');f.write_text(network+'\n');files.append(f)
        if files and not (store/'actions.json').is_file():check_actions(store/'actions.json',files)
    dump(store/'verified.json',dict(policy_hashes=delta,server_check=sha(server),
         decision_check=sha(store/'actions.json') if files else None))
    print('Verified',', '.join(delta),flush=True)


def available(delta):
    # Server startup and the periodic live policy refresh can outlast several scan attempts.
    from native_trials import active_bridge,ready
    try:
        bridge=active_bridge()
        status=json.loads((ROOT/'runtime/status.json').read_text())
        return bridge and ready(bridge) and set(delta)<=set(status['policies'])
    except (OSError,KeyError,ValueError):
        return False

def follow():
    deadline=json.loads((WORK/'admission-deadline.json').read_text())['unix_time']+180
    while time.time()<deadline:
        delta=changed()
        if delta and available(delta):
            command=[sys.executable,str(HERE/'cpu_limit.py'),'--audit',sys.executable,__file__,'scan']
            for attempt in range(3):
                result=subprocess.run(command)
                if result.returncode==0:break
                if not available(changed()):break
                if attempt==2:result.check_returncode()
                time.sleep(5)
        time.sleep(15)


if __name__=='__main__':
    (audit if sys.argv[1:] == ['scan'] else follow)()
