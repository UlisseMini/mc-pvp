"""Development cross-play, immutable finalist selection and prospective evaluation."""
import argparse
from collections import defaultdict
import gzip
import json
from pathlib import Path
import subprocess
import hashlib
import shutil
from run import ROOT,HERE,FAMILIES,calendars,compile_model,java
SCRIPTS=('wtap','hold','trade','reset','pressure','select','counterstrafe')
CONTROLS=('wtap','hold','pressure')

def roster(path, arms):
    with path.open('x') as f:
        for name,model in arms.items():
            if model and any(c.isspace() for c in str(model.resolve())):raise ValueError('Whitespace in model file path')
            f.write(name+(' '+str(model.resolve()) if model else '')+'\n')

def grid(folder,name,arms,opps,clocks,mode='sumo',profile='mmc'):
    ap=folder/(name+'-arms.txt');op=folder/(name+'-opponents.txt')
    roster(ap,arms);roster(op,opps)
    dest=folder/(name+'.jsonl.gz')
    with dest.open('xb') as raw,gzip.GzipFile(fileobj=raw,mode='wb',mtime=0) as out:
        with subprocess.Popen(java('Matches',mode,profile,ap,op,*clocks),cwd=ROOT,stdout=subprocess.PIPE) as proc:
            shutil.copyfileobj(proc.stdout,out)
            if proc.wait():raise RuntimeError(f'{name} failed')
    return dest

def read(path):
    with gzip.open(path,'rt') as f:return [json.loads(l) for l in f]

def rank(rows,groups):
    points=defaultdict(lambda:defaultdict(list))
    for r in rows:points[r['arm']][groups.get(r['opponent'],r['opponent'])].append(r['score'])
    ranking=[]
    for name,all_scores in points.items():
        means={k:sum(v)/len(v) for k,v in all_scores.items()}
        average=sum(means.values())/len(means);worst=min(means.values())
        ranking.append(dict(arm=name,average=average,worst=worst,merit=.7*average+.3*worst,groups=means))
    return sorted(ranking,key=lambda v:(-v['merit'],-v['average'],v['arm']))

def select(fit,out):
    out.mkdir(parents=True,exist_ok=False)
    models={f'{p.parent.name}-{p.stem}':p for p in sorted(fit.glob('*/*.net'))}
    config=json.loads((fit/'configuration.json').read_text())
    assert len(models)==len(config['families'])*len(config['seeds'])*len(set(range(0,config['generations'],4))|{config['generations']-1})
    controls=dict.fromkeys(CONTROLS);scripts=dict.fromkeys(SCRIPTS)
    anchors={f'{family.lower()}-anchor':fit/f'{family.lower()}-{config["seeds"][0]}'/f'g{config["generations"]-1:03}.net' for family in FAMILIES}
    clocks=calendars('development_selection_clocks')
    first=read(grid(out,'checkpoint',models|controls,scripts|anchors,clocks))
    ranks=rank(first,{})
    finalists={}
    for family in FAMILIES:
        names=[r['arm'] for r in ranks if r['arm'].startswith(family.lower()+'-')]
        # Distinct weights prevent duplicate champions occupying both slots.
        hashes=set()
        for n in names:
            h=models[n].read_bytes()
            if h in hashes:continue
            finalists[n]=models[n];hashes.add(h)
            if len(hashes)==2:break
    field=controls|finalists
    second=read(grid(out,'round-robin',field,scripts|finalists,clocks))
    groups={k:k.split('-')[0] for k in finalists}
    final=rank(second,groups)
    selected={f:next(r['arm'] for r in final if r['arm'].startswith(f.lower()+'-')) for f in FAMILIES}
    winner=next(r['arm'] for r in final if r['arm'] in selected.values())
    result=dict(checkpoint_ranking=ranks,crossplay_ranking=final,selected=selected,champion=winner,
                bouts=len(first)+len(second),fit_sha256={n:hashlib.sha256(models[n].read_bytes()).hexdigest() for n in selected.values()})
    (out/'selection.json').write_text(json.dumps(result,indent=2)+'\n')
    for family,n in selected.items():shutil.copyfile(models[n],out/(family.lower()+'.net'))
    return result

def validate(selected,clocks,out):
    out.mkdir(parents=True,exist_ok=False)
    picks=json.loads((selected/'selection.json').read_text())
    models={f.lower():selected/(f.lower()+'.net') for f in FAMILIES}
    for fam,path in models.items():
        assert hashlib.sha256(path.read_bytes()).hexdigest()==picks['fit_sha256'][picks['selected'][fam.upper()]]
    # Each architecture also faces the others on fresh timings (cross-play, separately labelled).
    field=dict.fromkeys(CONTROLS)|models
    grid(out,'sumo',field,dict.fromkeys((*SCRIPTS,'zest-strafe'))|models,clocks)
    grid(out,'hypixel',field,dict.fromkeys(('wtap','hold','zest-strafe')),clocks,profile='hypixel')
    grid(out,'boxing',models,{'wtap':None},clocks,mode='boxing')

if __name__=='__main__':
    p=argparse.ArgumentParser();s=p.add_subparsers(dest='cmd',required=True)
    a=s.add_parser('select');a.add_argument('fit',type=Path);a.add_argument('out',type=Path)
    a=s.add_parser('validate');a.add_argument('selected',type=Path);a.add_argument('clocks',type=Path);a.add_argument('out',type=Path)
    a=p.parse_args();compile_model()
    if a.cmd=='select':
        result=select(a.fit,a.out);print(result['selected'],result['champion'],result['bouts'])
    else:validate(a.selected,sorted(a.clocks.glob('*.txt.gz')),a.out)
