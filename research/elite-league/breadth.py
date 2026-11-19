"""Train the sumo gate across archived generations, and publish each defending or new winner."""
import argparse
import gzip
import json
import subprocess
import tempfile
from collections import Counter
from pathlib import Path
import champions
from adaptation import dump,freeze,ranking,sha
from audit import collect
from continuation import CONTROLS,DEST as PREVIOUS,known,labels,resolve
from historical_model import COVERAGE,EXPANDED
from history_cup import FIELD,counts,mirrors
from ladder_transfer import WT,HOLD,REFERENCE,plan
from native_trials import run_batch
from results import native
from run import HERE,ROOT,calendars,compile_model,java
from tournament import grid

DEST=HERE/'breadth-frozen'
EVIDENCE=HERE/'evidence/breadth'

def field():
    modern=known()
    for i in range(1,4):
        name=f'continuation-{i:02d}'
        modern[name+'-fit']=PREVIOUS/name/'contender.net'
    original=json.loads(COVERAGE.read_text())['legacy']
    additional=[n for n in json.loads(EXPANDED.read_text())['legacy'] if n not in original]
    return {'modern':modern,'movement':{'legacy-'+n:None for n in original},'expanded':{'legacy-'+n:None for n in additional}}

def screening(source,groups):
    results=[]
    for row in ranking(source):
        cohorts={}
        for name,foes in groups.items():
            rates=row['opponents']
            if not foes:raise ValueError('Empty league')
            weak=sorted(foes,key=lambda n:(rates[n],n))[:5]
            avg=sum(rates[n] for n in foes)/len(foes);tail=sum(rates[n] for n in weak)/len(weak)
            merit=(avg+tail+rates[weak[0]])/3
            cohorts[name]=dict(mean=avg,tail=tail,worst=rates[weak[0]],merit=merit,weakest=weak)
        merits=[v['merit'] for v in cohorts.values()];overall=.5*(min(merits)+sum(merits)/len(merits))
        results.append(dict(name=row['name'],merit=overall,mean=sum(v['mean'] for v in cohorts.values())/len(merits),groups=cohorts))
    return sorted(results,key=lambda r:(-r['merit'],-r['mean'],r['name']))

def merged(groups):
    opponents={n:path for group in groups.values() for n,path in group.items()}
    if len(opponents)!=sum(map(len,groups.values())):raise ValueError('Duplicate opponent in different leagues')
    return opponents

def attribution(src,groups):
    group_of={n:g for g,field in groups.items() for n in field};stats={}
    with gzip.open(src,'rt') as f:
        for line in f:
            r=line.split()
            if r[6]!='ok':continue
            counters=stats.setdefault(group_of[r[1]],Counter());counters['states']+=1
            a,b,w,unchanged=map(float,r[10:14])
            for k,x in zip(('population_points','table_points','wtap_points','unchanged_points'),(a,b,w,unchanged)):counters[k]+=x
            counters['table_better']+=b>a;counters['population_better']+=a>b
    return stats

def train(work):
    compile_model();work.mkdir(exist_ok=True,parents=True);DEST.mkdir(exist_ok=True);EVIDENCE.mkdir(exist_ok=True,parents=True)
    groups=field();active=PREVIOUS/'continuation-03/pair.net';prior=dict(CONTROLS,**{'continuation-03-fit':PREVIOUS/'continuation-03/contender.net'})
    sealed=calendars('development_selection_clocks');training=calendars('training_clocks')
    history=[]
    for i in (1,2):
        name=f'breadth-{i:02d}';stage=work/name;stage.mkdir(exist_ok=True)
        d=DEST/name;d.mkdir(exist_ok=True);manifest=d/'selection.json';opponents=merged(groups)
        source=stage/'labels.tsv.gz';history.append(source)
        if not manifest.exists():
            if not source.exists():labels(active,opponents,source,41875+i,training)
            mapping=stage/'groups.txt';freeze(mapping,''.join(f'{n} {g}\n' for g,field in groups.items() for n in field).encode())
            subprocess.run(java('ContinuationFit',active,stage/'nets','--groups',mapping,*history),cwd=ROOT,check=True)
            pool={p.stem:p for p in sorted((stage/'nets').glob('*.net'))}
            with tempfile.TemporaryDirectory(dir=work) as tmp:
                track=grid(Path(tmp),'screen',prior|pool,opponents,training[::2],mode='mouse')
                shortlisted=[r['name'] for r in screening(track,groups) if r['name'] in pool][:4]
                finalists=prior|{n:pool[n] for n in shortlisted}
                f=grid(Path(tmp),'title',finalists,opponents,sealed,mode='mouse')
                ranks=screening(f,groups);freeze(d/'development.jsonl.gz',f.read_bytes())
            winner=ranks[0]['name'];new=next(r['name'] for r in ranks if r['name'] in pool)
            which=next(r['name'] for r in ranks if r['name'] in {'adaptive','continuation-03-fit'} or r['name'] in pool or r['name'].startswith('breadth-'))
            freeze(d/'contender.net',pool[new].read_bytes());freeze(d/'pair.net',finalists[which].read_bytes())
            champions.install(name,resolve(winner,finalists),f'Three-league sumo tournament {i} winner ({winner})',name)
            champions.install(name+'-fit',pool[new],f'Three-league sumo training {i} winner ({new})',name,'counterfactual')
            dump(manifest,dict(winner=winner,challenger=new,top_pair=which,shortlist=shortlisted,groups={g:list(v) for g,v in groups.items()},
                               teaching=attribution(source,groups),ranking=ranks,inputs=sha(source)))
            print(name,winner,new,[(r['name'],round(r['merit'],3)) for r in ranks],flush=True)
        active=d/'pair.net';prior[name+'-fit']=d/'contender.net';groups['modern'][name+'-fit']=d/'contender.net'
    with tempfile.TemporaryDirectory(dir=work) as tmp:
        table=grid(Path(tmp),'final',prior,merged(groups),sealed,mode='mouse')
        ranked=screening(table,groups)
        dest=DEST/'development.jsonl.gz'
        if not dest.exists():freeze(dest,table.read_bytes())
        elif screening(dest,groups)!=ranked:raise ValueError('Development rank changed')
        winner=ranked[0]['name'];new=next(r['name'] for r in ranked if r['name'].startswith('breadth-'))
        champions.install('league-breadth',resolve(winner,prior),'Three-league sumo common tournament winner ('+winner+')','league-breadth')
        dump(DEST/'selection.json',dict(winner=winner,new=new,ranked=ranked))
        print('Common',winner,new,flush=True)

def validate(work):
    data=json.loads((DEST/'selection.json').read_text());new=data['new']
    spec=json.loads((FIELD/'field.json').read_text())
    groups=field();modern=groups['modern']
    for i in (1,2):modern[f'breadth-{i:02d}-fit']=DEST/f'breadth-{i:02d}/contender.net'
    modern['shift-sealed-04']=FIELD/'nets/shift-sealed-04.net'
    candidate=next(r for r in data['ranked'] if r['name']==new)
    opponents=list(dict.fromkeys(['wtap','hold',*(r['weakest'][0] for r in candidate['groups'].values()),'shift-sealed-04']))
    field_map=merged(groups)
    foes={n:field_map[n] for n in opponents}
    native_names={n:(spec[n]['native'] if n in spec else n.removeprefix('legacy-')) for n in foes}
    arms={'wtap':None,REFERENCE:CONTROLS['population'],'continuation-03-fit':modern['continuation-03-fit'],new:modern[new]}
    tapes=mirrors('breadth',work)
    if not (EVIDENCE/'prospective.jsonl.gz').exists():
        compile_model()
        with tempfile.TemporaryDirectory(dir=work) as tmp:
            f=grid(Path(tmp),'validation',arms,foes,tapes,mode='mouse');freeze(EVIDENCE/'prospective.jsonl.gz',f.read_bytes())
    roster=[WT if n=='wtap' else n for n in arms]
    trials=plan('ebroad',roster,list(native_names.values()),2);manifest=work/'breadth-native.json'
    run_batch(trials,manifest)
    bundle=EVIDENCE/'native.json.gz'
    if not bundle.exists():
        scores=work/'breadth-scores';collect(manifest,scores,False);native(manifest,scores,bundle)
    with gzip.open(bundle,'rt') as f:actual=counts(json.load(f)['matches'])
    with gzip.open(EVIDENCE/'prospective.jsonl.gz','rt') as f:rows=list(map(json.loads,f))
    for r in rows:r['opponent']=native_names[r['opponent']]
    model=counts(rows,True)
    winner=min(actual,key=lambda n:(-actual[n]['merit'],-actual[n]['mean'],n))
    champions.install('league-breadth-native',ROOT/'policies'/(winner+'.properties'),'Three-league native transfer tournament winner','league-breadth-native')
    dump(EVIDENCE/'native-results.json',dict(winner=winner,opponents=native_names,native=actual,model=model))
    print('Native',winner,flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('stage',choices=['train','validate']);p.add_argument('work',type=Path);a=p.parse_args()
    (train if a.stage=='train' else validate)(a.work)
