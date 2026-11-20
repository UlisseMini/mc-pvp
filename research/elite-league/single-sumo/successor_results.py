"""Compare the new broad policies with their parents on common model games and independent main-server games."""
import fcntl
import subprocess
import sys
import time

from id_evaluate import make_panel
from learner import CLOCKS, HERE, Worker, write_json
from league_tournament import stats
from successor_pool import OUT, ROLES, EVIDENCE, read


def roster():
    spec=read(OUT/'protocol.json')
    field=dict(read(EVIDENCE/'g002/generation.json')['field'])
    field.update({'counter-'+k:v for k,v in spec['entrants'].items()})
    actors={**read(OUT/'weights.json'),**{'parent-'+r:v for r,v in spec['initial'].items()},'wtap':field['wtap']}
    return field,actors


def model():
    field,actors=roster()
    cases=make_panel(CLOCKS['dev'],4)
    worker=Worker()
    try:
        for name,actor in actors.items():
            for foe,v in field.items():
                dest=OUT/'model'/name/(foe+'.json')
                if dest.exists():continue
                plan=[[actor['reference'],v['reference'],c,s,a,b,x,y] for _,_,s,a,b,c,x,y in cases]
                raw=[r for j in range(0,len(plan),32) for r in worker.run(plan[j:j+32],False)]
                rows=[[i,k,s,a,b,r[0],r[1]] for (i,k,s,a,b,*_),r in zip(cases,raw,strict=True)]
                write_json(dest,dict(aggregate=stats(rows),games=rows),fixed=True)
    finally:worker.close()
    cells={n:{f:read(OUT/'model'/n/(f+'.json'))['aggregate'] for f in field} for n in actors}
    worst={n:min((f for f in field if f!='wtap' and field[f]['sha256']!=actors[n]['sha256']),
                 key=lambda f:(cells[n][f]['wins'],cells[n][f]['score'],f)) for n in ROLES}
    foes=list(dict.fromkeys(['wtap','counter-incumbent','counter-seven','counter-g1',*worst.values()]))
    write_json(OUT/'model/summary.json',dict(cells=cells,weakest_distinct=worst,native_opponents=foes,
               metric='outright wins in 256 paired bouts per cell, draws separately; open development calendars'),
               fixed=True)


def native():
    from matched_native import assess
    field,actors=roster()
    labels=read(OUT/'model/summary.json')['native_opponents']
    assess(OUT/'native','counter-successor-1',actors,{n:field[n] for n in labels})


if __name__=='__main__':
    if sys.argv[1:]==['model']:model()
    else:
        with (OUT/'evaluation.lock').open('a') as guard:
            fcntl.flock(guard,fcntl.LOCK_EX)
            while not (OUT/'weights.json').exists():time.sleep(8)
            subprocess.run([sys.executable,HERE.parent/'cpu_limit.py','--terrain','nice','-n','8',sys.executable,__file__,'model'],check=True)
            native()
