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


def mlp_native():
    from matched_native import assess
    from remote_round import entrants
    wait_for(['mlp/final.net','mlp/result.json','mlp/common-field.json'])
    field,_,_=entrants()
    result=read(OUT/'mlp/common-field.json')
    actor=read(OUT/'mlp/result.json')
    name=publish('sumo-generalist-001-64k',actor,'7 CPS mixed-opponent MLP, 65536 training fights')
    arms={'generalist':dict(**actor,native_alias=name),'parent':field[result['parent']],'wtap':field['wtap']}
    foes=list(dict.fromkeys(['wtap',result['weakest'],'sumo-seven-first']))
    folder=OUT/'mlp/native'
    write_json(folder/'design.json',dict(actor=actor,published=name,parent=result['parent'],opponents=foes,
               seat_rtt_blocks=8,control='wtap',qualifier='Independent native games on the default recorded 137-block floor.'),fixed=True)
    assess(folder,'remote-mlp-001',arms,{k:field[k] for k in foes})


def history():
    from concurrent.futures import ThreadPoolExecutor
    with ThreadPoolExecutor(max_workers=3) as pool:
        jobs=[pool.submit(wait_for,[f'history/{r}/protocol.json',f'history/{r}/result.json']) for r in ('parent','gru','gru-aux')]
        for job in jobs:job.result()


def challenge_winner():
    enroll()
    # The final scoreboard is collected by run(), independently of its native panel.
    while not (OUT/'published.json').exists():time.sleep(15)
    tournament=read(OUT/'tournament.json')
    winner=tournament['winner'];target=tournament['field'][winner]
    from generation import ranked_parents
    from adversary import obtain,native_check
    parents=ranked_parents(2,tournament)
    response=obtain(target,parents['mlp'][1],1987301)
    write_json(OUT/'winner-response.json',dict(target=winner,policy=response['policy'],
               confirmed=response['confirmed'],target_sha256=target['sha256'],wins=response['challenger_result']['wins'],
               draws=response['challenger_result']['draws'],plain_wtap=response['wtap'],games=response['games']),fixed=True)
    if response['source']=='single-target MLP PPO':native_check(response)


def payoff_transfer():
    dest=OUT/'payoff-pair'
    wait_for(['payoff-pair/protocol.json','payoff-pair/development.json'])
    if not read(dest/'development.json')['qualified_in_development']:return
    wait_for(['payoff-pair/parity.json'])
    send_future_clocks(dest/'prospective',dict(models='frozen 24-head reward-trained GRU and its controls',
                                      opponents=['sg002-mentor-gru','sumo-seven-first']))
    wait_for(['payoff-pair/prospective-result.json'])


def prospective(head24=False):
    base='response-reversal-head24/' if head24 else 'response-reversal/'
    wait_for([base+'protocol.json',base+'result.json'])
    choice=read(OUT/base/'result.json')
    blueprint=read(OUT/base/'protocol.json')
    files=[base+'train/'+k+'.json' for k in blueprint['challenged']]
    if 'response_names' in choice:files.append(base+'selection.json')
    files.extend(base+'dev/'+k+'.json' for k in choice.get('confirmation',{}))
    retrieve(files)
    if not choice['confirmed']:return
    enrolled=enroll()
    folder=OUT/('prospective-head24' if head24 else 'prospective')
    send_future_clocks(folder,dict(models=enrolled,opponents=choice['opponents']))
    for role in ('gru','gru-aux'):
        which='history-prospective-head24' if head24 else 'history-prospective'
        wait_for([f'{which}/{role}/protocol.json',f'{which}/{role}/result.json'])


def send_future_clocks(folder,metadata):
    protocol=folder/'assay.json'
    if not protocol.exists():
        latest=max(int(p.stem[1:]) for p in (ROOT/'runtime/results').glob('m*.json') if p.stem[1:].isdigit())
        write_json(protocol,dict(first_future_id=latest+6,calendar_count=24,minimum_gap_ms=60000,
                   **metadata,selection='first eligible bot-only sumo timing recordings at least 60s apart, from after the last model froze; read no outcomes for selection'),fixed=True)
    from calendars_holdout import collect
    collect(folder)
    clocks=read(folder/'calendars/manifest.json')['selected']
    paths=[*clocks,str((folder/'calendars/manifest.json').relative_to(ROOT)),str(protocol.relative_to(ROOT))]
    if len(set(clocks))!=24 or any(not c.startswith(str(folder.relative_to(ROOT))+'/calendars/') for c in clocks):raise ValueError('Unexpected reserved calendar set')
    with tempfile.TemporaryDirectory(dir=BUILD/'hetzner') as scratch:
        from pathlib import Path
        files=Path(scratch)/'calendar-files'
        files.write_text(''.join(x+'\n' for x in paths))
        subprocess.run(['rsync','-az','--files-from='+str(files),str(ROOT)+'/',HOST+':'+REMOTE+'/'],check=True)
    subprocess.run(['ssh',*OPTIONS,HOST,'cd '+REMOTE+' && python3 research/elite-league/single-sumo/remote_jobs.py'],check=True)


if __name__=='__main__':
    if sys.argv[1:]==['history']:history()
    elif sys.argv[1:]==['prospective']:prospective()
    elif sys.argv[1:]==['challenge']:challenge_winner()
    elif sys.argv[1:]==['mlp-native']:mlp_native()
    elif sys.argv[1:]==['head24']:prospective(True)
    elif sys.argv[1:]==['payoff']:payoff_transfer()
    elif sys.argv[1:]==['opening-timing']:wait_for(['stationary-field/opening-timing/result.json'])
    elif sys.argv[1:]==['field']:
        from concurrent.futures import ThreadPoolExecutor
        with ThreadPoolExecutor(max_workers=3) as pool:
            jobs=[pool.submit(wait_for,[r+'/common-field.json']) for r in ROLES]
            for job in jobs:job.result()
    else:run()
