"""Play frozen architecture finalists on the main Forge lab under matched gameplay settings."""
import argparse
import hashlib
import importlib.util
import itertools
import fcntl
import os
import json
from pathlib import Path
import random
import sys
import time
from run import HERE,ROOT,FAMILIES

def load_batch():
    spec=importlib.util.spec_from_file_location('lab_batch',ROOT/'research/sumo-strategies/native.py')
    batch=importlib.util.module_from_spec(spec);spec.loader.exec_module(batch)
    return batch

def installed():
    return hashlib.sha256((ROOT/'build/practice-client/practice-bridge.jar').read_bytes()).hexdigest()

def active_bridge():
    # Forge loads a private copy per slot. Another local build can legitimately update the template jar.
    manager=json.loads((ROOT/'runtime/manager.json').read_text())
    values=set()
    for i in range(manager['bot_slots']):
        heartbeat=json.loads((ROOT/f'runtime/bots/{i}/heartbeat.json').read_text())
        if time.time()-heartbeat['time']/1000>=6:return None
        path=ROOT/f'runtime/bots/{i}/mods/practice-bridge.jar'
        actual=hashlib.sha256(path.read_bytes()).hexdigest()
        if heartbeat['bridge_sha256']!=actual:return None
        values.add(actual)
    return values.pop() if len(values)==1 else None

def ready(expected=None):
    if expected is None:expected=installed()
    status=json.loads((ROOT/'runtime/status.json').read_text())
    return time.time()-status['time']/1000<5 and all(
        f'LabSlot{i}' in status['players'] and
        (lambda d:d['bridge_sha256']==expected and time.time()-d['time']/1000<6)(
         json.loads((ROOT/f'runtime/bots/{i}/heartbeat.json').read_text()))
        for i in range(json.loads((ROOT/'runtime/manager.json').read_text())['bot_slots']))

def install(selected,tag):
    result=json.loads((selected/'selection.json').read_text())
    champion=next(f.lower() for f,n in result['selected'].items() if n==result['champion'])
    for fam in (f.lower() for f in FAMILIES):
        net=selected/(fam+'.net');assert hashlib.sha256(net.read_bytes()).hexdigest()==result['fit_sha256'][result['selected'][fam.upper()]]
        for box in (False,True):
            if box and fam!=champion:continue
            name=f'{tag}-{fam}'+('-box' if box else '')
            base='research-wtap20' if box else 'research-hold20'
            template=(ROOT/'policies'/f'{base}.properties').read_text().splitlines()
            content='\n'.join([r for r in template if not r.startswith('description=')]+[
                f'description={fam.title()} tournament finalist. Normal movement and capped attack controls.',
                f'duel_network={net.read_text().strip()}',f'duel_network_landmark={3 if box else 0}'])+'\n'
            dest=ROOT/'policies'/(name+'.properties')
            if dest.exists() and dest.read_text()!=content:raise ValueError(f'Policy conflict at {dest}')
            dest.write_text(content)
    return champion

def plan(tag,champion):
    arms=[tag+'-'+f.lower() for f in FAMILIES];winner=tag+'-'+champion
    wt='research-wtap20';hold='research-hold20';pressure='sumo-rl-pressure'
    groups=[('sumo','mmc',opp,[wt,hold,pressure,*arms],2) for opp in (wt,hold)]
    groups += [('sumo','mmc',opp,[wt,hold,winner],3) for opp in ('trade-wtap','jump-reset','research-zest-strafe',pressure)]
    groups += [('sumo','hypixel',opp,[wt,hold,winner],2) for opp in (wt,hold)]
    groups += [('boxing','mmc','combo-wake-2000',[wt,winner+'-box'],4)]
    rows=[]
    for mode,profile,opp,entries,n in groups:
        for rep,rtt,side,arm in itertools.product(range(n),(0,80),'ab',entries):
            req=f'{tag}-t-{mode}-{profile}-{opp}-{rep}-{rtt}-{side}-{arm}'
            a,b=(arm,opp) if side=='a' else (opp,arm)
            base=f'lab sumo {a} {b} {rtt} {rtt}' if mode=='sumo' else f'lab scenario {a} {b} {rtt} {rtt} 20000'
            rows.append((req,dict(mode=mode,profile=profile,opponent=opp,arm=arm,rep=rep,rtt=rtt,side=side,
                command=f'{base} {req} preset={profile} reach=3 cps=20 tapdelay=0 aimdelay=0 aimnoise=0 aimrate=0')))
    random.Random(982177).shuffle(rows)
    return dict(rows)

def cohort_priority(name):
    # The common frozen final has no dependency on the track confirmations.
    # Give both game modes turns in the same FIFO as the general confirmation.
    if name.startswith(('mmc13-final','boxing100-native','box-student-native')):return 0
    if name.startswith(('mmc13-opening','mmc13-confirm','mmc13-box-clocks','m13floor','focus-','ladder-','confirmation-','mouse-smoke','gate-smoke','port-smoke')):return 0
    if name.startswith(('mmc13-actor','mmc13-pairs')):return 4
    if name.startswith('history-'):return 5
    if name.startswith('mmc13-trade-confirm'):return 7
    if name.startswith(('mmc13-broad','mmc13-slice','mmc13-transfer','mmc13-archive-final')):return 8
    if name.startswith(('continuation-','cadence-','box-breadth-','box-student','gate-native','boxing100')):return 8
    if name.startswith(('novel-','empirical-','final-')):return 10
    if name.startswith('eold'):return 20
    return 30

def run_batch(trials,out):
    # Keep the complete frozen schedule visible while short cohorts share eight physical clients.
    # A phase ends only after every issued match in it records a terminal result.
    if out.exists():
        record=json.loads(out.read_text())
        if record['plan']!=trials:raise ValueError('Existing manifest has different protocol')
        if record['matches'].keys()==trials.keys():
            for req,mid in record['matches'].items():
                if json.loads((ROOT/'runtime/results'/(mid+'.json')).read_text())['request']!=req:
                    raise ValueError('Recorded result differs from its request')
            return
    work=ROOT/'build/elite-league';waiting=work/'native-waiting';waiting.mkdir(exist_ok=True)
    with out.with_suffix('.runner.lock').open('a') as runner:
        fcntl.flock(runner.fileno(),fcntl.LOCK_EX)
        found=None
        while found is None or len(found)<len(trials):
            ticket=waiting/f'{cohort_priority(out.stem):02d}-{time.time_ns()}-{os.getpid()}'
            ticket.touch(exist_ok=False)
            try:
                with (work/'main-cohort.lock').open('a') as shared:
                    while True:
                        queue=[]
                        for item in sorted(waiting.iterdir()):
                            try:os.kill(int(item.name.split('-')[-1]),0)
                            except ProcessLookupError:
                                item.unlink(missing_ok=True);continue
                            queue.append(item)
                        if queue and queue[0]==ticket:
                            try:fcntl.flock(shared.fileno(),fcntl.LOCK_EX|fcntl.LOCK_NB);break
                            except BlockingIOError:pass
                        time.sleep(.5)
                    ticket.unlink(missing_ok=True)
                    found=_run_locked(trials,out,found)
            finally:ticket.unlink(missing_ok=True)

def _run_locked(trials,out,found=None):
    batch=load_batch()
    lock=out.with_suffix('.build.json')
    pinned=json.loads(lock.read_text()) if lock.exists() else {}
    expected=pinned.get('bridge_sha256')
    until=time.monotonic()+150
    while True:
        try:
            live=active_bridge()
            if live and (not expected or live==expected) and ready(live):break
        except (OSError,ValueError,KeyError):
            pass
        if time.monotonic()>until:raise RuntimeError('Native pool is missing the frozen bridge or fresh heartbeats')
        time.sleep(2)
    build={'bridge_sha256':live,
        'server_agent_sha256':hashlib.sha256((ROOT/'build/lab-agent.jar').read_bytes()).hexdigest()}
    if lock.exists() and any(build.get(k)!=v for k,v in json.loads(lock.read_text()).items()):
        raise ValueError('Native cohort was opened on different runtime artifacts')
    if not lock.exists():lock.write_text(json.dumps(build)+'\n')
    # A recovered process reconciles by immutable request ID before issuing any further rounds.
    if found is None:
        found={}
        for path in (ROOT/'runtime/results').glob('m*.json'):
            g=json.loads(path.read_text());req=g.get('request')
            if req in trials:
                if req in found:raise RuntimeError('Duplicate trial '+req)
                found[req]=g['id']
    if out.exists() and json.loads(out.read_text())['plan']!=trials:raise ValueError('Existing manifest has different protocol')
    batch.save(out,trials,found)
    part=out.with_suffix('.partial.json')
    pending={k:v for k,v in trials.items() if k not in found}
    if pending:
        jobs=int(os.environ.get('ELITE_NATIVE_JOBS','4' if (os.cpu_count() or 1)>=12 else '3'))
        if not 1<=jobs<=4:raise ValueError('Expected one to four simultaneous native duels')
        def recover():
            if (ROOT/'runtime/manager.json').exists():return False
            from deploy_runtime import resume
            folder=out.parent/(out.stem+'-recovery-'+str(time.time_ns()))
            resume(folder,out)
            print('Main lab recovered at',folder,flush=True)
            return True
        manager_boot=json.loads((ROOT/'runtime/manager.json').read_text())['started']
        def capacity_limit():
            # Forge finalizes renderer/JIT work after connecting. Leave headroom while it settles.
            age=time.time()-manager_boot
            if age<220:return 2
            if age<250:return 3
            return jobs
        try:
            batch.run(trials,jobs,part,reserve=2,share_with_players=True,finished=found,
                      empty_lab_reserve=0 if jobs==4 else 2,recover=recover,only_requests=list(pending)[:64],capacity_limit=capacity_limit)
        except SystemExit:
            # Reflect all duels which drained at closing time in the primary manifest.
            if part.exists():
                partial=json.loads(part.read_text())
                if partial['plan']!=trials:raise ValueError('Partial manifest has different protocol')
                found.update(partial['matches'])
                batch.save(out,trials,found)
            raise
        found.update(json.loads(part.read_text())['matches'])
    else:batch.save(part,trials,found)
    batch.save(out,trials,found)
    return found

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('selected',type=Path);p.add_argument('out',type=Path);p.add_argument('--tag',default='elite-v1')
    p.add_argument('--install-only',action='store_true')
    args=p.parse_args()
    champion=install(args.selected,args.tag)
    if not args.install_only:
        until=time.monotonic()+150
        while not ready():
            if time.monotonic()>until:raise RuntimeError('Expected current Forge bot bridge on all slots')
            time.sleep(2)
        run_batch(plan(args.tag,champion),args.out)
