"""How much development score is available by picking a different existing policy for each opponent?"""
from league_tournament import *

def measure():
    field=historical();matrix={}
    for name in FIELD:
        matrix[name]={}
        for foe in FIELD:
            rows=games(None,field,name,foe)  # Only called after all 210 immutable archive matchups exist.
            matrix[name][foe]=float(np.mean([r[5] for r in rows]))
    means={name:float(np.mean(list(m.values()))) for name,m in matrix.items()}
    fixed=max(FIELD,key=lambda n:(means[n],n));cells={}
    for foe in FIELD:
        selected=max(FIELD,key=lambda n:(matrix[n][foe],means[n],n))
        cells[foe]=dict(oracle=selected,oracle_score=matrix[selected][foe],fixed=matrix[fixed][foe],wtap=matrix['wtap'][foe])
    result=dict(protocol='exploratory archive selection and scoring on the same eight development clocks',
        weighting='equal weight for each of the 20 actual opponents, including mirrors',
        best_fixed=fixed,mean_fixed=means[fixed],mean_wtap=means['wtap'],
        mean_oracle=float(np.mean([r['oracle_score'] for r in cells.values()])),field=cells,means=means,
        scope='simulation; lookup over 20 existing policies only; held-out and native gains ungraded')
    write_json(EVIDENCE/'archive-reference.json',result,fixed=True)

if __name__=='__main__':measure()
