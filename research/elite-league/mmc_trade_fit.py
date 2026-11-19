"""Fit and validate three-head trade choices using the physically audited direct actor's own observations."""
import argparse
import gzip
import json
import subprocess
from adaptation import dump, freeze
import champions
import focus
from history_cup import FIELD, mirrors
from mmc_league import EVIDENCE, FROZEN, WORK, contest, finalists, groups, setup
from run import ROOT, calendars, compile_model, java
from trade_response import WORDS

ACTOR='mmc13-actor-2-fit'
PREVIOUS='mmc13-actor-1-fit'
STAGE='mmc13-trades'


def pool():
    field=groups()['modern']
    for i in (1,2):
        field[f'mmc13-actor-{i}-fit']=FROZEN/f'mmc13-actor-{i}/contender.net'
    return field


def train():
    field=pool()
    train=calendars('training_clocks')
    donors=[p.name.split('.')[0] for p in train]
    if len(set(donors))!=16:raise ValueError('Expected the registered sixteen training recordings')
    work=WORK/STAGE
    work.mkdir(exist_ok=True)
    d=FROZEN/STAGE
    d.mkdir(exist_ok=True)
    compile_model()
    fits={}
    for index in (1,2):
        source=EVIDENCE/f'training-trades/exchange-{index}.jsonl.gz'
        rows=[[],[]]
        with gzip.open(source,'rt') as f:
            for line in f:
                r=json.loads(line)
                cell=0 if donors.index(r['clock'])<12 else 1
                cs=r.get('continuations')
                record=[r['opponent'],r['base'],cs['wtap'][0] if cs else r['base']]
                if cs:record.extend(r['x']);record.extend(cs[k][0] for k in WORDS)
                rows[cell].append(' '.join(map(str,record)))
        if list(map(len,rows))!=[144,48]:raise ValueError('Incomplete training panel')
        paths=[work/f'learn-{index}.txt',work/f'validation-{index}.txt']
        for path,contents in zip(paths,rows):freeze(path,('\n'.join(contents)+'\n').encode())
        dest=work/f'trade-{index}'
        subprocess.run(java('TradeFit',*paths,field[ACTOR],dest,index),cwd=ROOT,check=True)
        name=f'mmc13-trade-{index}-fit'
        fits[name]=d/(name+'.net');freeze(fits[name],dest.with_suffix('.net').read_bytes())
        freeze(d/(name+'.json'),dest.with_suffix('.json').read_bytes())
        champions.install(name,fits[name],f'Conditional three-head response to exchange {index}',STAGE,'adaptive')
    incumbent,_,controls,_=finalists()
    arms=focus.unique(controls|{PREVIOUS:field[PREVIOUS],ACTOR:field[ACTOR]}|fits)
    out=d/'development.jsonl.gz'
    focus.play_model(out,arms,field,calendars('development_selection_clocks'))
    focus.TARGET=PREVIOUS
    ranks=focus.score(out,field)
    best=ranks[0]['name']
    candidate=next(r['name'] for r in ranks if r['name'] in fits)
    chosen=next(r for r in ranks if r['name']==candidate)
    weak=next(n for n in chosen['weakest'] if focus.identity(n,field[n])!=focus.identity(candidate,fits[candidate]))
    champions.install(STAGE,focus.source(best,arms[best]),'Measured-map exchange tournament winner ('+best+')',STAGE)
    dump(d/'selection.json',dict(winner=best,contender=candidate,weakest_distinct=weak,ranking=ranks,
                                learned_clocks=donors[:12],threshold_clocks=donors[12:]))
    print(best,candidate,weak,flush=True)


def confirm():
    field=pool()
    incumbent,_,controls,_=finalists()
    d=FROZEN/STAGE
    selection=json.loads((d/'selection.json').read_text())
    candidate=selection['contender']
    arms=focus.unique({k:controls[k] for k in ['wtap',incumbent]}|{ACTOR:field[ACTOR],candidate:d/(candidate+'.net')})
    field['shift-sealed-04']=FIELD/'nets/shift-sealed-04.net'
    names=dict.fromkeys(['wtap',PREVIOUS,'adapt-04-pair',selection['weakest_distinct'],'shift-sealed-04'])
    foes={n:field[n] for n in names}
    reps={n:8 if n=='wtap' else 4 for n in foes}
    dump(d/'confirmation.json',dict(arms={n:focus.identity(n,p) for n,p in arms.items()},
                                   opponents={n:focus.identity(n,p) for n,p in foes.items()},repeats=reps))
    contest('trade-confirm',arms,foes,reps,mirrors('mmc13-trade-confirm',WORK))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('stage',choices=('train','confirm'))
    a=p.parse_args()
    setup()
    (train if a.stage=='train' else confirm)()
