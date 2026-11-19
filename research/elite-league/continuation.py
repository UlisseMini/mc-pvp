"""Train and publish sumo continuations using matched-state advantage targets."""
import argparse
from collections import Counter,defaultdict
import gzip
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import champions
from adaptation import fields,ranking,freeze,dump,sha
from history_cup import FROZEN,FIELD,CUP,execute
from ladder_transfer import WT,HOLD,REFERENCE,plan
from run import HERE,ROOT,calendars,compile_model,java
from tournament import roster,grid

DEST=HERE/'continuation-frozen'
OLD=FROZEN/'challenger.net'
POP=HERE/'robust-frozen/population.net'
TABLE=HERE/'empirical-game-frozen/explicit.net'
CONTROLS={'wtap':None,'hold':None,'adaptive':OLD,'population':POP,'table':TABLE}
def known():
    foes=fields(FIELD,'training')
    for i in range(1,5):
        for track in ('pair','recurrent'):foes[f'adapt-{i:02d}-{track}']=FROZEN/f'round-{i:02d}/{track}.net'
    return foes

def labels(existing,opponents,out,seed,tapes):
    with tempfile.TemporaryDirectory(dir=out.parent) as temp:
        rosterfile=Path(temp)/'opponents';roster(rosterfile,opponents)
        stage=Path(temp)/'out.gz'
        with subprocess.Popen(java('ContinuationTeacher',existing,rosterfile,seed,*tapes),stdout=subprocess.PIPE,cwd=ROOT) as process:
            with stage.open('wb') as raw,gzip.GzipFile(fileobj=raw,mode='wb',mtime=0) as f:shutil.copyfileobj(process.stdout,f)
            if process.wait():raise RuntimeError('Continuation state check failed')
        stage.replace(out)
    by=Counter();points=defaultdict(float);hashes=0
    with gzip.open(out,'rt') as f:
        for row in f:
            r=row.split();by[r[6]]+=1
            if r[6]!='ok':continue
            hashes+=int(r[9]!='-')
            for n,v in zip(('population','table','wtap','adaptive'),r[10:14]):points[n]+=float(v)
            by['disagreements']+=int(r[10]!=r[11])
    if by['ok']+by['absent']+by['nohead']!=len(opponents)*len(tapes)*4*5 or not hashes:raise AssertionError('Incomplete matched origins')
    dump(out.with_suffix('.json'),dict(states=dict(by),full_terminal_hashes=hashes,continuation_points=points,sha256=sha(out)))

def resolve(name,arms):
    return arms[name] or ROOT/'policies'/({'wtap':WT,'hold':HOLD}[name]+'.properties')

def train(work):
    compile_model();work.mkdir(parents=True,exist_ok=True);DEST.mkdir(exist_ok=True)
    foes=known();active=OLD;archive=[];past={}
    for i in range(1,4):
        name=f'continuation-{i:02d}';stage=work/name;stage.mkdir(exist_ok=True)
        pinned=DEST/name;pinned.mkdir(exist_ok=True)
        manifest=pinned/'selection.json';label=stage/'labels.tsv.gz';archive.append(label)
        if not manifest.exists():
            if not label.exists():labels(active,foes,label,863000+i,calendars('training_clocks'))
            nets=stage/'nets'
            subprocess.run(java('ContinuationFit',active,nets,*archive),cwd=ROOT,check=True)
            pool={p.stem:p for p in sorted(nets.glob('*.net'))}
            arms=CONTROLS|past|pool
            with tempfile.TemporaryDirectory(dir=work) as tmp:
                train=ranking(grid(Path(tmp),'screen',arms,foes,calendars('training_clocks'),mode='mouse'))
                shortlist=[r['name'] for r in train if r['name'] in pool][:6]
                finalists=CONTROLS|past|{n:pool[n] for n in shortlist}
                source=grid(Path(tmp),'tournament',finalists,foes,calendars('development_selection_clocks'),mode='mouse')
                ranked=ranking(source);freeze(pinned/'development.jsonl.gz',source.read_bytes())
            winner=ranked[0]['name'];new=next(r['name'] for r in ranked if r['name'] in pool)
            best_pair=next(r['name'] for r in ranked if r['name']=='adaptive' or r['name'] in (pool|past))
            if finalists[winner]:freeze(pinned/'winner.net',resolve(winner,finalists).read_bytes())
            freeze(pinned/'contender.net',pool[new].read_bytes());freeze(pinned/'pair.net',finalists[best_pair].read_bytes())
            champions.install(name,resolve(winner,finalists),'Sumo continuation tournament '+str(i)+' winner ('+winner+')',name)
            champions.install(name+'-fit',pool[new],'Sumo continuation training '+str(i)+' winner ('+new+')',name,'counterfactual')
            dump(manifest,dict(winner=winner,contender=new,top_pair=best_pair,shortlist=shortlist,
                              ranking=ranked,teacher=json.loads(label.with_suffix('.json').read_text())))
            print(name,winner,ranked[0]['merit'],new,next(r['merit'] for r in ranked if r['name']==new),flush=True)
        active=pinned/'pair.net';past[name+'-fit']=pinned/'contender.net';foes[name+'-fit']=pinned/'contender.net'
        if label.exists():
            evidence=HERE/'evidence/continuation-development';evidence.mkdir(exist_ok=True,parents=True)
            freeze(evidence/(name+'.tsv.gz'),label.read_bytes())
    picks=CONTROLS|past
    pub=DEST/'selection.json'
    if not pub.exists():
        with tempfile.TemporaryDirectory(dir=work) as tmp:
            table=grid(Path(tmp),'common',picks,foes,calendars('development_selection_clocks'),mode='mouse')
            ranked=ranking(table);freeze(DEST/'development.jsonl.gz',table.read_bytes())
            older=json.loads((FIELD/'legacy-model.json').read_text())['legacy']
            legacy=grid(Path(tmp),'legacy',picks,{'legacy-'+n:None for n in older},calendars('development_selection_clocks'),mode='mouse')
            freeze(DEST/'legacy.jsonl.gz',legacy.read_bytes());lr=ranking(legacy)
        winner=ranked[0]['name'];contender=next(r['name'] for r in ranked if r['name'] in past)
        champions.install('league-continuation',resolve(winner,picks),'Three-round continuation tournament winner','league-continuation')
        dump(pub,dict(winner=winner,challenger=contender,ranking=ranked,legacy_ranking=lr))
        print('Common field',winner,contender,flush=True)

def validate(work):
    d=json.loads((DEST/'selection.json').read_text());best=d['challenger'];entry=DEST/best.removesuffix('-fit')/'contender.net'
    arms={WT:None,'adapt-04-pair':OLD,REFERENCE:POP,'elite-game-explicit':TABLE,best:entry}
    lab=json.loads((FIELD/'field.json').read_text());field=known()
    for p in DEST.glob('continuation-??'):field[p.name+'-fit']=p/'contender.net'
    for i in range(1,5):field[f'shift-sealed-{i:02d}']=FIELD/f'nets/shift-sealed-{i:02d}.net'
    weak=next(r['weakest'] for r in d['ranking'] if r['name']==best)
    names=list(dict.fromkeys(['wtap','hold',*weak[:3],*(f'shift-sealed-{i:02d}' for i in range(1,5))]))
    opps={lab[n]['native'] if n in lab else n:field[n] for n in names}
    donors=sorted((work/'history-clocks').glob('*.txt.gz'))
    if len(donors)!=8:raise ValueError('Expected eight prospective historical donor clocks')
    scores=execute('continuation',arms,opps,plan('ectr',list(arms),list(opps),2),donors,work)['native']
    winner=min(scores,key=lambda n:(-scores[n]['merit'],-scores[n]['mean'],n))
    champions.install('league-continuation-native',champions.POLICIES/(winner+'.properties'),
                      'Native continuation and switching-opponent tournament winner','league-continuation-native')

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('stage',choices=['train','validate']);p.add_argument('work',type=Path);a=p.parse_args()
    (train if a.stage=='train' else validate)(a.work)
