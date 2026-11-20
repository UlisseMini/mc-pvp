"""Frozen roles, draw distributions and final-checkpoint selections for one generation."""
import argparse
import sys
from train import configure
from league_tournament import *
from styles import freeze_assay

def read(path):return json.loads(path.read_text())

def ranked_parents(previous, ranking):
    # Only warm from ordinary PPO trainers with transferable physical-action heads.
    sources={}
    directories=[EVIDENCE/item for item in ('counter-trained-successors/mlp','counter-trained-successors/gru')]
    directories.extend(EVIDENCE/'robustness-001'/r for r in ('mlp','gru','gru-aux'))
    directories.extend(EVIDENCE/'robustness-001/weak-matchups'/r for r in ('original-mixture','weakest-mixture'))
    for gen in range(1,previous+1):
        folder=EVIDENCE/f'g{gen:03d}'
        directories.extend(folder/item for item in ('main','mlp','mentor-gru/ppo','mentor-mlp/ppo',
                           'frontier-generalists/generalist','frontier-generalists/response'))
    for d in directories:
        if all((d/n).exists() for n in ('config.json','trainer.pt','final.net')):
            sources[hashlib.sha256((d/'final.net').read_bytes()).hexdigest()]=(read(d/'config.json')['kind'],d/'trainer.pt')
    for receipt in (EVIDENCE/'fixed-adversaries').glob('*/result.json'):
        result=read(receipt)
        if not result['confirmed']:continue
        policy=result['policy'];checkpoint=ROOT/result['checkpoint']
        # A specialist can seed a generalist when it wins the broad tournament.
        # Early specialist optimizers used a different log format. Their MLP weights are compatible.
        if checkpoint.is_file() and (ROOT/policy['reference']).read_bytes().startswith(b'LEAGUE_MLP180.'):
            sources.setdefault(policy['sha256'],('mlp',checkpoint))
    selected={}
    for candidate in ranking['ranking']:
        name=candidate['name']
        entry=sources.get(ranking['field'][name]['sha256'])
        if entry and entry[0] not in selected:selected[entry[0]]=(name,entry[1])
    return selected

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
        if gate['confirmed'] and gate['incumbent']==inc:counter=gate['challenger']
        else:counter=last['counter']
        # Supplemental models join as a complete predeclared set. Failed response searches stay evaluation-only.
        from secondary import enrollment
        extra,groups=enrollment(g)
        field.update(extra)
        for family,names in groups.items():families.setdefault(family,[]).extend(names)
        verdict=prev/'plateau/verdict.json'
        if verdict.exists():
            outcome=read(verdict)
            answers={n:v for n,v in outcome.get('responses',{}).items() if v['opponent']==inc}
            for n,v in answers.items():
                field[n]={k:v[k] for k in ('reference','sha256')}
                families.setdefault('doubled-budget responses',[]).append(n)
            if answers:counter=max(answers,key=lambda n:answers[n]['score'])
            known=outcome.get('confirmed_archive_weakness')
            if known and known['opponent']==inc and (not answers or known['score']>answers[counter]['score']):
                counter=known['name']
        scores=next(r['family'] for r in rounds['ranking'] if r['name']==f'sg{g-1:03d}-main')
        warm_r=prev/'main/trainer.pt';warm_m=prev/'mlp/trainer.pt'
    if g>=3:
        parents=ranked_parents(g-1,rounds)
        warm_r=parents['gru'][1];warm_m=parents['mlp'][1]
    freeze_assay(g,folder)
    result=dict(generation=g,incumbent=inc,counter=counter,field=field,families=families,scores=scores,
                warm_gru=str(warm_r) if warm_r else None,warm_mlp=str(warm_m) if warm_m else None)
    if g>=3:
        from adversary import choose
        # Once the titleholder and last winner have a measured response, attack a new
        # counter, then the best-ranked unsearched learned opponent from the archive.
        previous_winner=rounds['winner']
        new_threats=families.get('65k single-opponent PPO',[])
        ranked=[r['name'] for r in rounds['ranking'] if r['name'] not in ('wtap','hold','legacy-wtap-clickphase')]
        result['adversary_target']=choose(field,[inc,previous_winner,*new_threats,*ranked])
        result['adversary_budget']=65536
        result['warm_sources']={kind:name for kind,(name,_) in parents.items()}
    write_json(path,result,fixed=True)
    return result

def refs(d,families):return {k:[d[n]['reference'] for n in members] for k,members in families.items()}

def prep_exploiter(g):
    path=EVIDENCE/f'g{g:03d}';c=initialize(g);field=c['field']
    if 'adversary_target' in c:
        from adversary import obtain
        result=obtain(field[c['adversary_target']],Path(c['warm_mlp']),1700321+(g-1)*4001)
        freeze(path/'exploiter/current.net',(ROOT/result['policy']['reference']).read_bytes())
        write_json(path/'exploiter/budget-result.json',result,fixed=True)
        return
    configure(path/'exploiter','gru','exploiter',1700321+(g-1)*4001,1700378+(g-1)*4001,
              field[c['incumbent']]['reference'],field[c['counter']]['reference'],refs(field,c['families']),c['scores'],c['warm_gru'] if g%2==0 else None)

def counter(g):
    path=EVIDENCE/f'g{g:03d}';c=initialize(g);field=c['field'].copy();name=f'sg{g:03d}-counter'
    field[name]=pin_model(path/'exploiter')
    worker=Worker()
    try:
        target=c.get('adversary_target',c['incumbent'])
        if 'adversary_target' in c:
            from adversary import gate as budget_gate
            gate=budget_gate(read(path/'exploiter/budget-result.json'),name,target)
        else:gate=counter_gate(worker,field,name,target)
        # Count distinct payoff profiles against other counters to the same champion.
        # A re-found vulnerability does not reset the plateau counter solely because the weights differ.
        previous=[read(p) for p in sorted(EVIDENCE.glob('g*/counter.json')) if p.parent!=path and read(p)['confirmed'] and read(p)['incumbent']==target]
        reference=[c['counter']]+[r['challenger'] for r in previous]
        if 'adversary_target' in c:
            # A receipt reused from the archive cannot be counted as a novel exploiter.
            reference.extend(n for n,v in c['field'].items() if v['sha256']==field[name]['sha256'])
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
        gate['novel']=bool(gate['confirmed'] and not any(field[n]['sha256']==field[name]['sha256'] for n in reference) and all(any(v[1]>.05 or v[2]<-.05 for v in cell.values()) for cell in novelty.values()))
        write_json(path/'counter.json',gate,fixed=True)
    finally:worker.close()
    name_ref=field[name]['reference'] if gate['confirmed'] else field[c['counter']]['reference']
    threats=None
    if 'adversary_target' in c:
        from adversary import STORE
        accepted={read(r)['policy']['sha256'] for r in STORE.glob('*/result.json') if read(r)['confirmed']}
        threats=[name_ref]+[v['reference'] for v in c['field'].values() if v['sha256'] in accepted]
    for role,kind,warm,k in [('main','gru',c['warm_gru'],11),('mlp','mlp',c['warm_mlp'],19)]:
        configure(path/role,kind,role,2000003+g*997+k,5101009+g*907,
                  field[c['incumbent']]['reference'],name_ref,refs(field,c['families']),c['scores'],Path(warm).relative_to(ROOT) if g>=3 else warm,threats=threats)

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
