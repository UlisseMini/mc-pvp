"""Verify and enroll the whole remote cohort, publish its actual winner and audit native transfer."""
import fcntl
import hashlib
import subprocess
import sys
import tempfile
import time

from learner import BUILD, ROOT, freeze, write_json
from native_league import publish
from remote_league import OUT, ROLES, read
from remote_round import PREFIX

HOST='uli@hetzner1'
REMOTE='mc-sumo-league/runs/robust-001'
REL=str(OUT.relative_to(ROOT))
OPTIONS=['-o','BatchMode=yes','-o','ConnectTimeout=8']


def available(file):
    # Arguments are generated from the single fixed cohort, never external paths.
    r=subprocess.run(['ssh',*OPTIONS,HOST,'test -f '+REMOTE+'/'+REL+'/'+file])
    return r.returncode==0


def retrieve(paths):
    with tempfile.TemporaryDirectory(dir=BUILD/'hetzner') as scratch:
        from pathlib import Path
        directory=Path(scratch)
        files=directory/'pull.txt'
        files.write_text(''.join(p+'\n' for p in paths))
        subprocess.run(['rsync','-az','--files-from='+str(files),'-e','ssh -o BatchMode=yes -o ConnectTimeout=8',
                        HOST+':'+REMOTE+'/'+REL+'/',str(directory)+'/'],check=True)
        for name in paths:
            freeze(OUT/name,(directory/name).read_bytes())


def wait_for(files):
    while not available(files[-1]):time.sleep(30)
    for attempt in range(4):
        try:retrieve(files);return
        except subprocess.CalledProcessError:
            if attempt==3:raise
            time.sleep(10)


def enroll():
    OUT.mkdir(exist_ok=True,parents=True)
    with (OUT/'enrollment.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        if (OUT/'entrants.json').exists():return read(OUT/'entrants.json')
        for role in ROLES:
            wait_for([role+'/'+f for f in ('config.json','final.net','trainer.pt','progress.json','result.json')])
        entrants={}
        for r in ROLES:
            ref=read(OUT/r/'result.json')
            if (ROOT/ref['reference'])!=OUT/r/'final.net' or hashlib.sha256((ROOT/ref['reference']).read_bytes()).hexdigest()!=ref['sha256']:
                raise ValueError('Downloaded actor differs from remote receipt')
            if read(OUT/r/'progress.json')['duels']!=65536:raise ValueError('Downloaded actor has wrong budget')
            entrants[PREFIX+r]=ref
        write_json(OUT/'entrants.json',entrants,fixed=True)
        return entrants


def run():
    entrants=enroll()
    wait_for(['tournament-protocol.json','tournament.json','tournament-result.json'])
    rr=read(OUT/'tournament.json');summary=read(OUT/'tournament-result.json')
    winner=rr['ranking'][0]['name']
    if winner!=summary['winner'] or rr['field'][winner]!=summary['policy']:raise ValueError('Remote winner mismatch')
    name=publish('sumo-league-remote-001',summary['policy'],'7 CPS mixed-PPO tournament winner on hetzner1: '+winner)
    write_json(OUT/'published.json',dict(name=name,winner=winner,policy=summary['policy']),fixed=True)
    from matched_native import assess
    arms={'winner':dict(**summary['policy'],native_alias=name),**entrants,**{'parent-'+k:v for k,v in read(OUT/'protocol.json')['initial'].items()},'wtap':rr['field']['wtap']}
    foes=list(dict.fromkeys(['wtap','sg003-long-incumbent','sg003-long-seven','sg003-long-g1',
                            *[r['weakest'] for r in summary['records'].values()]]))
    assess(OUT/'native','remote-league-001',arms,{f:rr['field'][f] for f in foes})
def history():
    from concurrent.futures import ThreadPoolExecutor
    with ThreadPoolExecutor(max_workers=3) as pool:
        jobs=[pool.submit(wait_for,[f'history/{r}/protocol.json',f'history/{r}/result.json']) for r in ('parent','gru','gru-aux')]
        for job in jobs:job.result()


def prospective():
    base='response-reversal/'
    wait_for([base+'protocol.json',base+'result.json'])
    choice=read(OUT/base/'result.json')
    if not choice['confirmed']:return
    retrieve([base+'selection.json'])
    enrolled=enroll()
    folder=OUT/'prospective'
    protocol=folder/'assay.json'
    if not protocol.exists():
        latest=max(int(p.stem[1:]) for p in (ROOT/'runtime/results').glob('m*.json') if p.stem[1:].isdigit())
        write_json(protocol,dict(first_future_id=latest+6,calendar_count=24,minimum_gap_ms=60000,
                   models=enrolled,opponents=choice['opponents'],selection='first eligible bot-only sumo timing recordings at least 60s apart, from after the last model froze; read no outcomes for selection'),fixed=True)
    from calendars_holdout import collect
    collect(folder)
    clocks=read(folder/'calendars/manifest.json')['selected']
    paths=[*clocks,str((folder/'calendars/manifest.json').relative_to(ROOT))]
    if len(set(clocks))!=24 or any(not c.startswith(str(folder.relative_to(ROOT))+'/calendars/') for c in clocks):raise ValueError('Unexpected reserved calendar set')
    with tempfile.TemporaryDirectory(dir=BUILD/'hetzner') as scratch:
        from pathlib import Path
        files=Path(scratch)/'calendar-files'
        files.write_text(''.join(x+'\n' for x in paths))
        subprocess.run(['rsync','-az','--files-from='+str(files),str(ROOT)+'/',HOST+':'+REMOTE+'/'],check=True)
    subprocess.run(['ssh',*OPTIONS,HOST,'cd '+REMOTE+' && python3 research/elite-league/single-sumo/remote_jobs.py'],check=True)
    for role in ('gru','gru-aux'):
        wait_for([f'history-prospective/{role}/protocol.json',f'history-prospective/{role}/result.json'])


if __name__=='__main__':
    if sys.argv[1:]==['history']:history()
    elif sys.argv[1:]==['prospective']:prospective()
    else:run()
