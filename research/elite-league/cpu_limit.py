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
        if all_research and ('research/elite-league/' in command or 'build/elite-league/' in command) and (
                'python' in command.lower() or 'java' in command.lower()) and '/bin/zsh -c' not in command:
            members.add(pid)
    while True:
        expanded=members|{pid for pid,ppid in parents.items() if ppid in members}
        if expanded==members:break
        members=expanded
    return {p:used[p] for p in members if p in used}

def supervised():
    return os.environ.get('ELITE_GOVERNED_GROUP')==str(os.getpgrp())

def main(cmd):
    if supervised():
        return subprocess.call(cmd)
    budget=min(6,(os.cpu_count() or 2)/2)
    # Leave margin for startup, GC, incoming players, and short-lived analysis processes.
    target=max(.5,budget-1.25)
    state=ROOT/'build/elite-league';state.mkdir(exist_ok=True,parents=True)
    with (state/'rollout.lock').open('a') as lock:
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
                    except ProcessLookupError:pass
                    running=desired
                now=end;prev=cur
            return child.returncode
        finally:
            if child.poll() is None:
                os.killpg(child.pid,signal.SIGCONT);os.killpg(child.pid,signal.SIGTERM);child.wait()

def supervise_script():
    if not supervised():
        sys.exit(main([sys.executable,*sys.argv]))

if __name__=='__main__':
    if len(sys.argv)>2 and sys.argv[1]=='--child':
        os.environ['ELITE_GOVERNED_GROUP']=str(os.getpgrp())
        os.execvp(sys.argv[2],sys.argv[2:])
    sys.exit(main(sys.argv[1:]))
