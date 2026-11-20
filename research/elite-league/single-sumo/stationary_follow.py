"""Collect stationary-opponent results, verify the controls, and reserve fresh clocks for qualifying actors."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import subprocess
import sys
import time

from adaptive_router import runtime,trace_parity
from learner import BUILD,ROOT,write_json
from matched_native import assess
from native_league import publish
from remote_import import retrieve,send_future_clocks,wait_for
from remote_league import OUT,read
from stationary_field import DEST,GENERALIST,OPENER,policy
from stationary_frontier import locations

REL='stationary-field/'


def actor_ref(path):
    return dict(reference=str(path.relative_to(ROOT)),sha256=hashlib.sha256(path.read_bytes()).hexdigest())

def frontier_grade(role,split,main):
    from remote_fit import SSH,REMOTE,invoke
    _,folder=locations(role,split)
    report=folder/'frontier-check/result.json'
    if not report.exists():
        invoke([*SSH,'cd '+REMOTE+' && python3 research/elite-league/single-sumo/remote_jobs.py frontier '+role+' '+split])
        wait_for([str(report.relative_to(OUT))])
    summary=read(report)
    qualified=bool(main['passed'] and summary['qualified_against_contemporary_baselines'])
    write_json(folder/'frontier-check/decision.json',dict(primary_pass=main['passed'],
               contemporary_pass=summary['qualified_against_contemporary_baselines'],qualified=qualified,split=split),fixed=True)
    return qualified

def native_router(result,folder,role):
    spec=read(folder/'protocol.json');field=spec['field']
    rates=result['switch_fraction']
    foes=list(dict.fromkeys(['wtap',result['weakest'],min(rates,key=rates.get),max(rates,key=rates.get)]))
    pin=runtime();bridge=BUILD/pin['forge']['jar']
    arms={n:actor_ref(folder/'actors'/(n+'.net')) for n in ('history','tanh-head','switch','recent-six')}
    arms.update({'keep':policy(spec,spec['opener']),'broad-mlp':field[GENERALIST],'wtap':field['wtap']})
    arms.update(read(DEST/'frontier-protocol.json')['controls'])
    assess(folder/'native','stationary-'+role+'-001',arms,{n:field[n] for n in foes},bridge_override=bridge)


def router(strong=False):
    role='strong-router' if strong else 'router'
    folder=DEST/'strong-opening' if strong else DEST
    prefix=str(folder.relative_to(OUT))+'/'
    wait_for([prefix+'selection.json',prefix+'trained.json'])
    receipt=read(folder/'trained.json')
    retrieve([prefix+'actors/'+r+'.net' for r in receipt['actors']]+[prefix+k+'.pt' for k in ('history','tanh-head','one-head')])
    pin=runtime();parity={}
    for role,expected in receipt['actors'].items():
        path=folder/'actors'/(role+'.net')
        if actor_ref(path)['sha256']!=expected:raise ValueError('Returned actor changed')
        parity[role]=trace_parity(path,pin)
    write_json(folder/'forge-parity.json',dict(controls=parity,bridge=pin['forge']['sha256']),fixed=True)
    wait_for([prefix+'dev-result.json'])
    result=read(folder/'dev-result.json')
    with ThreadPoolExecutor(max_workers=1) as pool:
        job=pool.submit(native_router,result,folder,role)
        if frontier_grade(role,'dev',result):
            send_future_clocks(folder/'prospective',dict(actors=receipt['actors'],field=read(folder/'protocol.json')['field']))
            wait_for([prefix+'prospective-result.json'])
            if frontier_grade(role,'prospective',read(folder/'prospective-result.json')):
                name=publish('sumo-adaptive-strong-001' if strong else 'sumo-adaptive-stationary-001',actor_ref(folder/'actors/history.net'),
                             '7 CPS history router, confirmed on fresh simulator timings against frozen feed-forward opponents')
                write_json(folder/'published.json',dict(name=name,scope='prospective simulator confirmation; native results in native/summary.json'),fixed=True)
        job.result()


def native_ppo(role,result):
    spec=read(DEST/'protocol.json');field=spec['field']
    pair_path=DEST/'successors'/role/'pair.json'
    pair=read(pair_path) if pair_path.exists() else None
    actor=pair['gru'] if pair else read(OUT/'protocol.json')['initial']['gru'] if role=='parent' else read(OUT/role/'result.json')
    cells=result['cells'][role]
    weak=min(cells,key=lambda n:cells[n]['wdl'][0])
    arms={'recurrent':actor,'robust-opener':field[OPENER],'broad-mlp':field[GENERALIST],'wtap':field['wtap']}
    arms.update(read(DEST/'frontier-protocol.json')['controls'])
    if pair:arms['same-generation-mlp']=pair['mlp']
    foes=list(dict.fromkeys(['wtap',weak,'sumo-seven-first']))
    assess(DEST/'continuous'/role/'native','stationary-ppo-'+role,arms,{n:field[n] for n in foes})


def continuous():
    # Each native panel can queue separately. Fresh calendars start after all tested weights have frozen.
    roles=('parent','gru','gru-aux')
    def one(role):
        if role!='parent':wait_for([role+'/final.net',role+'/result.json'])
        wait_for([REL+'ppo-protocol.json',REL+'continuous/'+role+'/result.json'])
        return read(DEST/'continuous'/role/'result.json')
    with ThreadPoolExecutor(max_workers=6) as pool:
        futures={r:pool.submit(one,r) for r in roles}
        passed=[];native_jobs=[]
        for r in roles:
            result=futures[r].result()
            native_jobs.append(pool.submit(native_ppo,r,result))
            if frontier_grade(r,'dev',result):passed.append(r)
        if passed:
            send_future_clocks(DEST/'ppo-prospective',dict(roles=passed,field=read(DEST/'protocol.json')['field']))
            for r in passed:
                receipt=REL+'ppo-prospective/continuous/'+r+'/result.json'
                wait_for([receipt])
                frontier_grade(r,'prospective',read(OUT/receipt))
        for job in native_jobs:job.result()


def league():
    from remote_fit import RSYNC,SSH,REMOTE,invoke
    from learner import EVIDENCE
    generation=3
    while True:
        role=f'g{generation:03d}-main'
        source=EVIDENCE/f'g{generation:03d}'
        while not (source/'tournament.json').is_file():
            if (EVIDENCE/'stop.json').exists() and read(EVIDENCE/'stop.json')['generation']<generation:return
            time.sleep(30)
        record=read(source/'tournament.json')['field']
        folder=DEST/'successors'/role
        pair={'gru':record[role],'mlp':record[f'g{generation:03d}-mlp']}
        write_json(folder/'pair.json',pair,fixed=True)
        paths=[str((folder/'pair.json').relative_to(ROOT)),*[v['reference'] for v in pair.values()]]
        subprocess.run([*RSYNC,'-R',*paths,SSH[-1]+':'+REMOTE+'/'],cwd=ROOT,check=True)
        invoke([*SSH,'cd '+REMOTE+' && python3 research/elite-league/single-sumo/remote_jobs.py'])
        prefix=REL+'continuous/'+role+'/'
        wait_for([prefix+'result.json']);result=read(DEST/'continuous'/role/'result.json')
        with ThreadPoolExecutor(max_workers=1) as pool:
            native=pool.submit(native_ppo,role,result)
            if frontier_grade(role,'dev',result):
                send_future_clocks(folder/'prospective',dict(roles=[role],pair=pair,field=read(DEST/'protocol.json')['field']))
                receipt=REL+'successors/'+role+'/prospective/continuous/'+role+'/result.json'
                wait_for([receipt])
                frontier_grade(role,'prospective',read(OUT/receipt))
            native.result()
        generation+=1


if __name__=='__main__':
    if sys.argv[1:]==['continuous']:continuous()
    elif sys.argv[1:]==['league']:league()
    elif sys.argv[1:]==['strong']:router(True)
    else:router()
