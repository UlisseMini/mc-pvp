"""Fit direct key and click controls with PPO against a structured opponent league."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import champions
import focus
from adaptation import dump,freeze,sha
from ladder import roster_fields
from mmc_league import EVIDENCE,FROZEN,WORK,setup,groups,finalists,CURRENT,OLD_TARGET,contest
from history_cup import mirrors
from run import ROOT,HERE,calendars,java,compile_model

def train():
    setup();n,new,prior,hard=finalists();modern=groups()['modern'];target=json.loads((FROZEN/'target-selection.json').read_text())['target'];focus.TARGET=target
    if (FROZEN/'round-05/selection.json').exists():
        # Earlier pair rounds operate independently. Register their weights before the first direct fit.
        optional={f'mmc13-{i:02d}-pair':FROZEN/f'round-{i:02d}/contender.net' for i in (4,5)}
    else:optional={}
    roster=FROZEN/'direct-field.json'
    if not roster.exists():dump(roster,dict(optional={k:sha(p) for k,p in optional.items()}))
    optional={k:FROZEN/('round-'+k.split('-')[1])/'contender.net' for k in json.loads(roster.read_text())['optional']}
    mixed=modern|optional;controls=focus.unique(prior|optional)
    train=calendars('training_clocks');dev=calendars('development_selection_clocks')
    start=focus.REFERENCE_MODEL;compile_model()
    for i in (1,2):
        name=f'mmc13-actor-{i}';d=FROZEN/name;d.mkdir(exist_ok=True);tmp=WORK/name;tmp.mkdir(exist_ok=True);stage=tmp/'updates'
        if i==2:
            earlier=json.loads((FROZEN/'mmc13-actor-1/selection.json').read_text())
            weak=earlier['weakest_distinct'];hard=list(dict.fromkeys([weak,*hard]))
        opponents=mixed|controls
        strata={o:(o if o in ['wtap',CURRENT,*hard] else 'scripted' if not p else 'switching' if p.read_text().startswith('PAIR.') else 'learned') for o,p in opponents.items()}
        if not (stage/'g0511.net').exists():
            with tempfile.TemporaryDirectory(dir=tmp) as temp:
                t=Path(temp);roster_fields(t/'field',opponents,strata,{})
                subprocess.run(java('PPO',t/'updates',512,32,991753+i,start,HERE/'frozen',HERE/'challenge-frozen','--mouse-league',t/'field',*train),cwd=ROOT,check=True)
                (t/'updates').rename(stage)
        pool={p.stem:p for p in stage.glob('*.net')};targeted={o:opponents[o] for o in dict.fromkeys(['wtap',CURRENT,target,*hard])}
        first=d/'training-score.jsonl.gz';focus.play_model(first,controls|pool,targeted,train[::2])
        short=[r['name'] for r in focus.score(first,targeted,True) if r['name'] in pool][:4]
        entries=controls|{k:pool[k] for k in short};field=mixed|{o:controls[o] for o in hard if o in controls}
        out=d/'development.jsonl.gz';focus.play_model(out,entries,field,dev)
        ranks=focus.score(out,field)
        best=ranks[0]['name'];student=next(r['name'] for r in ranks if r['name'] in pool)
        freeze(d/'contender.net',pool[student].read_bytes())
        if entries[best]:freeze(d/'winner.net',entries[best].read_bytes())
        champions.install(name+'-fit',d/'contender.net',f'Direct-control PPO round {i} specialist ({student})',name,'direct')
        champions.install(name,focus.source(best,entries[best]),f'Recorded-map direct-control tournament {i} winner ({best})',name)
        weak=next(o for o in ranks[0]['weakest'] if focus.identity(o,field[o])!=focus.identity(best,entries[best]))
        last=json.loads((stage/'training.jsonl').read_text().splitlines()[-1])
        dump(d/'selection.json',dict(winner=name+'-fit' if best in pool else best,challenger=name+'-fit',weakest_distinct=weak,
            ranking=ranks,starting_sha256=sha(start),heads=last['actor_heads'],rollouts_with_controls=last['bouts_including_controls'],groups=strata))
        print(name,best,student,weak,last['actor_heads'],flush=True)
        start=d/'contender.net';controls[name+'-fit']=start;mixed[name+'-fit']=start

def confirmation():
    setup();n,new,prior,hard=finalists();base=FROZEN/'mmc13-actor-2';d=json.loads((base/'selection.json').read_text())
    pool={f'mmc13-actor-{i}-fit':FROZEN/f'mmc13-actor-{i}/contender.net' for i in (1,2)}
    top=next(r['name'] for r in d['ranking'] if r['name'] in pool or r['name'].startswith('g') and r['name'][1:].isdigit())
    lead=top if top in pool else 'mmc13-actor-2-fit';arms=focus.unique({k:prior[k] for k in dict.fromkeys(['wtap','elite-population',n])}|{lead:pool[lead]})
    foes=groups()['modern']|pool|prior
    for i in (4,5):
        p=FROZEN/f'round-{i:02d}/contender.net'
        if p.exists():foes[f'mmc13-{i:02d}-pair']=p
    reps={'wtap':12,**{o:4 for o in dict.fromkeys([hard[0],d['weakest_distinct'],'hold']) if o!='wtap'}}
    dump(FROZEN/'actor-confirmation.json',dict(arms={a:focus.identity(a,p) for a,p in arms.items()},repeats=reps))
    contest('actor',arms,{n:foes[n] for n in reps},reps,mirrors('mmc13-actor',WORK))
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('stage',choices=('train','confirm'));a=p.parse_args()
    (train if a.stage=='train' else confirmation)()
