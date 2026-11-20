"""Inspect the starting policies against the complete shared field and audit the strongest specialist's weak matchup."""
from statistics import mean

from league_tournament import games,stats
from matched_native import assess
from remote_league import OUT,read
from remote_round import entrants
from learner import write_json


def run():
    field,_,_=entrants()
    roles=['sg002-mentor-gru','sg001-mlp','sg003-long-incumbent','sg003-long-seven','sg003-long-g1','wtap']
    cells={n:{f:stats(games(None,field,n,f)) for f in field} for n in roles}
    reports={}
    for n,row in cells.items():
        opponents=[f for f in field if field[n]['sha256']!=field[f]['sha256']]
        weakest=min(opponents,key=lambda f:(row[f]['wins'],row[f]['score'],f))
        reports[n]=dict(distinct_weight_hashes=len({field[f]['sha256'] for f in opponents}),
                        opponents_with_majority_wins=[f for f in opponents if row[f]['wins']>128],
                        weakest=weakest,against_weakest=row[weakest],wtap_against_weakest=cells['wtap'][weakest],
                        against_wtap=row['wtap'],wtap_mirror=cells['wtap']['wtap'],
                        cells=row)
    dest=OUT/'starting-field'
    write_json(dest/'model.json',reports,fixed=True)
    name=max((n for n in roles if n.startswith('sg003-long')),key=lambda n:(
        reports[n]['against_weakest']['wins'],mean(r['wins'] for r in cells[n].values())))
    weakest=reports[name]['weakest']
    write_json(dest/'native-design.json',dict(actor=name,weakest=weakest,blocks=8,
               selection='Best worst distinct opponent by outright wins among the three previous 65536-fight specialists. Open development field, 256 games per cell.',
               controls=['sg002-mentor-gru','wtap'],opponents=['wtap',weakest,'sumo-seven-first','v4-ppo-g0496'],
               grade='Independent native ring-outs at matched seven CPS, both seats, four paired RTTs. This small panel does not promote best-sumo.'),fixed=True)
    public={'sg003-long-incumbent':'sumo-counter-frozen-64k','sg003-long-g1':'sumo-counter-league001-64k','sg003-long-seven':'sumo-counter-seven-64k'}
    actors={name:dict(**field[name],native_alias=public[name]),'sg002-mentor-gru':field['sg002-mentor-gru'],'wtap':field['wtap']}
    assess(dest/'native','rr-starting-field-001',actors,{f:field[f] for f in ['wtap',weakest,'sumo-seven-first','v4-ppo-g0496']})


if __name__=='__main__':run()
