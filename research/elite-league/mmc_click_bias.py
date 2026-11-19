"""Search one weight of the existing MLP after paired no-hit opening diagnostics."""
from collections import defaultdict
import gzip
import json

import champions
import focus
from adaptation import dump, freeze, sha
from history_cup import mirrors
from mechanism import encode, unpack
from mmc_league import FROZEN, WORK, EVIDENCE, contest, setup
from mmc_trade_fit import ACTOR, PREVIOUS, pool
from run import calendars


PARENT = 'mmc13-trade-2-fit'
STAGE = 'mmc13-click-bias'
SPLIT = FROZEN / STAGE
DELTAS = [-1., -.5, -.25, .0625, .125, .25, .5, .75, 1., 1.5, 2., 3., 4.]


def ranking(path, entrants, opponents, count):
    with gzip.open(path,'rt') as stream:
        cells = defaultdict(lambda:defaultdict(lambda:defaultdict(list)))
        for row in map(json.loads,stream):
            cells[row['arm']][row['opponent']][row['rtt'], row['side']].append(row['score'])
    if set(cells) != set(entrants):
        raise ValueError('Missing entrants')
    result = []
    for arm, foes in cells.items():
        if set(foes) != set(opponents):
            raise ValueError('Missing opponent')
        rates = {}
        for name, strata in foes.items():
            if set(strata)!={(t,s) for t in (0,80) for s in 'ab'} or any(len(x)!=count for x in strata.values()):
                raise ValueError('Unbalanced cells')
            rates[name] = sum(map(sum,strata.values()))/(4*count)
        ordered=sorted(rates, key=lambda n:(rates[n],n))
        mean=sum(rates.values())/len(rates)
        worst_five=ordered[:5]
        merit=(mean+sum(rates[n] for n in worst_five)/len(worst_five)+rates[ordered[0]])/3
        wins=[s.count(1) for s in foes['wtap'].values()]
        result.append(dict(name=arm,qualified=sum(wins)/(4*count)>=.9 and min(wins)/count>=.75,
                           merit=merit,mean=mean,weakest=ordered[:6],rates=rates))
    return sorted(result,key=lambda r:(not r['qualified'],-r['merit'],-r['mean'],r['name']!=PARENT,r['name']))


def title(name, winner, arms):
    champions.install(name,focus.source(winner,arms[winner]),f'Click threshold tournament winner ({winner})',name)


def run():
    setup()
    SPLIT.mkdir(exist_ok=True)
    field=pool()
    field[PARENT]=FROZEN/'mmc13-trades'/(PARENT+'.net')
    trained=calendars('training_clocks')
    development=calendars('development_selection_clocks')
    training_foes={n:field[n] for n in ('wtap',ACTOR,PREVIOUS,'continuation-02-fit')}
    dump(SPLIT/'protocol.json',dict(diagnosis=sha(EVIDENCE/'opening-contact/clicks.jsonl.gz'),
         parent=sha(field[PARENT]),deltas=DELTAS,training=[p.name for p in trained],development=[p.name for p in development],
         training_opponents=list(training_foes),selection='Prefer >=90% outright W-tap wins and >=75% in each W-tap seat/RTT cell. Then rank by the mean of overall mean, bottom-five mean and minimum opponent score, break ties by overall mean and retain the unchanged parent in a tie. Propose only the best altered weight from the training clocks for the broad development tournament.',
         validation='Play W-tap, frozen parent, original PPO and the selected altered MLP against W-tap, the passive PPO, and the altered contender’s weakest distinct development opponent. Use 8 new timing donors and 2 native repeats in each seat/RTT cell.'))
    family, original=unpack(field[PARENT].read_text())
    actor_family, actor_weights=unpack(field[ACTOR].read_text())
    if family!='RESPONSE' or original[0]!=1 or actor_family!='MLP' or original[5:234]!=actor_weights or len(actor_weights)!=229:
        raise ValueError('Parent does not embed the registered MLP')
    # Five response header floats, eight hidden units with bias and 22 inputs, then the third output with bias and 8 inputs.
    bias=5+8*(1+22)+2*(1+8)
    arms={n:field[n] for n in ('wtap',PARENT)}
    for index,delta in enumerate(DELTAS):
        n=f'mmc13-click-{index:02d}'
        weights=original.copy()
        weights[bias]+=delta
        arms[n]=SPLIT/(n+'.net')
        freeze(arms[n],(encode(family,weights)+'\n').encode())
        check=unpack(arms[n].read_text())[1]
        if [i for i,(a,b) in enumerate(zip(original,check)) if a!=b]!=[bias]:
            raise ValueError('Changed more than the click output bias')
    path=SPLIT/'training.jsonl.gz'
    focus.play_model(path,arms,training_foes,trained)
    train=ranking(path,arms,training_foes,len(trained))
    winner=train[0]['name']
    contender=next(r['name'] for r in train if r['name'] not in ('wtap',PARENT))
    title(STAGE+'-train',winner,arms)
    champions.install(contender,arms[contender],f'MLP click threshold finalist ({contender})',STAGE,'adaptive')
    broad={n:field[n] for n in ('wtap',PARENT,ACTOR,'continuation-03-fit')}
    broad[contender]=arms[contender]
    path=SPLIT/'development.jsonl.gz'
    focus.play_model(path,broad,field,development)
    dev=ranking(path,broad,field,len(development))
    winner=dev[0]['name']
    title(STAGE,winner,broad)
    weak=next(o for o in next(r for r in dev if r['name']==contender)['weakest'] if focus.identity(o,field[o])!=sha(arms[contender]))
    dump(SPLIT/'selection.json',dict(training=train,development=dev,contender=contender,winner=winner,weakest_distinct=weak))
    finalists={n:broad[n] for n in ('wtap',PARENT,ACTOR,contender)}
    opponents={n:field[n] for n in dict.fromkeys(['wtap',ACTOR,weak])}
    dump(SPLIT/'confirmation.json',dict(arms={n:focus.identity(n,p) for n,p in finalists.items()},
         opponents={n:focus.identity(n,p) for n,p in opponents.items()}, repeats={n:2 for n in opponents}))
    print(winner,contender,weak,flush=True)
    contest('click-confirm',finalists,opponents,{n:2 for n in opponents},mirrors('mmc13-click-confirm',WORK))


if __name__ == '__main__':
    run()
