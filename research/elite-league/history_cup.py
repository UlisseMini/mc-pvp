"""Compare the strongest contenders across the archived roster and publish the tournament winner."""
import argparse
from collections import defaultdict
import gzip
import fcntl
import json
from pathlib import Path
import sys
from adaptation import BASELINES,fields,properties,dump,freeze,sha
from audit import collect
import champions
from ladder_transfer import WT,HOLD,REFERENCE,models,native_install,plan
from native_trials import run_batch
from results import native
from run import HERE,ROOT,compile_model
from tournament import grid

FIELD=HERE/'adaptive-field'
FROZEN=HERE/'adaptive-frozen'
CUP=HERE/'evidence/history-cup'

def read(p):return json.loads(p.read_text())
def data(p):
    with gzip.open(p,'rt') as f:return json.load(f)
def strength(points):
    ordered=sorted(points.values());mean=sum(ordered)/len(ordered);weakest=ordered[0]
    tail=sum(ordered[:5])/min(5,len(ordered))
    return dict(mean=mean,tail=tail,worst=weakest,merit=(mean+tail+weakest)/3,
                below_half=sum(v<.5 for v in ordered),at_half=sum(v==.5 for v in ordered))

def candidates():
    frozen=HERE/'ladder-frozen';_,paths=models(frozen);winner=read(frozen/'native-choice.json')['winner']
    adaptive=read(FROZEN/'selection.json')['challenger']
    return {WT:None,REFERENCE:paths[REFERENCE],winner:paths.get(winner),adaptive:FROZEN/'challenger.net'}

def rosters(arms=None):
    arms=candidates() if arms is None else arms
    mapped=fields(FIELD,'all');spec=read(FIELD/'field.json');opps={}
    for n,p in mapped.items():opps[spec[n]['native']]=p
    for i in range(1,5):
        for track in ('pair','recurrent'):
            opps[f'adapt-{i:02d}-{track}']=FROZEN/f'round-{i:02d}/{track}.net'
    # Include all arms as opponents even if a later tournament promotes a previously unpublished strategy.
    # Prefer the actual entrant name so that the head-to-head cells survive deduplication.
    # A non-network opponent is identified by its controller name, never by an empty weight hash.
    seen={};unique={};aliases={}
    for name,path in (arms|opps).items():
        identity=('network',sha(path)) if path else ('builtin',name)
        representative=seen.setdefault(identity,name)
        aliases[name]=representative
        if representative==name:unique[name]=path
    for settings in spec.values():
        for alias in settings.get('aliases',[]):aliases.setdefault(alias,aliases[settings['native']])
    return arms,unique,aliases

def model_aliases(field):
    inverse={v:k for k,v in BASELINES.items()}
    return {inverse.get(n,n):p for n,p in field.items()}

def mirrors(tag,work):
    with (work/(tag+'-export.lock')).open('a') as lock:
        fcntl.flock(lock.fileno(),fcntl.LOCK_EX)
        return _mirrors(tag,work)

def _mirrors(tag,work):
    # Donor mirrors are new independent matches after all contender weights are frozen.
    from importlib.util import spec_from_file_location,module_from_spec
    module=spec_from_file_location('history_mirrors',ROOT/'research/sumo-rl/native.py')
    donor=module_from_spec(module);module.loader.exec_module(donor)
    src=work/(tag+'-clocks.json');out=work/(tag+'-clocks')
    trials=donor.plan('elite-'+tag+'-sealed',True)
    if tag.startswith('mmc13-'):
        for t in trials.values():t.update(diameter=13,start_gap=8,aim_input='mouse',command=t['command']+' aiminput=mouse')
    run_batch(trials,src)
    if len(list(out.glob('*.txt.gz')))!=8:
        import subprocess
        subprocess.run([sys.executable,str(ROOT/'research/sumo-rl/calendars.py'),str(out),'--frozen-manifest',str(src)],check=True)
    files=sorted(out.glob('*.txt.gz'))
    if len(files)!=8:raise ValueError('Expected eight independent mirror clocks')
    dest=CUP/tag/'clocks';dest.mkdir(exist_ok=True,parents=True)
    for p in files:freeze(dest/p.name,p.read_bytes())
    freeze(dest/'requests.json.gz',gzip.compress(src.read_bytes(),mtime=0))
    return files

def counts(rows,model=False):
    groups=defaultdict(lambda:defaultdict(list))
    strata=defaultdict(lambda:defaultdict(lambda:defaultdict(list)))
    for r in rows:
        a=r['arm'];o=r['opponent']
        if model:a=BASELINES.get(a,a);o=BASELINES.get(o,o)
        score=r['score'] if model else (1 if r['win'] else .5 if r['draw'] else 0)
        groups[a][o].append(score)
        strata[a][o][f"{r['rtt']}/{r['side']}"].append(score)
    result={}
    for a,vs in groups.items():
        rates={o:sum(s)/len(s) for o,s in vs.items()}
        result[a]=dict(**strength(rates),opponents={o:dict(points=sum(s),n=len(s),
            strata={k:[sum(values),len(values)] for k,values in strata[a][o].items()}) for o,s in vs.items()})
    return result

def summarize_bundle(bundle,model):
    native_rows=data(bundle)['matches']
    with gzip.open(model,'rt') as f:sim=list(map(json.loads,f))
    return dict(native=counts(native_rows),model=counts(sim,True),
                native_sha256=sha(bundle),model_sha256=sha(model))

def execute(tag,arms,opps,trials,files,work):
    dest=CUP/tag;dest.mkdir(exist_ok=True,parents=True)
    bundle=dest/'native.json.gz';sim=dest/'model.jsonl.gz';folder=work/(tag+'-validation')
    native_install({n:p for n,p in (arms|opps).items() if p})
    if not sim.exists():
        compile_model();folder.mkdir(exist_ok=True)
        result=grid(folder,'regulation',model_aliases(arms),model_aliases(opps),files,mode='mouse')
        freeze(sim,result.read_bytes())
    manifest=work/(tag+'-native.json')
    if not bundle.exists():
        run_batch(trials,manifest)
        scores=work/(tag+'-scores');collect(manifest,scores,False);native(manifest,scores,bundle)
    ranking=summarize_bundle(bundle,sim);dump(dest/'results.json',ranking)
    return ranking

def stage(work):
    arms,opps,aliases=rosters();CUP.mkdir(exist_ok=True,parents=True)
    dump(CUP/'opponents.json',dict(opponents=list(opps),aliases=aliases))
    dump(CUP/'entrants.json',{n:(sha(p) if p else sha(ROOT/'policies'/(n+'.properties'))) for n,p in arms.items()})
    tapes=mirrors('history',work)
    ranking=execute('history',arms,opps,plan('ehist',list(arms),list(opps),1),tapes,work)
    by=ranking['native'];order=sorted(by,key=lambda n:(-by[n]['merit'],-by[n]['mean'],n));best=order[0]
    champ=arms[best] or ROOT/'policies'/(best+'.properties')
    champions.install('league-history',champ,'Historical roster tournament winner','league-history')
    # A champion in a sparse tournament remains playable regardless of later confirmation.
    challengers=[n for n in order if n!=WT]
    selected=challengers[0]
    cells=by[selected]['opponents']
    weakest=sorted(cells,key=lambda n:(cells[n]['points']/cells[n]['n'],n))
    test=list(dict.fromkeys(aliases[n] for n in [WT,HOLD,*arms,*weakest[:5],'shift-sealed-01','shift-sealed-02','shift-sealed-03','shift-sealed-04']))
    dump(CUP/'selection.json',dict(winner=best,challenger=selected,opponents=test,source=sha(CUP/'history/results.json')))

def tour_final(work):
    decision=read(CUP/'selection.json');arms,field,aliases=rosters();opps={n:field[n] for n in decision['opponents']}
    tapes=mirrors('history-final',work)
    ranks=execute('history-final',arms,opps,plan('ehfinal',list(arms),list(opps),4),tapes,work)
    candidate=decision['challenger']
    # Both possible prior title holders played this tournament. Read the published title at confirmation.
    incumbent=properties(ROOT/'policies/best-sumo.properties')['title_source']
    if incumbent not in arms:raise ValueError('Reigning champion missing from the frozen comparison: '+incumbent)
    inc=incumbent
    by=ranks['native'];dev=read(CUP/'history/results.json')['native'];actual=ranks['model']
    ordered=sorted(by,key=lambda n:(-by[n]['merit'],-by[n]['mean'],n));winner=ordered[0]
    champions.install('league-history-final',arms[winner] or ROOT/'policies'/(winner+'.properties'),
                      'Historical roster confirmation tournament winner','league-history-final')
    def rate(a,n):
        s=by[a]['opponents'][aliases[n]];return s['points']/s['n']
    passes=(candidate==winner and dev[candidate]['merit']>dev[WT]['merit'] and by[candidate]['merit']>by[WT]['merit']
            and actual[candidate]['merit']>actual[WT]['merit'] and rate(candidate,WT)>.5
            and (candidate==inc or (by[candidate]['mean']>=by[inc]['mean'] and by[candidate]['merit']>=by[inc]['merit']
                 and actual[candidate]['merit']>=actual[inc]['merit'] and rate(candidate,inc)>=.5)))
    dump(CUP/'title.json',dict(winner=winner,challenger=candidate,incumbent=inc,advanced=passes,
                               recommended=candidate if passes else inc,field=decision['opponents'],source=sha(CUP/'history-final/results.json')))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('cmd',choices=['stage','final']);p.add_argument('work',type=Path);a=p.parse_args()
    (stage if a.cmd=='stage' else tour_final)(a.work)
    if a.cmd=='final':
        from reigning import publish
        receipt=CUP/'title.json';decision=read(receipt)
        publish('best-sumo',decision['recommended'],'Sumo title holder after the historical tournament and confirmation',receipt)
