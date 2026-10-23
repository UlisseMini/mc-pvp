#!/usr/bin/env python3
"""Measure the running lab without changing its load, clients, or game timing."""
import argparse
import json
import subprocess
import time
from lab import ROOT, RUNTIME


def cpu_seconds(value):
    total=0.0
    for part in value.split(':'):total=total*60+float(part)
    return total


def snapshot(pids):
    output=subprocess.check_output(['ps','-p',','.join(map(str,pids)),'-o','pid=,time=,rss='],text=True)
    return {int(p):{'cpu_seconds':cpu_seconds(cpu),'rss_mib':int(rss)/1024}
            for p,cpu,rss in (line.split() for line in output.splitlines())}


def measure(seconds=20):
    manager=json.loads((RUNTIME/'manager.json').read_text())['pid']
    rows=subprocess.check_output(['ps','-axo','pid=,ppid=,comm='],text=True)
    pids=[int(p) for p,parent,command in (line.split(None,2) for line in rows.splitlines())
          if int(parent)==manager and command.endswith('/java')]
    if len(pids)!=7:raise RuntimeError(f'Expected one server and six supervised clients, found {len(pids)}')
    first=snapshot(pids);start=time.monotonic();wall_start=time.time()*1000;peak_rss=0;parallel=0;human=False;matches=set()
    while time.monotonic()-start<seconds:
        current=snapshot(pids);peak_rss=max(peak_rss,sum(v['rss_mib'] for v in current.values()))
        state=json.loads((RUNTIME/'status.json').read_text())
        parallel=max(parallel,sum(m['policy_a']!='human' for m in state['matches']))
        human|=any(not p.startswith('LabSlot') for p in state['players'])
        matches.update(m['id'] for m in state['matches'])
        time.sleep(min(1,max(.01,seconds-(time.monotonic()-start))))
    last=snapshot(pids);elapsed=time.monotonic()-start;gaps=[]
    for identifier in matches:
        events=[json.loads(line) for line in (RUNTIME/'replays'/f'{identifier}.jsonl').read_text().splitlines()]
        stamps=[e['time'] for e in events if e['type']=='tick' and e['time']>=wall_start]
        gaps += [b-a for a,b in zip(stamps,stamps[1:])]
    gaps.sort()
    result={'seconds':elapsed,'jvm_processes':len(pids),'observed_parallel_bot_matches':parallel,
            'human_present':human,'cpu_percent_of_one_core':sum(last[p]['cpu_seconds']-first[p]['cpu_seconds'] for p in pids)/elapsed*100,
            'peak_sampled_total_rss_mib':peak_rss,'native_tick_gaps_ms':{
                'samples':len(gaps),'median':gaps[len(gaps)//2] if gaps else None,
                'p99':gaps[min(len(gaps)-1,int(len(gaps)*.99))] if gaps else None,'max':max(gaps,default=None)}}
    destination=ROOT/'experiments/performance.json';destination.parent.mkdir(exist_ok=True)
    destination.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--seconds',type=int,default=20)
    args=parser.parse_args()
    if not 5<=args.seconds<=60:parser.error('Choose 5–60 seconds')
    measure(args.seconds)
