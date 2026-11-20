"""Test whether practicing the new generalist's three weakest opponents beats equal extra training."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import fcntl
import subprocess
import sys

from learner import HERE, ROOT, Worker, write_json
from league_tournament import schedule, stats
from remote_league import OUT, read
from remote_round import entrants

DEST = OUT/'weak-matchups'
ROLES = ('original-mixture','weakest-mixture')


def register():
    field, _, _ = entrants()
    cells = read(OUT/'mlp/common-field.json')['cells']
    weakest = sorted(cells, key=lambda n: (cells[n]['candidate']['wins'],cells[n]['candidate']['score'],n))[:3]
    warm = OUT/'mlp/trainer.pt'
    for role in ROLES:
        cfg = read(OUT/'mlp/config.json')
        cfg.update(warm=str(warm.relative_to(ROOT)),seed=293491,action_seed=29349101,draw_seed=120394)
        if role == 'weakest-mixture':
            groups = cfg['families']
            former = set(groups['archive'] + groups['counters'])
            groups['counters'] = [field[n]['reference'] for n in weakest]
            groups['archive'] = sorted(former.difference(groups['counters']))
        write_json(DEST/role/'config.json',cfg,fixed=True)
    write_json(DEST/'protocol.json',dict(parent=read(OUT/'mlp/result.json'),weakest=weakest,field=field,
        training='Both arms start from the same finished MLP, fresh optimizer and matched draws for 65536 additional fights. They differ only in the opponents prioritized in the 32/16/16 mixture.',
        model='256 games per opponent for every arm and W-tap on the 35 common development opponents. All four actors use the same two seeds, timing tape, seat and RTTs in each fixture. Score outright wins and the worst opponent.',
        native='Independent eight-block comparisons of both arms, the parent and plain W-tap against the three preselected opponents and W-tap. Enroll both final actors in generation four.'),fixed=True)


def enroll():
    from remote_fit import delegate
    register()
    with (DEST/'training.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        if (DEST/'entrants.json').is_file():return read(DEST/'entrants.json')
        with ThreadPoolExecutor(max_workers=len(ROLES)) as pool:
            jobs=[pool.submit(delegate,DEST/r,1024) for r in ROLES]
            for job in jobs:job.result()
        actors={role:read(DEST/role/'remote-result.json')['policy'] for role in ROLES}
        write_json(DEST/'entrants.json',actors,fixed=True)
        return actors


def evaluate():
    protocol=read(DEST/'protocol.json')
    field=protocol['field']
    actors={**read(DEST/'entrants.json'),'parent':protocol['parent'],'wtap':field['wtap']}
    w=Worker()
    try:
        for name,foe in field.items():
            output=DEST/'model-cells'/(name+'.json')
            if output.is_file():continue
            # Context and both actors' random streams stay fixed when changing weights.
            base,keys=schedule('candidate',name,{'candidate':actors['parent'],name:foe})
            grades={}
            for role,actor in actors.items():
                fixtures=[(actor['reference'],*row[1:]) for row in base]
                outcomes=[r for i in range(0,len(fixtures),32) for r in w.run(fixtures[i:i+32],False)]
                grades[role]=stats([k+[r[0],r[1]] for k,r in zip(keys,outcomes,strict=True)])
            write_json(output,grades,fixed=True)
    finally:w.close()
    cells={n:read(DEST/'model-cells'/(n+'.json')) for n in field}
    summary={}
    for role in actors:
        weakest=min(cells,key=lambda n:(cells[n][role]['wins'],cells[n][role]['score'],n))
        summary[role]=dict(mean_wins=sum(row[role]['wins'] for row in cells.values())/(256*len(field)),
                           weakest=weakest,worst=cells[weakest][role],wtap_vs_worst=cells[weakest]['wtap'],
                           vs_wtap=cells['wtap'][role])
    write_json(DEST/'model.json',dict(summary=summary,cells=cells),fixed=True)


def remote_score():
    from remote_fit import SSH,RSYNC,REMOTE,invoke
    from remote_import import wait_for
    import shlex
    paths=[Path(__file__).resolve().relative_to(ROOT),*(DEST.relative_to(ROOT)/n for n in ('protocol.json','entrants.json'))]
    invoke([*RSYNC,'-R',*map(str,paths),SSH[-1]+':'+REMOTE+'/'])
    invoke([*SSH,'cd '+shlex.quote(REMOTE)+' && python3 research/elite-league/single-sumo/remote_jobs.py'])
    prefix='weak-matchups/'
    cells=[prefix+'model-cells/'+n+'.json' for n in read(DEST/'protocol.json')['field']]
    wait_for([*cells,prefix+'model.json'])


def run():
    actors=enroll()
    if not (DEST/'model.json').exists():
        from remote_fit import enabled
        if enabled():remote_score()
        else:subprocess.run([sys.executable,HERE.parent/'cpu_limit.py','nice','-n','8',sys.executable,__file__,'evaluate'],check=True)
    from matched_native import assess
    protocol=read(DEST/'protocol.json')
    field=protocol['field']
    arms={**actors,'parent':dict(**protocol['parent'],native_alias='sumo-generalist-001-64k'),'wtap':field['wtap']}
    foes=list(dict.fromkeys(['wtap',*protocol['weakest']]))
    assess(DEST/'native','weak-matchups-001',arms,{n:field[n] for n in foes})

def publish_frontier():
    from adaptive_router import runtime,trace_parity
    from native_league import publish
    from matched_native import assess
    field=read(DEST/'protocol.json')['field']
    scores=read(DEST/'model.json')['summary']
    winner=max(ROLES,key=lambda role:(scores[role]['worst']['wins'],scores[role]['mean_wins']))
    ref=read(DEST/'entrants.json')[winner]
    name=publish('sumo-generalist-001-128k',ref,'7 CPS broad PPO MLP, top of the two 131072-fight generalists on the 35-opponent simulator field')
    pin=runtime()
    parity=trace_parity(ROOT/ref['reference'],pin)
    write_json(DEST/'publication.json',dict(name=name,winner=winner,policy=ref,bridge=pin['forge']['sha256'],
              action_trace=parity,selection='Maximize weakest outright wins, then mean, among the two equal-budget training arms.'),fixed=True)
    arms={name:dict(**ref,native_alias=name),'previous-generalist':read(OUT/'mlp/result.json'),
          'previous-strong-specialist':field['sg003-long-g1'],'wtap':field['wtap']}
    names=dict.fromkeys(('wtap',scores[winner]['weakest'],'sg003-long-g1'))
    assess(DEST/'frontier-native','weak-matchups-frontier-001',arms,{n:field[n] for n in names})


if __name__=='__main__':
    if sys.argv[1:]==['evaluate']:evaluate()
    elif sys.argv[1:]==['remote-score']:remote_score()
    elif sys.argv[1:]==['publish']:publish_frontier()
    else:run()
