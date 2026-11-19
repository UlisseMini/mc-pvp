"""Fit when to open the click gate while keeping every movement output of the PPO actor."""
import itertools
import json
import random

import champions
import focus
from adaptation import BLOCK, GATE, dump, freeze, sha
from history_cup import mirrors
from mechanism import encode, unpack
from mmc_click_bias import PARENT, ranking
from mmc_league import EVIDENCE, FROZEN, WORK, contest, setup
from mmc_trade_fit import ACTOR, PREVIOUS, pool
from run import calendars

STAGE = 'mmc13-contact-gate'
SPLIT = FROZEN / STAGE


def gates():
    # Fixed controls include exact unmodified PPO and an always-open click gate after one head.
    yield 'base', dict(fixed=-1)
    yield 'open', dict(fixed=1)
    rng = random.Random(97125)
    for i in range(48):
        yield f'{i:02d}', dict(distance=rng.choice([1.,1.5,2.,2.5,3.,3.4]),
             position=rng.choice([0.,.4,1.,2.,4.]), own_velocity=rng.choice([-.5,0.,.5]),
             recent_range=rng.choice([0.,.5,1.,2.]), target_speed=rng.choice([-.5,0.,.5]),
             dwell=rng.choice([1,3,6,10]), hysteresis=rng.choice([0.,.1,.25]),
             hurt=rng.choice([.5,1.,2.]), rtt=rng.choice([-.25,0.,.25]))


def paired(actor, parameters):
    family, values = unpack(actor.read_text())
    if family != 'MLP' or len(values) != 229:
        raise ValueError('Contact study requires the frozen 22-by-8 PPO actor')
    click_bias = 8*23 + 2*9
    click_on = values.copy()
    click_on[click_bias:click_bias+9] = [1.] + [0.]*8
    gate = [0.]*GATE
    if 'fixed' in parameters:
        gate[0] = parameters['fixed']
    else:
        # Range feature is clipped (horizontal center distance - 3)/2. Negative
        # center projection favors striking away from the center. Old hurt cues
        # have feature -1. A recent cue suppresses the forced click.
        hurt = parameters['hurt']
        gate[0] = (parameters['distance'] - 3)/2 - 2*hurt + parameters['rtt']
        for i,value in {0:-1, 2:parameters['own_velocity'], 5:parameters['target_speed'],
                        10:-hurt, 11:-hurt, 12:-parameters['position'], 15:parameters['rtt']}.items():
            gate[1+i] = value
        # Slow range relative to current range suppresses activation during a fast approach.
        gate[1+22] = -parameters['recent_range']
        gate[1] += parameters['recent_range']
    weights = [2,0,0,0,0,0,parameters.get('dwell',1),parameters.get('hysteresis',0.)] + gate
    for expert in (values,click_on):
        weights += expert + [0.]*(BLOCK-len(expert))
    return (encode('PAIR',weights)+'\n').encode()


def crown(alias, winner, arms):
    champions.install(alias,focus.source(winner,arms[winner]),f'Situational click tournament winner ({winner})',alias)


def stage(name, entrants, opponents, clocks):
    path = SPLIT / (name+'.jsonl.gz')
    focus.play_model(path,entrants,opponents,clocks)
    ranked = ranking(path,entrants,opponents,len(clocks))
    crown(STAGE+({'fit':'-fit','filter':'-filter','development':''}[name]),ranked[0]['name'],entrants)
    return ranked


def run():
    setup()
    SPLIT.mkdir(exist_ok=True)
    train=calendars('training_clocks')
    dev=calendars('development_selection_clocks')
    if len(train)!=16 or len(dev)!=8 or set(dev)&set(train):
        raise ValueError('Wrong example split')
    field=pool()
    field[PARENT]=FROZEN/'mmc13-trades'/(PARENT+'.net')
    field['mmc13-phase-2-fit']=FROZEN/'mmc13-phase-trades/mmc13-phase-2-fit.net'
    opponents={n:field[n] for n in ('wtap',ACTOR,PREVIOUS,'breadth-01-fit','continuation-02-fit')}
    settings=dict(gates())
    dump(SPLIT/'protocol.json',dict(diagnosis=sha(EVIDENCE/'opening-contact/clicks.jsonl.gz'),
        actor=sha(field[ACTOR]),fit=[p.name for p in train[:12]],filter=[p.name for p in train[12:]],
        development=[p.name for p in dev],opponents=list(opponents),gate_parameters=settings,
        design='A pair of copies of the same frozen PPO MLP receives the same applied previous action. Only the second copy’s click readout is forced open. The selector uses local range and its recent history, self and target movement, hurt cues, center projection, and RTT. Search 48 selectors, plus disabled and always-open controls.',
        ranking='Use the click-bias tournament W-tap wins gate and robust mean/tail/min ranking unchanged. Filter the best six fitted gates on four separate training donors, then test only the highest-ranked of those six on all development opponents. Include the PPO, previous trade champion, and plain W-tap throughout. Publish each tournament winner.',
        test='Score the selected situational gate, raw PPO, previous trade winner and W-tap on 8 new prospective clocks and 2 native repeats per seat/RTT cell against W-tap, raw PPO and the selected contender’s weakest distinct development opponent.'))
    control={n:field[n] for n in ('wtap',ACTOR,PARENT)}
    entrants=dict(control)
    for k,spec in settings.items():
        n='mmc13-contact-'+k
        path=SPLIT/(n+'.net')
        freeze(path,paired(field[ACTOR],spec))
        entrants[n]=path
    fit=stage('fit',entrants,opponents,train[:12])
    # Check the always-disabled selector agrees on all per-match results with the raw actor.
    import gzip
    with gzip.open(SPLIT/'fit.jsonl.gz','rt') as f:
        check={(r['arm'],r['opponent'],r['clock'],r['rtt'],r['side']):
               [r[k] for k in ('score','winner','end_ms','fresh','incoming')] for r in map(json.loads,f) if r['arm'] in (ACTOR,'mmc13-contact-base')}
    for o,p,t,s in itertools.product(opponents,train[:12],(0,80),'ab'):
        key=(o,p.name.split('.')[0],t,s)
        if check[ACTOR,*key]!=check['mmc13-contact-base',*key]:
            raise ValueError('Disabled click selector changed a fight')
    chosen=[r['name'] for r in fit if r['name'].split('-')[-1].isdigit()][:6]
    filtered=stage('filter',control|{n:entrants[n] for n in chosen},opponents,train[12:])
    contender=next(r['name'] for r in filtered if r['name'] in chosen)
    champions.install(contender,entrants[contender],f'Situational click finalist ({contender})',STAGE,'adaptive')
    finalists=control|{contender:entrants[contender]}
    development=stage('development',finalists,field,dev)
    weakest=next(o for o in next(r for r in development if r['name']==contender)['weakest'] if focus.identity(o,field[o])!=sha(entrants[contender]))
    dump(SPLIT/'selection.json',dict(fit=fit,filtered=filtered,development=development,contender=contender,
                                    winner=development[0]['name'],weakest_distinct=weakest))
    foes={n:field[n] for n in dict.fromkeys(['wtap',ACTOR,weakest])}
    repeats={n:2 for n in foes}
    dump(SPLIT/'confirmation.json',dict(arms={n:focus.identity(n,p) for n,p in finalists.items()},
        opponents={n:focus.identity(n,p) for n,p in foes.items()},repeats=repeats))
    print(development[0]['name'],contender,weakest,flush=True)
    contest('contact-confirm',finalists,foes,repeats,mirrors('mmc13-contact-confirm',WORK))


if __name__=='__main__':
    run()
