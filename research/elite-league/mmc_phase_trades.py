"""Learn three-key timing responses that also work when upcoming input opportunities move."""
import argparse
import gzip
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
from collections import defaultdict
from statistics import mean

import champions
import focus
from adaptation import dump, freeze, sha
from history_cup import mirrors
from mmc_league import EVIDENCE, FROZEN, WORK, contest, finalists, setup
from mmc_trade_fit import ACTOR, PREVIOUS, STAGE as PRIOR, pool
from run import HERE, ROOT, calendars, compile_model, java
from tournament import roster
from trade_response import WORDS


STAGE = 'mmc13-phase-trades'
DELAYS = ('0','5','-5','12','-12')
OUT = EVIDENCE / 'phase-trades'
SPLIT = FROZEN / STAGE


def phase_examples(index, actor, foes, clocks, work):
    output = OUT / f'exchange-{index}.jsonl.gz'
    if not output.exists():
        with tempfile.TemporaryDirectory(dir=work) as tmp:
            t=Path(tmp)
            roster(t/'arm',{ACTOR:actor})
            roster(t/'foes',foes)
            with subprocess.Popen(java('TradePhaseStudy',t/'arm',t/'foes',index,*clocks),
                                  cwd=ROOT,stdout=subprocess.PIPE) as proc:
                with (t/'out').open('wb') as raw, gzip.GzipFile(fileobj=raw,mode='wb',mtime=0,filename='') as packed:
                    shutil.copyfileobj(proc.stdout,packed)
                if proc.wait():raise RuntimeError('Matched phase continuations failed')
            freeze(output,(t/'out').read_bytes())
    # Fixture keys contain the observed prefix and never use the modified future.
    from trade_attribution import fixtures
    original = fixtures(EVIDENCE / f'training-trades/exchange-{index}.jsonl.gz')
    rows = fixtures(output)
    if original.keys()!=rows.keys() or len(rows)!=4*len(foes)*len(clocks):
        raise ValueError('Changed clock or opponent grid')
    donors=[p.name.split('.')[0] for p in clocks]
    split=[[],[]]
    results=defaultdict(lambda:defaultdict(lambda:defaultdict(list)))
    reversals=defaultdict(lambda:defaultdict(int))
    for key,r in rows.items():
        old=original[key];opp,donor,rtt,side=key
        if r['base']!=old['base'] or r.get('fork_sha256')!=old.get('fork_sha256') or r.get('x')!=old.get('x') or r.get('at')!=old.get('at'):
            raise ValueError(f'Historical prefix changed on {key}')
        phases=r.get('phases')
        if bool(phases)!=('continuations' in old):raise ValueError('Exchange eligibility changed')
        if phases:
            nominal=phases['0']
            if (set(phases)!=set(DELAYS) or nominal['scores']!={n:v[0] for n,v in old['continuations'].items()}
                    or nominal['unchanged_terminal_sha256']!=old['unchanged_terminal_sha256']
                    or nominal['fork_sha256']!=old['fork_sha256']):
                raise ValueError(f'Historical continuations changed on {key}')
            av={word:mean(v['scores'][word] for v in phases.values()) for word in nominal['scores']}
            row=[opp,av['continue'],av['wtap'],*r['x'],*(av[word] for word in WORDS)]
            for name in nominal['scores']:
                differences=[phases[k]['scores'][name]-phases[k]['scores']['continue'] for k in DELAYS]
                if min(differences)<0<max(differences):reversals[opp][name]+=1
                for phase in DELAYS:
                    for group in ('all',f'{rtt}/{side}',donor):
                        results[opp,group][phase][name].append(phases[phase]['scores'][name])
        else:
            row=[opp,r['base'],r['base']]
        split[int(donors.index(donor)>=12)].append(' '.join(map(str,row)))
    stats={}
    for (opp,group),phases in results.items():
        stats.setdefault(opp,{})[group]={p:{n:dict(wins=v.count(1),draws=v.count(.5),losses=v.count(0))
                                              for n,v in words.items()} for p,words in phases.items()}
    dump(OUT/f'comparison-{index}.json',dict(starts=len(rows),by_phase=stats,opposite_sign_vs_actor=reversals))
    dst=work/f'fit-{index}'
    inputs=[work/f'learning-{index}.txt',work/f'tuning-{index}.txt']
    for p,batch in zip(inputs,split):freeze(p,('\n'.join(batch)+'\n').encode())
    subprocess.run(java('TradeFit',*inputs,actor,dst,index),cwd=ROOT,check=True)
    name=f'mmc13-phase-{index}-fit'
    fitted=SPLIT/(name+'.net')
    freeze(fitted,dst.with_suffix('.net').read_bytes())
    freeze(SPLIT/(name+'.json'),dst.with_suffix('.json').read_bytes())
    champions.install(name,fitted,f'Timing-averaged response to trade {index}',STAGE,'adaptive')
    return name,fitted


def train():
    setup()
    work=WORK/STAGE
    for folder in (work,SPLIT,OUT):folder.mkdir(exist_ok=True)
    clocks=calendars('training_clocks')
    field=pool()
    old={f'mmc13-trade-{i}-fit':FROZEN/PRIOR/f'mmc13-trade-{i}-fit.net' for i in (1,2)}
    field.update(old)
    foes={n:field[n] for n in ('wtap',PREVIOUS,'adapt-04-pair')}
    dump(SPLIT/'design.json',dict(protocol=sha(HERE/'mmc13-phase-trade-protocol.json'),
        actor=sha(field[ACTOR]),learning=[p.name for p in clocks[:12]],tuning=[p.name for p in clocks[12:]],
        opponents={n:focus.identity(n,p) for n,p in foes.items()}))
    compile_model()
    fits=dict(phase_examples(i,field[ACTOR],foes,clocks,work) for i in (1,2))
    _,_,controls,_=finalists()
    arms=focus.unique(controls|{PREVIOUS:field[PREVIOUS],ACTOR:field[ACTOR]}|old|fits)
    out=SPLIT/'development.jsonl.gz'
    focus.play_model(out,arms,field,calendars('development_selection_clocks'))
    focus.TARGET=PREVIOUS
    ranking=focus.score(out,field)
    winner=ranking[0]['name']
    # A new fit can have the same weights as an earlier entrant. Preserve both names.
    identities={focus.identity(n,p):n for n,p in fits.items()}
    selected=next(r for r in ranking if focus.identity(r['name'],arms[r['name']]) in identities)
    contender=identities[focus.identity(selected['name'],arms[selected['name']])]
    weak=next(n for n in selected['weakest'] if focus.identity(n,field[n])!=focus.identity(contender,fits[contender]))
    champions.install(STAGE,focus.source(winner,arms[winner]),f'Timing-averaged trade tournament winner ({winner})',STAGE)
    dump(SPLIT/'selection.json',dict(winner=winner,contender=contender,weakest_distinct=weak,ranking=ranking))
    print(winner,contender,weak,flush=True)


def confirm():
    setup()
    selected=json.loads((SPLIT/'selection.json').read_text())
    old=json.loads((FROZEN/PRIOR/'selection.json').read_text())
    field=pool()
    for i in (1,2):
        n=f'mmc13-trade-{i}-fit';field[n]=FROZEN/PRIOR/(n+'.net')
        n=f'mmc13-phase-{i}-fit';field[n]=SPLIT/(n+'.net')
    field.update(finalists()[2])
    opening=json.loads((EVIDENCE/'opening/outcomes.json').read_text())['winner']
    # The prior trade selection may record a baseline alias.
    arms=focus.unique({n:field[n] for n in dict.fromkeys(['wtap',old['winner'],ACTOR,opening,selected['contender']])})
    foes=focus.unique({n:field[n] for n in dict.fromkeys(['wtap',PREVIOUS,selected['weakest_distinct']])})
    repeats={n:2 for n in foes}
    dump(SPLIT/'confirmation.json',dict(arms={n:focus.identity(n,p) for n,p in arms.items()},
        opponents={n:focus.identity(n,p) for n,p in foes.items()},repeats=repeats))
    contest('phase-confirm',arms,foes,repeats,mirrors('mmc13-phase-confirm',WORK))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('stage',choices=('train','confirm'))
    (train if p.parse_args().stage=='train' else confirm)()
