"""Fit ordinary MLP controls to boxing continuations and evaluate direct matched suffix improvement."""
import argparse
import hashlib
import itertools
import json
from pathlib import Path
import random
import shutil
import subprocess
from run import HERE,ROOT,compile_model,java,calendars
from tournament import roster,grid,read
from native_trials import run_batch
from results import model_boxes

def refine(fit,out):
    out.mkdir(exist_ok=False,parents=True)
    students={p.stem:p for p in sorted((fit/'students').glob('*.net'))}
    if len(students)!=120:raise ValueError('Expected all 120 pretraining checkpoints')
    roster(out/'students.txt',students)
    subprocess.run(java('BoxingDistillFit',out/'students.txt',out/'refinement',*calendars('training_clocks')),check=True)
    field=students|{p.stem:p for p in (out/'refinement').glob('*.net')}|{'old-specialist':HERE/'boxing-frozen/specialist.net'}
    rows=read(grid(out,'development',field,{'wtap':None},calendars('development_selection_clocks'),mode='boxing'))
    by=model_boxes(rows)
    ranking=[]
    for n,data in by.items():
        a=data['rl']['3']['active'];b=data['wtap']['3']['active']
        ranked=0;compared=0;won=0;lost=0
        from report import model_game
        import math
        for r in rows:
            if r['arm']!=n:continue
            g=model_game(r)
            if '3' not in g['forks']:continue
            x=g['forks']['3'];v=x['rl']['active']['completed_cycles'];w=x['wtap']['active']['completed_cycles']
            ranked+=math.log1p(v)-math.log1p(w)+.15*((v>w)-(v<w));compared+=1;won+=v>w;lost+=v<w
        ranking.append(dict(name=n,merit=ranked/max(1,compared),starts=compared,paired_wins=won,paired_losses=lost,
                            wtap=b['cycles'],cycles=a['cycles']))
    ranking.sort(key=lambda r:(-r['merit'],-r['cycles'],r['name']))
    winner=next(r['name'] for r in ranking if r['name']!='old-specialist')
    frozen=HERE/'boxing-distilled-frozen';frozen.mkdir(exist_ok=False)
    dst=frozen/'student.net';shutil.copyfile(field[winner],dst)
    (frozen/'selection.json').write_text(json.dumps(dict(chosen=winner,sha256=hashlib.sha256(dst.read_bytes()).hexdigest(),rank=ranking),indent=2)+'\n')
    publish(frozen)
    print('Teacher-initialized finalist',ranking[:4],flush=True)

def publish(frozen):
    from champions import install
    wt='research-wtap20';student='elite-boxing-student';template=(ROOT/'policies/elite-boxing.properties').read_text().splitlines()
    if hashlib.sha256((frozen/'student.net').read_bytes()).hexdigest()!=json.loads((frozen/'selection.json').read_text())['sha256']:raise ValueError('Weights changed')
    src=(frozen/'student.net').read_text().strip()
    content='\n'.join(['duel_network='+src if l.startswith('duel_network=') else
        'description=Boxing MLP trained from own-input counterfactual demonstrations and paired combo outcomes.' if l.startswith('description=') else l for l in template])+'\n'
    path=ROOT/'policies'/(student+'.properties')
    if path.exists() and path.read_text()!=content:raise ValueError('Existing policy differs')
    if not path.exists():path.write_text(content)
    d=json.loads((frozen/'selection.json').read_text())
    leader=d['rank'][0];choice=wt if leader['merit']<=0 else 'elite-boxing' if leader['name']=='old-specialist' else student
    install('boxing-02',ROOT/'policies'/(choice+'.properties'),'Second established-combo tournament winner','boxing-02',mode='boxing')
    install('boxing-02-student',path,'Counterfactual boxing training winner','boxing-02','student','boxing')

def native(frozen,out):
    publish(frozen);wt='research-wtap20';student='elite-boxing-student';rows=[]
    for arm,rep,rtt,side in itertools.product((wt,'elite-boxing',student),range(6),(0,80),'ab'):
        opponent='combo-wake-2000';req=f'ebstu-{arm}-{rep}-{rtt}-{side}';a,b=(arm,opponent) if side=='a' else (opponent,arm)
        rows.append((req,dict(mode='boxing',profile='mmc',arm=arm,opponent=opponent,rep=rep,rtt=rtt,side=side,aim_input='mouse',
            command=f'lab scenario {a} {b} {rtt} {rtt} 20000 {req} preset=mmc reach=3 cps=20 tapdelay=0 aimdelay=0 aimnoise=0 aimrate=0 aiminput=mouse')))
    random.Random(511955).shuffle(rows);run_batch(dict(rows),out)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=('refine','model','native'));p.add_argument('source',type=Path);p.add_argument('out',type=Path);p.add_argument('--clocks',type=Path)
    a=p.parse_args()
    if a.mode=='native':native(a.source,a.out)
    elif a.mode=='refine':compile_model();refine(a.source,a.out)
    else:
        compile_model();a.out.mkdir(exist_ok=False,parents=True)
        grid(a.out,'boxing',{'student':a.source/'student.net','old-specialist':HERE/'boxing-frozen/specialist.net'},
             {'wtap':None},sorted(a.clocks.glob('*.txt.gz')),mode='boxing-mouse')
