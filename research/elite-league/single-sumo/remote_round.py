"""Play the common archive fixtures while the three remote policies train, then rank the entire field."""
import time
from itertools import combinations_with_replacement

from league_tournament import EVIDENCE, Worker, games, tournament, write_json
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


if __name__=='__main__':run()
