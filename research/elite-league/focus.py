"""Target the W-tap and weakest distinct matchups, while retaining the complete modern field."""
import argparse
import base64
import struct
from collections import defaultdict
import gzip
import itertools
import json
from pathlib import Path
import random
import subprocess
import tempfile
import champions
from adaptation import dump,freeze,properties,sha
from audit import collect
from breadth import field as all_groups
from history_cup import FIELD,counts,mirrors
from ladder_transfer import WT,HOLD,REFERENCE,plan
from native_trials import run_batch
from results import native
from run import HERE,ROOT,calendars,compile_model,java
from tournament import roster,grid

SERIES='focus'
TARGET='adapt-01-pair'
CURRENT='continuation-03-fit'
REFERENCE_MODEL=HERE/'robust-frozen/population.net'
DEST=HERE/'focus-frozen'
EVIDENCE=HERE/'evidence/focus'
BASE={'wtap':None,REFERENCE:REFERENCE_MODEL,CURRENT:HERE/'continuation-frozen/continuation-03/contender.net'}

def identity(n,p):
    return sha(p) if p else 'builtin-'+n

def names(n):
    specs=json.loads((FIELD/'field.json').read_text())
    return specs[n]['native'] if n in specs else n

def source(n,path):
    if path:return path
    return ROOT/'policies'/(names(n)+'.properties')

def unique(field):
    seen={};candidates={}
    for n,p in field.items():
        key=identity(n,p)
        if key not in seen:seen[key]=n;candidates[n]=p
    return candidates

def score(data,foes,targeted_only=False):
    by=defaultdict(lambda:defaultdict(list));point=defaultdict(lambda:defaultdict(list))
    with gzip.open(data,'rt') as f:
        for r in map(json.loads,f):
            a=r['arm'];o=r['opponent'];by[a][o].append(r['score'])
            if o=='wtap':point[a][str(r['rtt'])+r['side']].append(r['score'])
    out=[]
    for a,opps in by.items():
        if set(opps)!=set(foes):raise ValueError('Lost matchup: '+a)
        rates={n:sum(v)/len(v) for n,v in opps.items()};weak=sorted(rates,key=lambda n:(rates[n],n))
        avg=sum(rates.values())/len(rates);tail=sum(rates[o] for o in weak[:5])/min(5,len(rates))
        hard=min(rates[TARGET],rates[CURRENT]);w=rates['wtap']
        per={k:sum(v)/len(v) for k,v in point[a].items()}
        objective=.35*w+.45*hard+.20*(w+rates[TARGET]+rates[CURRENT])/3
        if not targeted_only:objective=.8*objective+.2*(avg+tail+rates[weak[0]])/3
        out.append(dict(name=a,qualified=w>=.90 and min(per.values())>=.75,merit=objective,field_mean=avg,
                        weakest=weak[:7],wtap_cells=per,rates=rates))
    return sorted(out,key=lambda r:(not r['qualified'] if not targeted_only else False,-r['merit'],-r['field_mean'],r['name']))

def play_model(out,arms,opps,tapes):
    if out.exists():return
    compile_model()
    with tempfile.TemporaryDirectory(dir=out.parent) as tmp:
        result=grid(Path(tmp),'matches',arms,opps,tapes,mode='mouse')
        freeze(out,result.read_bytes())

def physical(tag,setup,work,opps,arms,model):
    manifest=work/('focus-'+tag+'-native.json')
    folder=EVIDENCE/tag;folder.mkdir(exist_ok=True,parents=True)
    if not (folder/'schedule.json').exists():dump(folder/'schedule.json',dict(arms={n:identity(n,p) for n,p in arms.items()},opponents=list(opps),matches=len(setup)))
    run_batch(setup,manifest);bundle=folder/'native.json.gz'
    if not bundle.exists():
        scores=work/('focus-'+tag+'-scores');collect(manifest,scores,False);native(manifest,scores,bundle)
    with gzip.open(bundle,'rt') as f:nrows=json.load(f)['matches']
    with gzip.open(model,'rt') as f:forecast=list(map(json.loads,f))
    for r in forecast:r['arm']=names(r['arm']);r['opponent']=names(r['opponent'])
    actual=counts(nrows);simulation=counts(forecast,True)
    wtap_results={}
    for arm,summary in actual.items():
        relevant=[r for r in nrows if r['arm']==arm and r['opponent']==WT]
        by={f'{ping}{s}':[r for r in relevant if r['rtt']==ping and r['side']==s] for ping in (0,80) for s in 'ab'}
        wtap_results[arm]=dict(wins=sum(r['win'] for r in relevant),draws=sum(r['draw'] for r in relevant),total=len(relevant),
                              cells={k:[sum(r['win'] for r in games),sum(r['draw'] for r in games),len(games)] for k,games in by.items()})
    winner=min(actual,key=lambda n:(-actual[n]['merit'],-actual[n]['mean'],n))
    champions.install('focus-'+tag,ROOT/'policies'/(winner+'.properties'),'W-tap and hard-matchup Minecraft tournament winner ('+winner+')','focus-'+tag)
    dump(folder/'results.json',dict(winner=winner,native=actual,simulation=simulation,wtap_outcomes=wtap_results))
    print(tag,winner,wtap_results,flush=True)

def baseline(work):
    folder=EVIDENCE/'baseline';folder.mkdir(exist_ok=True,parents=True)
    opps={'wtap':None,TARGET:HERE/'adaptive-frozen/round-01/pair.net'}
    sim=folder/'development.jsonl.gz';play_model(sim,BASE,opps,calendars('development_selection_clocks'))
    physical('baseline',plan('efbase',[names(n) for n in BASE],[names(n) for n in opps],6),work,opps,BASE,sim)

def stage1(work):
    opponents=all_groups()['modern'];contenders=unique(BASE|opponents)
    train=calendars('training_clocks');dev=calendars('development_selection_clocks')
    top={n:opponents[n] for n in ('wtap',TARGET,CURRENT)}
    d=DEST/'round-01';d.mkdir(exist_ok=True,parents=True)
    play_model(d/'screen.jsonl.gz',contenders,top,train)
    screened=score(d/'screen.jsonl.gz',top,True)
    cut={r['name']:contenders[r['name']] for r in screened[:6]}
    finalists=unique(BASE|cut)
    play_model(d/'development.jsonl.gz',finalists,opponents,dev)
    ranked=score(d/'development.jsonl.gz',opponents)
    champ=ranked[0]['name'];alias=SERIES+'-01'
    champions.install(alias,source(champ,finalists[champ]),'First hard-matchup sumo tournament winner ('+champ+')',alias)
    dump(d/'selection.json',dict(winner=champ,entrant_sha256={n:identity(n,p) for n,p in contenders.items()},
                shortlist=list(finalists),ranking=ranked,training=screened))
    print(alias,champ,[(r['name'],r['rates']['wtap'],r['rates'][TARGET],round(r['merit'],3)) for r in ranked],flush=True)

def track(path):
    if not path:return None
    family,encoded=path.read_text().strip().split('.',1)
    if family=='TACTIC':
        code=round(struct.unpack('>f',base64.b64decode(encoded)[:4])[0])
        return {10:'table',11:'search'}.get(code,'rule')
    return 'neural' if family in ('MLP','RECURRENT','LINEAR','HYBRID') else None

def load_pool(stage):
    return {p.stem:p for p in sorted((stage/'checkpoints').glob('*.net'))}

def previous_arms(i,modern):
    first=json.loads((DEST/'round-01/selection.json').read_text());all_arms=unique(BASE|modern)
    arms={n:all_arms[n] for n in first['shortlist']}
    for round_number in range(2,i):
        d=DEST/f'round-{round_number:02d}'
        for kind in ('explicit','neural'):
            arms[f'{SERIES}-{round_number:02d}-'+kind]=d/(kind+'.net')
    return arms

def fit_round(i,work):
    from ladder import roster_fields
    folder=DEST/f'round-{i:02d}';folder.mkdir(exist_ok=True,parents=True)
    work=work/f'{SERIES}-{i:02d}';work.mkdir(exist_ok=True,parents=True)
    if (folder/'selection.json').exists():return
    modern=all_groups()['modern'];prior=previous_arms(i,modern)
    one=json.loads((DEST/'round-01/selection.json').read_text())
    earlier=json.loads((DEST/f'round-{i-1:02d}/selection.json').read_text())
    first=earlier['ranking'][0];known=unique(BASE|modern)|prior
    difficult=[TARGET,CURRENT]
    if i>2:
        ident=identity(first['name'],known[first['name']])
        weak=next(n for n in first['weakest'] if identity(n,modern[n])!=ident)
        if weak not in difficult and weak!='wtap':difficult.append(weak)
    targets={n:modern[n] for n in ['wtap',*difficult]}
    controls=unique(BASE|prior)
    order=[r['name'] for r in one['training']]
    if i>2:order=[f'{SERIES}-{i-1:02d}-explicit',f'{SERIES}-{i-1:02d}-neural',*order]
    warm={}
    for k in ('rule','table','search','neural'):
        chosen=unique({n:known[n] for n in order if n in known and track(known[n])==k})
        for n,path in list(chosen.items())[:4]:warm[k+'-'+n]=path
        if k=='rule' and not chosen:
            # The distinct current field has tables and searchers. Recover the archived rule champion for this track.
            warm['rule-archived']=HERE/'ladder-frozen/elite-common-rule.net'
    if {n.split('-')[0] for n in warm}!= {'rule','table','search','neural'}:raise ValueError('Missing warm-start family')
    tapes=calendars('training_clocks');compile_model()
    with tempfile.TemporaryDirectory(dir=work) as temp:
        t=Path(temp);targets_file=t/'targets';roster_fields(targets_file,targets,{n:n for n in targets},{})
        # Sample the bot's own opening, exchange and near-edge observations from lost training bouts.
        example_arm=known[first['name']] or BASE[CURRENT];roster(t/'observer',{'incumbent':example_arm})
        feedback=work/'lost-states.txt'
        if not feedback.exists():
            f=t/'losses'
            subprocess.run(java('Ladder','mouse-misses',t/'observer',targets_file,f,*tapes),cwd=ROOT,check=True)
            freeze(feedback,f.read_bytes())
        group={n:(n if n in targets else 'remainder') for n in modern}
        roster_fields(t/'field',modern,group,{});roster(t/'warm',warm)
        complete=work/'checkpoints/neural-g063.net'
        if not complete.exists():
            # A partial run gets its own directory; fully published stages never change.
            checkpoints=t/'checkpoints'
            subprocess.run(java('Ladder','focus',checkpoints,5100+i,64,24,t/'field',t/'warm',
                                ROOT/'build/elite-league/human-frames/fit.txt',feedback,*tapes),cwd=ROOT,check=True)
            if (work/'checkpoints').exists():raise ValueError('Previous incomplete checkpoint directory')
            checkpoints.rename(work/'checkpoints')
    pool=load_pool(work)
    narrow=folder/'screen.jsonl.gz';play_model(narrow,controls|pool,{n:modern[n] for n in ('wtap',TARGET,CURRENT)},tapes)
    screened=score(narrow,('wtap',TARGET,CURRENT),True)
    shortlisted={}
    for group in ('explicit','neural'):
        entries={r['name']:pool[r['name']] for r in screened if r['name'] in pool and (track(pool[r['name']])=='neural')==(group=='neural')}
        shortlisted.update(dict(list(unique(entries).items())[:4]))
    finalists=controls|shortlisted
    out=folder/'development.jsonl.gz';play_model(out,finalists,modern,calendars('development_selection_clocks'))
    ranked=score(out,modern);winner=ranked[0]['name'];alias=f'{SERIES}-{i:02d}'
    champions.install(alias,source(winner,finalists[winner]),f'Focused sumo tournament {i} winner ({winner})',alias)
    published={}
    for group in ('explicit','neural'):
        n=next(r['name'] for r in ranked if r['name'] in shortlisted and (track(shortlisted[r['name']])=='neural')==(group=='neural'))
        freeze(folder/(group+'.net'),shortlisted[n].read_bytes())
        champions.install(alias+'-'+group,folder/(group+'.net'),f'Focused sumo {i} {group} winner ({n})',alias,group)
        published[n]=alias+'-'+group
    # Expose stable live aliases to the following round, with training checkpoint IDs retained separately.
    for r in ranked:
        if r['name'] in published:r.update(checkpoint=r['name'],name=published[r['name']])
    best=ranked[0]['name'];winner_path=finalists[winner]
    if winner_path:freeze(folder/'winner.net',winner_path.read_bytes())
    dump(folder/'selection.json',dict(winner=best,winning_checkpoint=winner,targets=list(targets),ranking=ranked,warm_names=list(warm),
                                    training_feedback_samples=len(feedback.read_text().splitlines()),trained_checkpoint_sha256={n:sha(p) for n,p in pool.items()}))
    print(alias,best,[(r['name'],r['rates']['wtap'],r['rates'][TARGET],round(r['merit'],3)) for r in ranked[:5]],flush=True)

def confirmation(work):
    folder=EVIDENCE/'confirmation';folder.mkdir(exist_ok=True,parents=True)
    last=DEST/'round-03';d=json.loads((last/'selection.json').read_text());modern=all_groups()['modern']
    n=d['winner'];weights=(last/'winner.net') if (last/'winner.net').exists() else None
    # Every finalist is duelable. Collapse equal controls before a mirror is scheduled.
    winner_name=names(n)
    native_source=properties(ROOT/'policies'/(winner_name+'.properties'))
    if weights and native_source.get('duel_network')!=weights.read_text().strip():raise ValueError('Winner binding differs: '+n)
    new=next(r['name'] for r in d['ranking'] if r['name'].startswith('focus-') and r['name'].endswith(('-explicit','-neural')))
    _,round_number,kind=new.split('-')
    extra=DEST/('round-'+round_number)/(kind+'.net')
    arms=unique(BASE|{n:weights,new:extra})
    weak=next(o for o in d['ranking'][0]['weakest'] if identity(o,modern[o])!=identity(n,weights))
    repeats={'wtap':12,TARGET:6,CURRENT:6}
    if weak not in repeats:repeats[weak]=4
    foes={o:modern[o] for o in repeats};arm_names=[names(a) for a in arms]
    dump(folder/'frozen.json',dict(arms={a:identity(a,p) for a,p in arms.items()},opponent_repeats=repeats,selected=n))
    tapes=mirrors('focus-final',work);model=folder/'prospective.jsonl.gz';play_model(model,arms,foes,tapes)
    trials={}
    for o,k in repeats.items():trials.update(plan('effin',arm_names,[names(o)],k))
    schedule=list(trials.items());random.Random(788909).shuffle(schedule)
    physical('confirmation',dict(schedule),work,foes,arms,model)
    evaluated=json.loads((folder/'results.json').read_text());decisions={}
    with gzip.open(model,'rt') as f:predictions=list(map(json.loads,f))
    def rate(b,arm,o):
        c=b[arm]['opponents'][o]
        return c['points']/c['n']
    for a in arms:
        k=names(a);wt=evaluated['wtap_outcomes'][k]
        sim_w=[r['score'] for r in predictions if r['arm']==a and r['opponent']=='wtap']
        if len(sim_w)!=32 or wt['total']!=48 or any(v[2]!=12 for v in wt['cells'].values()):
            raise ValueError('Unequal W-tap qualification exposure')
        clear=wt['wins']>=.9*wt['total'] and min(v[0] for v in wt['cells'].values())>=10 and sim_w.count(1)>=.9*len(sim_w)
        hard={tag:rate(evaluated[tag],k,TARGET)-rate(evaluated[tag],CURRENT,TARGET) for tag in ('native','simulation')}
        repair=all(v>0 for v in hard.values())
        decisions[k]=dict(clear_wtap_gate=clear,improved_hard_opponent=repair,hard_point_advantage_over_prior=hard,
                          simulation_wtap=dict(wins=sim_w.count(1),draws=sim_w.count(.5),n=len(sim_w)))
    dump(folder/'qualification.json',dict(prior=CURRENT,target=TARGET,results=decisions))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('stage',choices=['baseline','screen','fit','confirm']);p.add_argument('work',type=Path)
    a=p.parse_args()
    if a.stage=='fit':
        for i in (2,3):fit_round(i,a.work)
    else:{'baseline':baseline,'screen':stage1,'confirm':confirmation}[a.stage](a.work)
