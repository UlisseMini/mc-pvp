"""Score existing fighters from one established-combo state per trial, across different defenders."""
import argparse
from collections import defaultdict
import gzip
import itertools
import json
import math
from pathlib import Path
import random
import shutil
import subprocess
import tempfile
import champions
from adaptation import dump,freeze,properties,sha
from audit import collect
from ladder_transfer import WT,HOLD,REFERENCE
from native_trials import run_batch
from results import model_game,model_boxes,collect_maintenance,native
from run import HERE,ROOT,calendars,compile_model,java
from tournament import roster

DEST=HERE/'boxing-breadth-frozen'
RESULT=HERE/'evidence/boxing-breadth'
def arms():
    return {'affine':HERE/'boxing-frozen/specialist.net','student':HERE/'boxing-distilled-frozen/student.net',
            'population':HERE/'robust-frozen/population.net','adaptive':HERE/'adaptive-frozen/challenger.net',
            'continuation':HERE/'continuation-frozen/continuation-03/contender.net'}
def rivals():
    return {'wtap':(WT,None),'hold':(HOLD,None),'trade':('trade-wtap',None),'zest-strafe':('research-zest-strafe',None),
            'population':(REFERENCE,arms()['population']),'anti-hybrid':('elite-br-hybrid',HERE/'challenge-frozen/anti-hybrid.net'),
            'table':('elite-game-explicit',HERE/'empirical-game-frozen/explicit.net'),'adaptive':('adapt-04-pair',arms()['adaptive']),
            'continuation':('continuation-03-fit',arms()['continuation'])}
FOES=('wtap','hold','trade','population','table','adaptive')

def simulate(challengers,opponents,tapes,out):
    compile_model()
    with tempfile.TemporaryDirectory() as temp:
        t=Path(temp);roster(t/'arms',challengers);roster(t/'opponents',opponents)
        with subprocess.Popen(java('BoxingBreadth',t/'arms',t/'opponents',*tapes),stdout=subprocess.PIPE) as process:
            with (t/'output.gz').open('wb') as raw,gzip.GzipFile(fileobj=raw,mode='wb',mtime=0) as f:shutil.copyfileobj(process.stdout,f)
            if process.wait():raise ValueError('Unchanged boxing prefix or terminal hit sequence diverged')
        freeze(out,(t/'output.gz').read_bytes())

def summarize(file):
    with gzip.open(file,'rt') as f:raw=list(map(json.loads,f))
    by=defaultdict(list);common={};scores=defaultdict(lambda:defaultdict(list))
    for r in raw:
        by[r['opponent']].append(r);key=(r['opponent'],r['clock'],r['side'],r['rtt'])
        baseline={k:[cut['landmark'],cut['fork_at'],cut['committed'],cut['wtap']] for k,cut in r['forks'].items()}
        if key in common and common[key]!=baseline:raise AssertionError('Contenders used different W-tap establishment')
        common[key]=baseline
        cut=model_game(r)['forks'].get('3')
        if cut:
            w=cut['wtap']['active']['completed_cycles'];c=cut['rl']['active']['completed_cycles']
            scores[r['arm']][r['opponent']].append(math.log1p(c)-math.log1p(w)+.15*((c>w)-(c<w)))
    ranking=[dict(name='wtap',merit=0.,mean=0.)]
    for name,vs in scores.items():
        rates={o:sum(xs)/len(xs) for o,xs in vs.items()};mean=sum(rates.values())/len(rates)
        ranking.append(dict(name=name,mean=mean,merit=.5*mean+.5*min(rates.values()),opponent_merit=rates))
    ranking.sort(key=lambda r:(-r['merit'],-r['mean'],r['name']))
    outcomes={o:model_boxes(rows) for o,rows in by.items()}
    return dict(ranking=ranking,fully_shared_prefixes=len(common),opponents=outcomes,model_rows=len(raw))

def choose():
    DEST.mkdir(exist_ok=True);RESULT.mkdir(exist_ok=True);field=arms();rival={n:v[1] for n,v in rivals().items()}
    out=RESULT/'development.jsonl.gz'
    if not out.exists():simulate(field,rival,calendars('development_selection_clocks'),out)
    stats=summarize(out);ranking=stats['ranking'];winner=ranking[0]['name'];best=next(r['name'] for r in ranking if r['name']!='wtap')
    source=ROOT/'policies'/(WT+'.properties') if winner=='wtap' else field[winner]
    champions.install('boxing-03',source,'Nine-defender boxing maintenance tournament winner ('+winner+')','boxing-03',mode='boxing')
    champions.install('boxing-03-contender',field[best],'Nine-defender boxing challenger ('+best+')','boxing-03','maintenance','boxing')
    dump(DEST/'selection.json',dict(winner=winner,challenger=best,weights=sha(field[best]),ranking=ranking))
    dump(RESULT/'development.json',stats)
    print(winner,[(r['name'],round(r['merit'],4)) for r in ranking],flush=True)

def defend(name,k):
    policy=rivals()[name][0];source=ROOT/'policies'/(policy+'.properties')
    alias=f'breadth-{name}-wake{k}';lines=[l for l in source.read_text().splitlines() if not l.startswith(('description=','league_','research_landmark_k=','click_start_ms='))]
    config='\n'.join(lines+['description=Two-second delayed boxing drill defender '+name,'click_start_ms=2000','research_landmark_k='+str(k)])+'\n'
    freeze(ROOT/'policies'/(alias+'.properties'),config.encode());return alias

def validation(work):
    selection=json.loads((DEST/'selection.json').read_text());best=selection['challenger']
    donors=sorted((work/'box-student-clocks').glob('*.txt.gz'))
    if len(donors)!=8:raise ValueError('Expected the eight reserved boxing donor calendars')
    raw=RESULT/'prospective.jsonl.gz'
    if not raw.exists():simulate({best:arms()[best]},{n:rivals()[n][1] for n in FOES},donors,raw)
    dump(RESULT/'prospective.json',summarize(raw))
    trials=[];name='boxing-03-contender'
    for k,repeats in ((3,2),(5,1),(10,1)):
        for foe in FOES:
            opp=defend(foe,k)
            for arm,rep,ping,seat in itertools.product((WT,name),range(repeats),(0,80),'ab'):
                req=f'ebwide-{k}-{foe}-{arm}-{rep}-{ping}-{seat}';a,b=(arm,opp) if seat=='a' else (opp,arm)
                command=f'lab scenario {a} {b} {ping} {ping} 20000 {req} preset=mmc cps=20 reach=3 tapdelay=0 aimdelay=0 aimnoise=0 aimrate=0 aiminput=mouse'
                trials.append((req,dict(arm=arm,opponent=opp,defender_style=foe,side=seat,rep=rep,rtt=ping,gate_k=k,mode='boxing',profile='mmc',aim_input='mouse',command=command)))
    random.Random(228931).shuffle(trials)
    manifest=work/'box-breadth-native.json';run_batch(dict(trials),manifest)
    bundle=RESULT/'native.json.gz'
    if not bundle.exists():
        sc=work/'box-breadth-scores';collect(manifest,sc,False);native(manifest,sc,bundle)
    with gzip.open(bundle,'rt') as f:games=json.load(f)['matches']
    summary={};eligible=defaultdict(lambda:defaultdict(list))
    for k in (3,5,10):
        for n in (WT,name):
            for foe in FOES:
                cell=[g for g in games if (g['gate_k'],g['arm'],g['defender_style'])==(k,n,foe)]
                summary[f'{k}/{n}/{foe}']=collect_maintenance([g['first_boxing']['outcomes'] for g in cell])
                if k==3:
                    for g in cell:
                        obs=g['first_boxing']['outcomes']['3']['active']
                        if obs['entries']:eligible[n][foe].append(obs['completed_cycles'])
    shared=[o for o in FOES if eligible[WT][o] and eligible[name][o]]
    means={n:{o:sum(math.log1p(c) for c in eligible[n][o])/len(eligible[n][o]) for o in shared} for n in (WT,name)}
    advantage=[means[name][o]-means[WT][o] for o in shared];merit=.5*sum(advantage)/len(advantage)+.5*min(advantage) if advantage else 0
    winner=name if merit>0 else WT
    champions.install('boxing-04',ROOT/'policies'/(winner+'.properties'),'Native multi-defender boxing maintenance winner','boxing-04',mode='boxing')
    dump(RESULT/'native-summary.json',dict(winner=winner,compared_opponents=shared,independent_start_advantage=merit,mean_log_cycles=means,cells=summary))
    print(len(games),'independent native boxing drills',winner,flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('cmd',choices=['select','validate']);p.add_argument('work',type=Path);a=p.parse_args()
    if a.cmd=='select':choose()
    else:validation(a.work)
