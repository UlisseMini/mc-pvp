"""Frozen roles, draw distributions and final-checkpoint selections for one generation."""
import argparse
import sys
from train import configure
from league_tournament import *
from styles import freeze_assay

def read(path):return json.loads(path.read_text())

def initialize(g):
    folder=EVIDENCE/f'g{g:03d}';path=folder/'generation.json'
    if path.exists():return read(path)
    prev=EVIDENCE/f'g{g-1:03d}'
    if g==1:
        field=historical();families={k:list(v) for k,v in FAMILIES.items()};inc=INCUMBENT;counter=BASE_COUNTER
        scores={};warm_r=warm_m=None
    else:
        last=read(prev/'generation.json');rounds=read(prev/'tournament.json')
        title=read(prev/'decision.json');inc=title['live_incumbent']
        field=rounds['field'];families={k:list(v) for k,v in rounds['families'].items()}
        gate=read(prev/'counter.json')
        if gate['confirmed']:counter=gate['challenger']
        else:counter=last['counter']
        scores=next(r['family'] for r in rounds['ranking'] if r['name']==f'sg{g-1:03d}-main')
        warm_r=prev/'main/trainer.pt';warm_m=prev/'mlp/trainer.pt'
    freeze_assay(g,folder)
    result=dict(generation=g,incumbent=inc,counter=counter,field=field,families=families,scores=scores,
                warm_gru=str(warm_r) if warm_r else None,warm_mlp=str(warm_m) if warm_m else None)
    write_json(path,result,fixed=True)
    return result

def refs(d,families):return {k:[d[n]['reference'] for n in members] for k,members in families.items()}

def prep_exploiter(g):
    path=EVIDENCE/f'g{g:03d}';c=initialize(g);field=c['field']
    configure(path/'exploiter','gru','exploiter',1700321+(g-1)*4001,1700378+(g-1)*4001,
              field[c['incumbent']]['reference'],field[c['counter']]['reference'],refs(field,c['families']),c['scores'],c['warm_gru'] if g%2==0 else None)

def counter(g):
    path=EVIDENCE/f'g{g:03d}';c=initialize(g);field=c['field'].copy();name=f'sg{g:03d}-counter'
    field[name]=pin_model(path/'exploiter')
    worker=Worker()
    try:
        gate=counter_gate(worker,field,name,c['incumbent'])
        # Count distinct payoff profiles against other counters to the same champion.
        # A re-found vulnerability does not reset the plateau counter solely because the weights differ.
        previous=[read(p) for p in sorted(EVIDENCE.glob('g*/counter.json')) if p.parent!=path and read(p)['confirmed'] and read(p)['incumbent']==c['incumbent']]
        reference=[c['counter']]+[r['challenger'] for r in previous]
        novelty={}
        if gate['confirmed']:
            for old in sorted(set(reference)):
                contrasts={}
                for foe in ('wtap','hold',INCUMBENT,BASE_COUNTER,'sumo-seven-ppo'):
                    a=stats(games(worker,field,name,foe))['clock'];b=stats(games(worker,field,old,foe))['clock']
                    diff=np.array(a)-np.array(b)
                    lo,hi=interval((diff+1)/2,.05/(5*max(1,len(reference))))
                    contrasts[foe]=[float(diff.mean()),2*lo-1,2*hi-1]
                novelty[old]=contrasts
        gate['profile_contrasts']=novelty
        gate['novel']=bool(gate['confirmed'] and all(any(v[1]>.05 or v[2]<-.05 for v in cell.values()) for cell in novelty.values()))
        write_json(path/'counter.json',gate,fixed=True)
    finally:worker.close()
    name_ref=field[name]['reference'] if gate['confirmed'] else field[c['counter']]['reference']
    for role,kind,warm,k in [('main','gru',c['warm_gru'],11),('mlp','mlp',c['warm_mlp'],19)]:
        configure(path/role,kind,role,2000003+g*997+k,5101009+g*907,
                  field[c['incumbent']]['reference'],name_ref,refs(field,c['families']),c['scores'],warm)

def compete(g):
    path=EVIDENCE/f'g{g:03d}';c=initialize(g);field=c['field'].copy()
    families={k:list(v) for k,v in c['families'].items()}
    for role,dirname in [('main','main'),('mlp','mlp'),('counter','exploiter')]:
        name=f'sg{g:03d}-{role}';field[name]=pin_model(path/dirname)
        # Failed counters remain immutable experimental records, and are evaluated but do not enlarge the training family.
        if role!='counter' or read(path/'counter.json')['confirmed']:
            families.setdefault('league '+role,[]).append(name)
    worker=Worker()
    try:tournament(worker,field,families,c['incumbent'],path/'tournament.json')
    finally:worker.close()

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('op',choices=['prepare','counter','compete']);p.add_argument('generation',type=int)
    a=p.parse_args();{'prepare':prep_exploiter,'counter':counter,'compete':compete}[a.op](a.generation)
