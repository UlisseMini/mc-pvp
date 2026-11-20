"""Serialize large jobs and dynamically budget their entire process group alongside the live lab."""
import fcntl
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
ROOT=Path(__file__).resolve().parents[2]

def lab_cpu_budget():
    return .40*(os.cpu_count() or 2)

def cpu_seconds(value):
    days,sep,clock=value.partition('-')
    if not sep:clock=days;days='0'
    total=0.
    for part in clock.split(':'):total=total*60+float(part)
    return int(days)*86400+total

def family_usage(roots,all_research=False):
    rows=subprocess.check_output(['ps','-axo','pid=,ppid=,time=,command='],text=True)
    used={};parents={};members=set(roots)
    for line in rows.splitlines():
        parts=line.split(None,3)
        if len(parts)<3:continue
        pid,ppid,elapsed=int(parts[0]),int(parts[1]),parts[2];command=parts[3] if len(parts)>3 else ''
        used[pid]=cpu_seconds(elapsed);parents[pid]=ppid
        if all_research and any(p in command for p in ('research/elite-league/','build/elite-league/','research/sword26/','research/sumo-rl/')) and (
                'python' in command.lower() or 'java' in command.lower()) and '/bin/zsh -c' not in command:
            members.add(pid)
    while True:
        expanded=members|{pid for pid,ppid in parents.items() if ppid in members}
        if expanded==members:break
        members=expanded
    return {p:used[p] for p in members if p in used}

def supervised():
    return os.environ.get('ELITE_GOVERNED_GROUP')==str(os.getpgrp())

def main(cmd,audit=False,policy=False):
    if supervised():
        return subprocess.call(cmd)
    budget=lab_cpu_budget()
    # Leave margin for startup, GC, incoming players, and short-lived analysis processes.
    target=max(.25,budget-.35)
    state=ROOT/'build/elite-league';state.mkdir(exist_ok=True,parents=True)
    # One replay reader can use spare capacity while a long single-threaded
    # simulation owns the rollout slot. Both governors measure total lab usage.
    lane='replay-reader.lock' if audit else 'policy-training.lock' if policy else 'rollout.lock'
    with (state/lane).open('a') as lock:
        fcntl.flock(lock.fileno(),fcntl.LOCK_EX)
        manager=ROOT/'runtime/manager.json'
        def lab_pid():
            try:return json.loads(manager.read_text())['pid']
            except (OSError,ValueError,KeyError):return -1
        # This wrapper establishes the ID before launching any nested compiler or simulation.
        child=subprocess.Popen([sys.executable,__file__,'--child',*cmd],start_new_session=True)
        running=True
        try:
            prev=family_usage((lab_pid(),child.pid),True);now=time.monotonic();credit=0.
            while child.poll() is None:
                time.sleep(.25)
                cur=family_usage((lab_pid(),child.pid),True);end=time.monotonic()
                work=sum(max(0,v-prev.get(k,v)) for k,v in cur.items())
                credit=min(.13*target,credit+target*(end-now)-work)
                desired=credit>=0
                if desired!=running and child.poll() is None:
                    try:os.killpg(child.pid,signal.SIGCONT if desired else signal.SIGSTOP)
                    except (ProcessLookupError,PermissionError):
                        # Darwin can return EPERM during the exec/exit transition of /usr/bin/nice.
                        # Leave the current state unchanged and retry on the next sample if still alive.
                        child.poll()
                    else:running=desired
                now=end;prev=cur
            return child.returncode
        finally:
            if child.poll() is None:
                for sig in (signal.SIGCONT,signal.SIGTERM):
                    if child.poll() is not None:break
                    try:os.killpg(child.pid,sig)
                    except (ProcessLookupError,PermissionError):
                        if child.poll() is None:child.send_signal(sig)
                child.wait()

def supervise_script():
    if not supervised():
        sys.exit(main([sys.executable,*sys.argv]))

if __name__=='__main__':
    if len(sys.argv)>2 and sys.argv[1]=='--child':
        os.environ['ELITE_GOVERNED_GROUP']=str(os.getpgrp())
        if sys.argv[2:5]==['nice','-n','8']:
            os.nice(8)
            del sys.argv[2:5]
        os.execvp(sys.argv[2],sys.argv[2:])
    audit=sys.argv[1:2]==['--audit'];policy=sys.argv[1:2]==['--policy']
    sys.exit(main(sys.argv[2:] if audit or policy else sys.argv[1:],audit,policy))
