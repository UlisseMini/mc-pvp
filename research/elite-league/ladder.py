"""Run successive mixed-strategy tournaments. Each completed season is a frozen input to the next."""
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
from run import HERE,ROOT,compile_model,java,calendars
from tournament import grid,read,roster
from human_sparring import controls,samples
from mechanism import unpack
from champions import publish_round

TRACKS=('rule','table','search','neural')
MILESTONES=(1,4,8,12)
def initial(out,season=13):
    c=controls()
    models={**c,'ppo':HERE/'robust-frozen/ppo.net','human-trained':HERE/'human-challenge-frozen/human.net',
            'control-trained':HERE/'human-challenge-frozen/control.net'}
    group={n:('basic' if n in ('wtap','hold') else 'learned') for n in models}
    for n in ('pressure','trade','select','counterstrafe','reset','zest-strafe','wtap-tick-window','phase-spacing','kb-displace-pull','kb-displace-edge'):
        models[n]=None;group[n]='basic' if n in ('trade','pressure') else 'timing'
    for n,p in samples(out,'selection').items():
        # Keep a small, diverse human sparring group. One human demonstration source.
        if n.endswith(('10103','50111')):models[n]=p;group[n]='human-sparring'
    for milestone in (3,8):
        d=HERE/"ladder-refinements"/f"round-{milestone}"
        if season>milestone:
            for base in ("table","reference"):
                name=f"trade-r{milestone}-{base}"
                models[name]=d/(base+".net");group[name]="programmed-responses"
    return models,group

def read_json(p):return json.loads(p.read_text())
def prefix(k):return k.split('-')[0]
def roster_fields(path,models,groups,weights):
    with path.open('x') as f:
        for n,p in models.items():
            f.write(f'{n} {groups[n]} {weights.get(n,1.0)}'+(' '+str(p.resolve()) if p else '')+'\n')

def evaluate(rows,groups):
    raw=defaultdict(lambda:defaultdict(list))
    metrics=defaultdict(lambda:defaultdict(list))
    for r in rows:
        raw[r['arm']][r['opponent']].append(r['score'])
        metrics[r['arm']][groups[r['opponent']]].append(r['score'])
    result=[]
    for n,by in metrics.items():
        scores={k:sum(v)/len(v) for k,v in by.items()};avg=sum(scores.values())/len(scores);worst=min(scores.values())
        result.append(dict(name=n,merit=.6*avg+.4*worst,mean=avg,worst=worst,groups=scores,
                           per_opponent={k:sum(v)/len(v) for k,v in raw[n].items()}))
    return sorted(result,key=lambda x:(-x['merit'],-x['mean'],x['name']))

def season(top,num,frames,generations,pop):
    out=top/f'season-{num:02d}';out.mkdir(exist_ok=False,parents=True)
    models,group=initial(out,num);weights={};past=[]
    for j in range(1,num):
        q=top/f'season-{j:02d}';prev=read_json(q/'results.json');past.append(prev)
        for track in TRACKS:
            name=f's{j:02d}-{track}';models[name]=q/(track+'.net');group[name]='prior-neural' if track=='neural' else 'prior-programs'
    if past:
        # Prioritize opponents that beat BOTH kinds of champion, retaining a 35% sampling floor.
        ref=[past[-1]['picked'][t]['per_opponent'] for t in ('explicit','neural')]
        for n in models:
            known=[m[n] for m in ref if n in m]
            weights[n]=.35+1-sum(known)/len(known) if known else 1.0
    field_path=out/'field.txt';roster_fields(field_path,models,group,weights)
    warm={};families=('mlp','recurrent','hybrid','linear')
    fam=families[(num-1)%len(families)]
    warm['neural-origin']={'mlp':HERE/'robust-frozen/champion.net','recurrent':HERE/'challenge-frozen/anti-hybrid.net',
        'hybrid':HERE/'frozen/hybrid.net','linear':HERE/'frozen/linear.net'}[fam]
    for j in range(max(1,num-4),num):
        q=top/f'season-{j:02d}'
        for t in TRACKS:
            if t!='neural' or read_json(q/'results.json')['family']==fam:warm[f'{t}-past-{j}']=q/(t+'.net')
    wp=out/'warm.txt';roster(wp,warm)
    feedback=top/f'season-{num-1:02d}'/'lost-states.txt'
    subprocess.run(java('Ladder','fit',out/'checkpoints',num,generations,pop,field_path,wp,frames,feedback,*calendars('training_clocks')),cwd=ROOT,check=True)
    pool={p.stem:p for p in sorted((out/'checkpoints').glob('*.net'))}
    baseline={k:models[k] for k in ('wtap','hold','reference','v1-hybrid','human-trained')}
    ranked=evaluate(read(grid(out,'tournament',baseline|pool,models,calendars('development_selection_clocks'))),group)
    picks={t:next(r for r in ranked if r['name'].startswith(t+'-')) for t in TRACKS}
    picks['explicit']=next(r for r in ranked if prefix(r['name']) in TRACKS[:3])
    for t,row in picks.items():shutil.copyfile(pool[row['name']],out/(t+'.net'))
    selected={t:out/(t+'.net') for t in TRACKS}
    rp=out/'stage-winners.txt';roster(rp,selected)
    # Sampling loss observations uses training clocks; no evaluation clock supplies a prototype.
    subprocess.run(java('Ladder','misses',rp,field_path,out/'lost-states.txt',*calendars('training_clocks')[:4]),cwd=ROOT,check=True)
    result=dict(season=num,family=fam,selected={k:v['name'] for k,v in picks.items()},picked=picks,
        wt=next(r for r in ranked if r['name']=='wtap'),hold=next(r for r in ranked if r['name']=='hold'),
        reference=next(r for r in ranked if r['name']=='reference'),ranking=ranked,
        hashes={t:hashlib.sha256((out/(t+'.net')).read_bytes()).hexdigest() for t in picks},
        training_bouts=read_jsonl_last(out/'checkpoints/training.jsonl')['bouts'],
        tournament_bouts=(len(pool)+len(baseline))*len(models)*32,
        feedback_rows=sum(1 for _ in (out/'lost-states.txt').open()))
    (out/'results.json').write_text(json.dumps(result,indent=2)+'\n')
    publish_round(out,result,"season")
    print(f"S{num} W-tap {result['wt']['mean']:.3f} Hold-W {result['hold']['mean']:.3f} reference {result['reference']['mean']:.3f} "
          f"explicit {picks['explicit']['name']} {picks['explicit']['mean']:.3f} neural {picks['neural']['mean']:.3f}",flush=True)

def read_jsonl_last(path):
    with path.open() as f:return json.loads(list(f)[-1])

def summary(top,out,clocks):
    out.mkdir(exist_ok=False,parents=True)
    arms,groups=initial(out);opps=dict(arms)
    for n in MILESTONES:
        for t in ('rule','table','search','neural'):
            name=f's{n:02d}-{t}';arms[name]=top/f'season-{n:02d}'/(t+'.net')
            opps[name]=arms[name];groups[name]='ladder-neural' if t=='neural' else 'ladder-explicit'
    ranked=evaluate(read(grid(out,'finalists',arms,opps,clocks)),groups)
    pick={}
    for t in ('explicit','neural'):
        winner=next(x for x in ranked if x['name'].startswith('s') and x['name'][1:3].isdigit()
                    and (x['name'].endswith('-neural') if t=='neural' else not x['name'].endswith('-neural')))
        pick[t]=winner;shutil.copyfile(arms[winner['name']],out/(t+'.net'))
    (out/'finalists.json').write_text(json.dumps(dict(picks=pick,ranked=ranked,bouts=len(arms)*len(opps)*len(clocks)*4),indent=2)+'\n')

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('mode',choices=('seasons','summary'));p.add_argument('out',type=Path)
    p.add_argument('--frames',type=Path);p.add_argument('--source',type=Path);p.add_argument('--first',type=int,default=1);p.add_argument('--last',type=int,default=12)
    p.add_argument('--generations',type=int,default=48);p.add_argument('--population',type=int,default=24)
    a=p.parse_args();compile_model()
    if a.mode=='seasons':
        for i in range(a.first,a.last+1):season(a.out,i,a.frames,a.generations,a.population)
    else:summary(a.source,a.out,calendars('development_selection_clocks'))
