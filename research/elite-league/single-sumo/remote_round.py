"""Play the common archive fixtures while the three remote policies train, then rank the entire field."""
import sys
import time
from itertools import combinations_with_replacement

from league_tournament import EVIDENCE, Worker, games, stats, tournament, write_json
from remote_league import OUT, ROLES, read

PREFIX='remote-001-'


def entrants():
    generation=read(EVIDENCE/'g002/generation.json')
    field=dict(generation['field'])
    families={k:list(v) for k,v in generation['families'].items()}
    for k,v in read(OUT/'protocol.json')['entrants'].items():
        name='sg003-long-'+k
        field[name]=v
        families.setdefault('65k single-opponent PPO',[]).append(name)
    return field,families,generation['incumbent']


def run():
    field,families,inc=entrants()
    write_json(OUT/'tournament-protocol.json',dict(opponents=field,entrants=[PREFIX+r for r in ROLES],cap=7,
               qualification='All entrants, final 65536 weights. Rank by the ordinary league merit: equal parts mean family score and worst family score. Record the weakest distinct opponent, W-tap, and separate ring-out wins.',
               fixture='ordinary 256-game seeded development round robin, mirrored seats and four RTT pairs'),fixed=True)
    w=Worker()
    try:
        # Existing local common fixtures may be imported before launch, and every remaining cell is resumable.
        for i,(a,b) in enumerate(combinations_with_replacement(sorted(field),2)):
            games(w,field,a,b)
            if i%25==0:print('archive cells complete',i+1,flush=True)
        while not all((OUT/r/'result.json').exists() for r in ROLES):time.sleep(8)
        for r in ROLES:
            name=PREFIX+r
            field[name]=read(OUT/r/'result.json')
            families.setdefault('remote mixed PPO',[]).append(name)
        if not (OUT/'tournament.json').exists():tournament(w,field,families,inc,OUT/'tournament.json')
        result=read(OUT/'tournament.json')
        checked={}
        for n in {result['winner'],inc,*[PREFIX+r for r in ROLES]}:
            weak=min((f for f in field if f!='wtap' and field[f]['sha256']!=field[n]['sha256']),
                     key=lambda f:(result['matrix'][n][f]['wins'],result['matrix'][n][f]['score'],f))
            checked[n]=dict(weakest=weak,against_weakest=result['matrix'][n][weak],wtap_against_weakest=result['matrix']['wtap'][weak],
                            against_wtap=result['matrix'][n]['wtap'],wtap_mirror=result['matrix']['wtap']['wtap'])
        write_json(OUT/'tournament-result.json',dict(winner=result['winner'],policy=field[result['winner']],
                   records=checked,matchups=len(field)*(len(field)+1)//2),fixed=True)
    finally:w.close()


def precompute(role):
    field,_,_=entrants()
    done=OUT/role/'result.json'
    while not done.exists():time.sleep(10)
    actor=PREFIX+role
    parent_hash=read(OUT/'protocol.json')['initial']['mlp' if role=='mlp' else 'gru']['sha256']
    parent=next(n for n,r in field.items() if r['sha256']==parent_hash)
    original=list(field)
    field[actor]=read(done)
    worker=Worker()
    try:
        cells={n:{k:stats(games(worker,field,p,n)) for k,p in [('candidate',actor),('parent',parent),('wtap','wtap')]} for n in original}
    finally:worker.close()
    weak=min((n for n in original if field[n]['sha256']!=field[actor]['sha256']),
             key=lambda n:(cells[n]['candidate']['wins'],cells[n]['candidate']['score'],n))
    grade={label:dict(mean_outright=sum(cells[n][label]['wins'] for n in original)/(256*len(original)),
                      majority=sum(cells[n][label]['wins']>128 for n in original)) for label in ('candidate','parent','wtap')}
    write_json(OUT/role/'common-field.json',dict(parent=parent,weakest=weak,summary=grade,cells=cells,
               status='Completed final weights against the shared starting field. Remaining entrants join the frozen full tournament once all are ready.'),fixed=True)


def calendar_spread(role):
    from assay import lower_contrast
    from learner import CLOCKS
    field,_,_=entrants()
    foes=list(field)
    result=read(OUT/role/'common-field.json')
    name=PREFIX+role
    field[name]=read(OUT/role/'result.json')
    references={'candidate':name,'parent':result['parent'],'previous-counter':'sg003-long-g1','wtap':'wtap'}
    rates={}
    for label,actor in references.items():
        rows=[r for foe in foes for r in games(None,field,actor,foe)]
        size=32*len(foes)
        rates[label]=[sum(r[5]==1 for r in rows if r[0]==i)/size for i in range(len(CLOCKS['dev']))]
        if len(rows)!=len(rates[label])*size:raise ValueError('Unbalanced calendar cells')
    effects={}
    for label in ('parent','previous-counter','wtap'):
        donor=[x-y for x,y in zip(rates['candidate'],rates[label],strict=True)]
        effects[label]=dict(mean=sum(donor)/len(donor),interval=lower_contrast(donor,.025))
    write_json(OUT/role/'calendar-spread.json',dict(outright_rate=rates,candidate_advantage=effects,
        scope='Paired differences over eight exposed timing recordings on a fixed 35-opponent panel. Nominal two-sided 95% t intervals describe timing variation and do not cover opponent selection.'),fixed=True)


if __name__=='__main__':
    if sys.argv[1:2]==['spread']:calendar_spread(sys.argv[2])
    elif sys.argv[1:]:precompute(sys.argv[1])
    else:run()
