"""Independently replay a completed fixed-opponent scorecard on the other simulator host."""
import json
from pathlib import Path
import shlex
import subprocess
import sys
import time

from learner import CLOCKS,ROOT,Worker,write_json
from remote_fit import REMOTE,RSYNC,SSH,digest


def replay():
    from league_tournament import historical
    request=json.load(sys.stdin)
    for ref in request['policies']:
        name=ref['reference']
        actual=digest(ROOT/name) if name.endswith('.net') else historical()[name]['sha256']
        if actual!=ref['sha256']:raise ValueError('Frozen actor differs')
    worker=Worker()
    try:
        rows=request['rows']
        outcomes=[r[:2] for i in range(0,len(rows),32) for r in worker.run(rows[i:i+32],False)]
    finally:worker.close()
    print(json.dumps(outcomes))


def verify(target_sha):
    from adversary import STORE,read,ref
    from id_evaluate import make_panel
    from league_tournament import stats
    if len(target_sha)!=64 or any(c not in '0123456789abcdef' for c in target_sha):raise ValueError(target_sha)
    folder=STORE/target_sha
    spec=read(folder/'protocol.json')
    while not (folder/'final.net').is_file():time.sleep(5)
    if read(folder/'progress.json')['duels']!=65536:raise ValueError('The actor has not reached the full budget')
    actors=[ref(folder/'final.net'),spec['target']]
    script=Path(__file__).resolve().relative_to(ROOT)
    files=[script,*[Path(ref['reference']) for ref in actors if ref['reference'].endswith('.net')]]
    # Both weights may have originated locally. Preserve their exact repository paths.
    subprocess.run([*RSYNC,'-R',*map(str,files),SSH[-1]+':'+REMOTE+'/'],cwd=ROOT,check=True)
    panel=make_panel(CLOCKS['dev'],8)
    rows=[[arm,actors[1]['reference'],c,s,a,b,x,y] for arm in [actors[0]['reference'],'wtap']
          for _,_,s,a,b,c,x,y in panel]
    cmd='cd '+shlex.quote(REMOTE)+' && OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 ~/mc-sumo-league/venv/bin/python '+shlex.quote(str(script))+' replay'
    outcomes=json.loads(subprocess.check_output([*SSH,cmd],input=json.dumps(dict(policies=actors,rows=rows)),text=True))
    if len(outcomes)!=len(rows):raise ValueError('Truncated remote scorecard')
    games_by_arm={}
    for index,arm in enumerate(('candidate','wtap')):
        played=outcomes[len(panel)*index:len(panel)*(index+1)]
        games=[[i,r,s,a,b,*out] for (i,r,s,a,b,*_),out in zip(panel,played,strict=True)]
        games_by_arm[arm]=dict(aggregate=stats(games),games=games)
    write_json(folder/'remote-grade.json',dict(policy=actors[0],target=actors[1],matched_fights_per_arm=len(panel),
               **{k:v['aggregate'] for k,v in games_by_arm.items()}),fixed=True)
    while not (folder/'result.json').is_file():time.sleep(5)
    result=read(folder/'result.json')
    if actors!=[result['policy'],result['target']]:raise ValueError('Registered actor differs from the returned matchups')
    for arm,value in games_by_arm.items():
        if read(folder/'evaluation'/(arm+'.json'))!=value:
            raise ValueError('Remote and audited local outcomes or terminal times differ for '+arm)
    write_json(folder/'cross-host-confirmation.json',dict(policy=actors[0],target=actors[1],
        same_outcomes_and_end_times=len(rows),matched_fights_per_arm=len(panel),candidate=result['challenger_result'],wtap=result['wtap']),fixed=True)


if __name__=='__main__':
    if sys.argv[1]=='replay':replay()
    else:verify(sys.argv[1])
