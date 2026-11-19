"""Serialize heavy rollouts and yield their JVM when the running lab consumes the CPU budget."""
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

def family_usage(roots):
    rows=subprocess.check_output(['ps','-axo','pid=,ppid=,time='],text=True)
    used={};parents={}
    for line in rows.splitlines():
        pid,ppid,elapsed=line.split()
        used[int(pid)]=cpu_seconds(elapsed);parents[int(pid)]=int(ppid)
    members=set(roots)
    while True:
        expanded=members|{pid for pid,ppid in parents.items() if ppid in members}
        if expanded==members:break
        members=expanded
    return {p:used[p] for p in members if p in used}

def main(cmd):
    budget=min(6,(os.cpu_count() or 2)/2)
    # Reserve capacity for the incremental replay scorer and short-lived CLI tools.
    target=max(.5,budget-.85)
    state=ROOT/'build/elite-league';state.mkdir(exist_ok=True,parents=True)
    with (state/'rollout.lock').open('a') as lock:
        fcntl.flock(lock.fileno(),fcntl.LOCK_EX)
        manager=ROOT/'runtime/manager.json'
        def lab_pid():
            try:return json.loads(manager.read_text())['pid']
            except (OSError,ValueError,KeyError):return -1
        child=subprocess.Popen(cmd);running=True
        try:
            prev=family_usage((lab_pid(),child.pid));now=time.monotonic();credit=0.
            while child.poll() is None:
                time.sleep(.4)
                cur=family_usage((lab_pid(),child.pid));end=time.monotonic()
                # A first sample for a new process has no measured interval.
                work=sum(max(0,v-prev.get(k,v)) for k,v in cur.items())
                credit=min(.4*target,credit+target*(end-now)-work)
                desired=credit>=0
                if desired!=running and child.poll() is None:
                    child.send_signal(signal.SIGCONT if desired else signal.SIGSTOP);running=desired
                now=end;prev=cur
            return child.returncode
        finally:
            if child.poll() is None:
                child.send_signal(signal.SIGCONT);child.terminate();child.wait()

if __name__=='__main__':sys.exit(main(sys.argv[1:]))
