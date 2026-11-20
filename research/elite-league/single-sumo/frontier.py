"""Fit a best response against the actual tournament winner when the native title still trails."""
import fcntl
import sys
import subprocess
from train import configure
from run_training import train
from league_tournament import EVIDENCE, HERE, Worker, counter_gate, games, stats, pin_model, write_json
from promotion import read, actual_name, fixtures, cohort, WT, RTTS
from forge_window import cohort_window

def search(g):
    parent=EVIDENCE/f'g{g:03d}';folder=parent/'winner-response'
    folder.mkdir(parents=True,exist_ok=True)
    with (folder/'job.lock').open('a') as guard:
        fcntl.flock(guard,fcntl.LOCK_EX)
        if (folder/'result.json').exists():return read(folder/'result.json')
        nom=read(parent/'nomination.json')
        target=nom['winner'];field=nom['field'].copy()
        name=f'sg{g:03d}-winner-response';initial=f'sg{g:03d}-mlp'
        write_json(folder/'protocol.json',dict(target=target,target_sha=field[target]['sha256'],initial=initial,
            initial_sha=field[initial]['sha256'],updates=256,episodes_per_update=64,checkpoint='last',
            opponent_distribution='all bouts against this frozen winner; independent per-bout pings and both seats',
            enrollment='lower development donor bound above 55 percent against winner; all W-tap and weakest cells reported',
            native='eight seat/RTT blocks per opponent, candidate and W-tap'),fixed=True)
        config=read(parent/'mlp/config.json')
        configure(folder,'mlp','exploiter',853781+g*701,79781+g*1709,field[target]['reference'],
                  config['counter'],config['families'],{},parent/'mlp/trainer.pt')
        train(folder,256)
        subprocess.run([sys.executable,HERE.parent/'cpu_limit.py','nice','-n','8',sys.executable,
                        __file__,str(g),'score'],check=True)
        return read(folder/'result.json')

def score(g):
    parent=EVIDENCE/f'g{g:03d}';folder=parent/'winner-response'
    field=read(parent/'nomination.json')['field'];protocol=read(folder/'protocol.json')
    initial=protocol['initial'];target=protocol['target'];name=f'sg{g:03d}-winner-response'
    field[name]=pin_model(folder);worker=Worker()
    try:
        result=counter_gate(worker,field,name,target)
        scores={foe:stats(games(worker,field,name,foe)) for foe in field if foe!=name and '-seal-' not in foe}
        weakest=min(scores,key=lambda f:(scores[f]['score'],f))
        comparison={f:{'learner':scores[f],'initial':stats(games(worker,field,initial,f)),
                       'wtap':stats(games(worker,field,'wtap',f))} for f in dict.fromkeys((target,'wtap',weakest))}
    finally:worker.close()
    result.update(target=target,weakest=weakest,name=name,policy=field[name],matched=comparison)
    write_json(folder/'result.json',result,fixed=True)
    return result

def native(g, result):
    parent=EVIDENCE/f'g{g:03d}';folder=parent/'winner-response';done=folder/'native-result.json'
    if done.exists():return
    field=read(parent/'nomination.json')['field'];field[result['name']]=result['policy']
    arms=[actual_name(result['name'],field),WT]
    foes={f:actual_name(f,field) for f in result['matched']}
    plan=fixtures(f'sg{g:03d}-wr',arms,list(foes.values()),8,7)
    with cohort_window(folder/'native') as bridge:records=cohort(folder/'native',plan,bridge)
    cells={}
    for foe,alias in foes.items():
        cells[foe]={}
        for arm in arms:
            keys=[q for q,t in plan.items() if t['arm']==arm and t['opponent']==alias]
            def counts(qs):
                v=[records[q]['score'] for q in qs]
                return dict(wins=v.count(1),draws=v.count(.5),losses=v.count(0))
            cells[foe][arm]=dict(**counts(keys),seat={s:counts([q for q in keys if plan[q]['side']==s]) for s in 'ab'},
                  rtt={f'{a}/{b}':counts([q for q in keys if (plan[q]['rtt'],plan[q]['opponent_rtt'])==(a,b)]) for a,b in RTTS})
    write_json(done,dict(cells=cells,scope='independent native blocks'),fixed=True)

if __name__=='__main__':
    g=int(sys.argv[1])
    if sys.argv[2:]==['score']:score(g)
    else:
        result=search(g)
        if sys.argv[2:]==['native']:native(g,result)
