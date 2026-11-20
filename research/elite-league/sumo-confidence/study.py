"""Freeze exact duelable actors and run the prospectively blocked native comparison."""
import argparse
from contextlib import ExitStack,contextmanager
import importlib.util
import json
import os
from pathlib import Path
import random
import shutil
import sys
import time
D=Path(__file__).resolve().parent
ROOT=D.parents[2]
BUILD=ROOT/'build/elite-league/sumo-confidence'
sys.path.insert(0,str(D.parent/'sumo-inputs'))
from live import WT,ping,freeze,read,sha,load_batch
from native_trials import ready,active_bridge
from cpu_limit import family_usage,lab_cpu_budget
OLD=ping.OLD
NEW='sumo-vote-final'
FOES=[WT,'shift-check-v4-4','sumo-two-ping','sumo-own-ping']
CELLS=[(side,a,140-a) for side in 'ab' for a in (31,109)]
SERIES=32

def props(n):
    return dict(l.split('=',1) for l in (ROOT/'policies'/(n+'.properties')).read_text().splitlines() if '=' in l)

def pin():
    folder=D/'frozen';folder.mkdir(exist_ok=True)
    names={NEW,OLD,'best-sumo',*FOES}
    pinset={}
    for name in sorted(names):
        source=ROOT/'policies'/(name+'.properties')
        target=folder/source.name
        if not target.exists():shutil.copyfile(source,target)
        if name!='best-sumo' and sha(source)!=sha(target):raise ValueError('Frozen actor changed: '+name)
        pinset[name]=sha(target)
        weights=dict(l.split('=',1) for l in target.read_text().splitlines() if '=' in l).get('duel_network')
        if weights:
            path=folder/(name+'.net')
            if not path.exists():path.write_text(weights+'\n')
            assert path.read_text().strip()==weights
    assert (folder/(OLD+'.net')).read_bytes()==(folder/'best-sumo.net').read_bytes()
    assert (folder/(NEW+'.net')).read_bytes()==(D.parent/'sumo-inputs/ensemble/member.net').read_bytes()
    if (D/'pins.json').exists():
        if read(D/'pins.json')['policies']!=pinset:raise ValueError('Frozen policy registry changed')
    else:freeze(D/'pins.json',{'policies':pinset,'bridge':active_bridge(),'server':sha(ROOT/'build/lab-agent.jar')})

def archived_cohort():
    source=D.parent/'single-sumo/forge_window.py'
    spec=importlib.util.spec_from_file_location('confidence_forge_window',source)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    return module

# The standalone campaign shares one drained runtime across adjacent blocks to avoid repeated server restarts.
_campaign=None
@contextmanager
def pinned_block(runtime,bridge,pins,planned):
    global _campaign
    if _campaign is None:
        with runtime.window(bridge,server=pins['server']) as live:yield live
        return
    if _campaign['live'] is None:
        _campaign['live']=_campaign['stack'].enter_context(runtime.window(bridge,server=pins['server']))
        _campaign['time']=time.monotonic();_campaign['matches']=0
    yield _campaign['live']
    _campaign['matches']+=planned
    if _campaign['matches']>=48 or time.monotonic()-_campaign['time']>=900:
        _campaign['stack'].close();_campaign['live']=None

def general(block):
    rows=[]
    def add(arm,foe,side,own,other,tag):
        q=f'cf20-g{block:02}-{tag}-{side}{own}'
        rows.append((q,ping.trial(q,arm,foe,side,own,other)))
    for s,a,b in CELLS:
        for i,foe in enumerate(FOES):
            add(NEW,foe,s,a,b,'n'+str(i));add(OLD,foe,s,a,b,'o'+str(i))
            if block%4==0:add(WT,foe,s,a,b,'w'+str(i))
        for rep in range(3):add(NEW,OLD,s,a,b,'d'+str(rep))
    random.Random(821331+block).shuffle(rows)
    return dict(rows)

def play(name,plan):
    path=D/'native'/(name+'.json');path.parent.mkdir(exist_ok=True)
    found={}
    if path.exists():
        saved=read(path)
        if saved['plan']!=plan:raise ValueError('Changed schedule: '+name)
        found=saved['matches']
        if set(found)==set(plan) and len(set(found.values()))==len(plan):return
    earliest=(D/'protocol.md').stat().st_mtime-60
    for f in (ROOT/'runtime/results').glob('m*.json'):
        if f.stat().st_mtime<earliest:continue
        g=read(f);q=g.get('request')
        if q in plan:
            if q in found and g['id']!=found[q]:raise ValueError('Duplicate request '+q)
            found[q]=g['id']
    if set(found)==set(plan):
        if not path.exists() or read(path)['matches']!=found:load_batch().save(path,plan,found)
        return
    pins=read(D/'pins.json')
    for k,h in pins['policies'].items():
        if k!='best-sumo' and sha(ROOT/'policies'/(k+'.properties'))!=h:raise ValueError('Policy changed '+k)
    cohort=archived_cohort()
    bridge=ROOT/'build/elite-league/sumo-inputs-runtime/bridge.jar'
    if sha(bridge)!=pins['bridge']:raise ValueError('Frozen client artifact differs')
    with pinned_block(cohort,bridge,pins,len(plan)-len(found)) as live:
        if live!=pins['bridge'] or cohort.server_hash()!=pins['server']:
            raise RuntimeError('Live runtime differs from the frozen runtime')
        missing=None
        def available():
            nonlocal missing
            try:ok=ready(live) and cohort.server_hash()==pins['server']
            except (OSError,ValueError,KeyError):ok=False
            if not ok:
                if missing is None:missing=time.monotonic()
                if time.monotonic()-missing>45:raise RuntimeError('Frozen runtime was replaced during a native cohort')
                return 0
            missing=None
            return capacity()
        load_batch().run(plan,3,path,2,True,found,2,capacity_limit=available)

previous=None
last_jobs=2
def capacity():
    global previous,last_jobs
    if shutil.disk_usage(ROOT).free < 9*1024**3:return 0
    now=time.monotonic()
    if previous and now-previous[0]<3:return last_jobs
    pid=read(ROOT/'runtime/manager.json')['pid'];cur=family_usage([pid],True)
    if previous:
        before,old=previous;load=sum(max(0,v-old.get(k,v)) for k,v in cur.items())/(now-before)
        budget=lab_cpu_budget()
        last_jobs=3 if load<budget-.7 else 2 if load<budget-.1 else 1
    previous=now,cur
    return last_jobs

def main():
    pin()
    freeze(D/'general-plan.json',{f'g{b:02}':general(b) for b in range(1,SERIES+1)})
    from seven_study import fill as seven_fill
    # Earlier 20-CPS requests keep their IDs and moves. The first new control block
    # verifies the 7-CPS physical limiter before admitting more capped fixtures.
    seven_fill(1)
    for b in range(1,SERIES+1):
        play(f'g{b:02}',general(b))
        if (D/'specialist/ready.json').exists():
            from specialist_native import fill_until
            fill_until(b)
        if b>=3:seven_fill(min(16,4+6*(b-3)))
    from specialist_native import finish
    finish()
    seven_fill(16,only_ready=False)

if __name__=='__main__':
    sys.modules['study']=sys.modules[__name__]
    with ExitStack() as stack:
        _campaign=dict(stack=stack,live=None)
        main()
