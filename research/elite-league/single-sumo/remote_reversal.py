"""Find a payoff reversal on training calendars before assigning an opponent-history transfer test."""
import itertools
from collections import defaultdict
from statistics import mean

from assay import inputs,java_rows,lower_contrast
from learner import CLOCKS, write_json
from remote_league import OUT,read

FOLDER=OUT/'response-reversal'


def run():
    p=read(OUT/'protocol.json');actor=p['initial']['gru']['reference']
    frozen={**{n:n for n in ('wtap','hold','v4-ppo-g0496','sumo-seven-first','sumo-cap-ramp')},
            'league001':p['initial']['mlp']['reference'],**{'counter-'+n:v['reference'] for n,v in p['entrants'].items()}}
    responses={n:frozen[n] for n in ('wtap','hold','sumo-cap-ramp','league001','counter-g1','counter-incumbent','counter-seven')}
    write_json(FOLDER/'protocol.json',dict(actor=actor,challenged=frozen,responses=responses,
               selection='Fork at three visible hurt cues. Use only training calendars to choose two opponent policies and two fixed responses that maximize the smaller of the two opposite outright-win advantages. At least half of all starts per opponent must reach the fork.',
               confirmation='New action draws on the eight development clocks. Both preselected response differences must have a positive donor-clustered lower bound, and their training advantages must each be at least eight percentage points.',
               final='Only after the entire PPO cohort has frozen, collect 24 future spaced native timing recordings and test both GRUs. Same-world memory swaps preserve the ten most recent observations. Report matched and unmatched coverage and plain W-tap.'),fixed=True)
    def sample(clocks,foes,choices,split):
        result={}; styles={k:{'reference':frozen[k]} for k in foes}
        keys,rows=inputs(actor,styles,clocks,0,1291 if split=='train' else 1297)
        for foe in foes:
            dest=FOLDER/split/(foe+'.json')
            if not dest.exists():
                chunk=[(key,row) for key,row in zip(keys,rows,strict=True) if key[0]==foe]
                raw=java_rows('ResponseReversal',[r for _,r in chunk],[','.join(responses[c] for c in choices)])
                values=[[key,list((v[responses[c]] for c in choices)) if v['cut'] is not None else None] for (key,_),v in zip(chunk,raw,strict=True)]
                write_json(dest,dict(responses=choices,games=values),fixed=True)
            result[foe]=read(dest)['games']
        return result
    labels=list(responses);train=sample(CLOCKS['train'],list(frozen),labels,'train')
    means={}
    for name,rows in train.items():
        eligible=[v for _,v in rows if v is not None]
        if 2*len(eligible)>=len(rows):
            means[name]={r:mean(int(v[j]==1) for v in eligible) for j,r in enumerate(labels)}
    def advantages(x):
        a,b,m,n=x
        return means[a][m]-means[a][n],means[b][n]-means[b][m]
    pairs=[(*foes,*rs) for foes in itertools.combinations(sorted(means),2) for rs in itertools.permutations(labels,2)]
    if not pairs:
        write_json(FOLDER/'result.json',dict(confirmed=False,reason='Insufficient landmark coverage'),fixed=True);return
    chosen=max(pairs,key=lambda x:(min(advantages(x)),sum(advantages(x)),x))
    a,b,m,n=chosen
    gain=advantages(chosen)
    spec=dict(opponent_names=[a,b],response_names=[m,n],opponents=[frozen[a],frozen[b]],experts=[responses[m],responses[n]],training_gains=list(gain))
    write_json(FOLDER/'selection.json',spec,fixed=True)
    findings={};passed=min(gain)>=.08
    if passed:
        choices=list(dict.fromkeys([m,n,'wtap']))
        data=sample(CLOCKS['dev'],[a,b],choices,'dev')
        for foe,x,y in ((a,m,n),(b,n,m)):
            clusters=defaultdict(list)
            for key,v in data[foe]:
                if v is not None:clusters[key[1]].append(int(v[choices.index(x)]==1)-int(v[choices.index(y)]==1))
            donors=[mean(d) for d in clusters.values()]
            ci=lower_contrast(donors,.025) if len(donors)>=2 else [-1,1]
            findings[foe]=dict(donors=donors,interval=ci)
            passed &= len(donors)>=6 and ci[0]>0
    write_json(FOLDER/'result.json',dict(confirmed=bool(passed),confirmation=findings,**spec),fixed=True)


if __name__=='__main__':run()
