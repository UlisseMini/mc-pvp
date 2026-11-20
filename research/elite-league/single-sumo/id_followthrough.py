"""Score immutable checkpoints, the predeclared secondary target and the native transfer panel."""
from id_evaluate import *
from id_native import protocol as native_protocol
from id_runner import launch,progress
import fcntl
import time

def initial(target):
    cells=make_panel(CLOCKS['dev'],4)
    root=DIR/'initial-development';dest=root/(target+'.json')
    if dest.exists():return json.loads(dest.read_text())
    w=oracle_worker();table={}
    try:
        roles={'initializer':DIR/'shared-mlp/initial.net','wtap':'wtap'}
        for name,ref in roles.items():
            plan=[[ref,target,clk,side,a,b,rs,fs] for _,_,side,a,b,clk,rs,fs in cells]
            rows=[]
            for i in range(0,len(plan),32):rows+=w.run(plan[i:i+32],False)
            games=[[i,r,s,a,b,v[0],v[1]] for (i,r,s,a,b,_,_,_),v in zip(cells,rows)]
            from league_tournament import stats
            table[name]=dict(result=stats(games),games=games)
    finally:w.close()
    write_json(dest,table,fixed=True);return table

def specialist_milestone(target,exposure):
    from league_tournament import stats
    net=DIR/('dedicated-'+target)/f'n{exposure:05d}.net'
    cells=make_panel(CLOCKS['dev'],4)
    dest=DIR/'specialist-development'/target/f'n{exposure:05d}.json'
    if dest.exists():return
    w=oracle_worker()
    try:
        plan=[[net,target,clk,side,a,b,s,f] for _,_,side,a,b,clk,s,f in cells]
        results=[]
        for i in range(0,len(plan),32):results+=w.run(plan[i:i+32],False)
    finally:w.close()
    rows=[[i,r,s,a,b,v[0],v[1]] for (i,r,s,a,b,_,_,_),v in zip(cells,results)]
    original=initial(target)
    write_json(dest,dict(duels_trained=exposure,specialist=stats(rows),wtap=original['wtap']['result'],
        initializer=original['initializer']['result'],games=rows),fixed=True)

def wait_file(path):
    while not path.is_file():time.sleep(10)

def secondary_needed():
    first=initial(INCUMBENT)['initializer']['result']['score']
    if first>=.90:return True
    final=DIR/'eval-u256-development/summary-full.json'
    if not final.exists():return None
    cells=json.loads(final.read_text())['cells'][INCUMBENT]
    return max(cells[k]['score'] for k in ('informed-correct','dedicated-full-budget'))>=.95

def follow():
    with (BUILD/'id-follow.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        native_protocol();clock_protocol()
        wait_file(DIR/'shared-mlp/initial.net')
        for target in (INCUMBENT,'wtap',BASE_COUNTER):
            launch('id_followthrough.py','initial',target)
        decision=secondary_needed()
        if decision is None:
            wait_file(DIR/'eval-u256-development/summary-full.json');decision=secondary_needed()
        write_json(DIR/'secondary-decision.json',dict(train=decision,rule=protocol()['secondary_rule']),fixed=True)
        if decision:
            folder=DIR/('dedicated-'+BASE_COUNTER)
            while progress(folder,'duels')<16384:launch('dedicated.py',BASE_COUNTER,16)
        wait_file(DIR/'eval-u256-development/summary-full.json')
        wait_file(DIR/'prospective/calendars/manifest.json')
        launch('id_evaluate.py',256,'prospective')
        if decision:
            for split in ('development','prospective'):launch('id_evaluate.py',256,split,BASE_COUNTER)
        # The referee registry only supplies an experimental label. Each frozen branch still uses real keys.
        for f in ([INCUMBENT,BASE_COUNTER] if decision else [INCUMBENT]):
            subprocess.run([sys.executable,HERE/'id_native.py',f],check=True)
if __name__=='__main__':
    if sys.argv[1:2]==['initial']:initial(sys.argv[2])
    elif sys.argv[1:2]==['snapshot']:specialist_milestone(sys.argv[2],int(sys.argv[3]))
    else:follow()
