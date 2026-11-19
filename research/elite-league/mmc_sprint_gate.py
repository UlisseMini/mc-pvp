"""Select a brief learned-base movement change after an observed target hurt cue."""
import itertools

import champions
import focus
from adaptation import BLOCK,GATE,dump,freeze,sha
from history_cup import mirrors
from mechanism import encode,unpack
from mmc_click_bias import PARENT,ranking
from mmc_league import EVIDENCE,FROZEN,HERE,WORK,contest,setup
from mmc_trade_fit import ACTOR,PREVIOUS,pool
from run import calendars

STAGE='mmc13-sprint-gate'
DIR=FROZEN/STAGE
POP='elite-population'


def build(source,ms,key,ground):
    family,base=unpack(source.read_text())
    if family!='MLP' or len(base)!=229:raise ValueError('Expected the original eight-unit MLP')
    alternate=base.copy()
    # An output of -.3 lies strictly in the released-W region. Walking forces
    # W as well as releasing sprint. Other readouts use the original weights.
    alternate[184:193]=([-.3] if key=='release' else [1.])+[0.]*8
    if key=='walk':alternate[193:202]=[-1.]+[0.]*8
    gate=[0.]*GATE
    gate[0]=ms/300.-1.;gate[1+11]=1.
    if ground:
        gate[0]-=1.;gate[1+8]=1.
    w=[2,0,0,0,0,0,1,0]+gate
    for expert in (base,alternate):w+=expert+[0.]*(BLOCK-len(expert))
    return (encode('PAIR',w)+'\n').encode()


def tournament(label,arms,opponents,clocks):
    path=DIR/(label+'.jsonl.gz');focus.play_model(path,arms,opponents,clocks)
    result=ranking(path,arms,opponents,len(clocks));winner=result[0]['name']
    alias=STAGE if label=='development' else STAGE+'-'+label
    champions.install(alias,focus.source(winner,arms[winner]),f'Outgoing sprint tournament winner ({winner})',alias)
    return result


def run():
    setup();DIR.mkdir(exist_ok=True)
    field=pool()|focus.BASE|{PARENT:FROZEN/'mmc13-trades'/(PARENT+'.net')}
    controls={n:field[n] for n in ('wtap',ACTOR,POP,PARENT)}
    targets={n:field[n] for n in ('wtap',ACTOR,PREVIOUS,'continuation-02-fit','adapt-01-pair')}
    train=calendars('training_clocks');dev=calendars('development_selection_clocks')
    settings={f'mmc13-reset-{i:02d}':s for i,s in enumerate(itertools.product((ACTOR,POP),(25,75,125),('release','walk'),(False,True)))}
    dump(DIR/'protocol.json',dict(source_native=sha(EVIDENCE/'outgoing-sprint/opening-native-summary.json'),
        source_model=sha(EVIDENCE/'outgoing-sprint/hard-model-summary.json'),
        hypothesis='A two-head release improved the trade learner from 15/2/15 to 19/6/7 against continuation-02 on exposed model clocks, with the same 30/32 wins against plain W-tap. Use the existing native PAIR with two identical eight-unit experts except for forward and sprint. One expert always releases W or walks, preserving the learned strafe, click and jump readouts. Select it for a short time after a locally visible target hurt cue, optionally only on ground. This repeats the action on each qualifying cue, unlike the one-off diagnostic.',
        weights={n:focus.identity(n,p) for n,p in controls.items()},opponents=list(targets),settings=settings,
        fit=[p.name for p in train[:12]],filter=[p.name for p in train[12:]],development=[p.name for p in dev],
        ranking='Rank by the registered 90% W-tap win gate with a 75% floor per seat/RTT cell, then robust overall, tail and worst-opponent score. On the four filter donors compare the top three of each source family and all four controls. Take the best altered contender into the entire broad field, with unmodified sources, previous trade winner and W-tap. Publish all outright tournament winners and the selected challenger.',
        native='Compare the selected challenger to its stock source, previous trade winner and plain W-tap against W-tap, continuation-02 and its weakest distinct development opponent. Two native repeats per seat/RTT cell. Model confirmation reuses the eight frozen edge clocks. These clocks have appeared in earlier validation and are never training examples for this contender. Freeze weights before executing this panel.'))
    arms=dict(controls)
    for n,(source,ms,key,ground) in settings.items():
        p=DIR/(n+'.net');freeze(p,build(field[source],ms,key,ground));arms[n]=p
    fit=tournament('fit',arms,targets,train[:12])
    keep=dict(controls)
    for source in (ACTOR,POP):
        ids=[r['name'] for r in fit if r['name'] in settings and settings[r['name']][0]==source]
        keep.update({n:arms[n] for n in ids[:3]})
    filter_rank=tournament('filter',keep,targets,train[12:])
    challenger=next(r['name'] for r in filter_rank if r['name'] in settings)
    source=settings[challenger][0]
    champions.install(challenger,arms[challenger],'Outgoing sprint timing challenger ('+source+')',STAGE,'adaptive')
    broad=controls|{challenger:arms[challenger]}
    ranks=tournament('development',broad,field,dev)
    weak=next(o for o in next(r for r in ranks if r['name']==challenger)['weakest']
              if focus.identity(o,field[o])!=sha(arms[challenger]))
    dump(DIR/'selection.json',dict(winner=ranks[0]['name'],challenger=challenger,parent=source,
        weak=weak,fit=fit,filter=filter_rank,development=ranks))
    selected={n:broad[n] for n in ('wtap',source,PARENT,challenger)}
    foes={n:field[n] for n in dict.fromkeys(['wtap','continuation-02-fit',weak])};reps={n:2 for n in foes}
    dump(DIR/'confirmation.json',dict(arms={n:focus.identity(n,p) for n,p in selected.items()},
        opponents={n:focus.identity(n,p) for n,p in foes.items()},repeats=reps))
    out=EVIDENCE/'reset-confirm';out.mkdir(exist_ok=True)
    freeze(out/'input-protocol.json',(HERE/'mmc13-control-protocol.json').read_bytes())
    print(ranks[0]['name'],challenger,source,weak,flush=True)
    contest('reset-confirm',selected,foes,reps,mirrors('mmc13-edge-confirm',WORK))


if __name__=='__main__':run()
