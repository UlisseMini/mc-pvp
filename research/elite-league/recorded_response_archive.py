"""Evaluate the learned replies against all distinct archived styles before fixing their native opponents."""
import json
from recorded_range_responses import prepare,OUT
from recorded_mmc_league import evaluate,publish
from adaptation import dump
from run import calendars,compile_model
import focus

def main():
    field,_,original=prepare()
    common=json.loads((OUT/'common/results.json').read_text())
    for track in ('counter','reply'):
        n=common[track]
        field[n]=OUT/track/(n+'.net')
    arms={n:field[n] for n in ('wtap',original,common['counter'],common['reply'])}
    foes=focus.unique(field)
    d=OUT/'archive';d.mkdir(exist_ok=True)
    dump(d/'protocol.json',dict(arms=list(arms),opponents=list(foes),calendars=[p.name for p in calendars('development_selection_clocks')],
        selection='Rank on W-tap floor and robust archive score. Reserve the weakest distinct archived opponent of the common development winner for the shared native bracket.'))
    compile_model()
    ranks=evaluate(d/'development.jsonl.gz',arms,foes,calendars('development_selection_clocks'),'mmc-range')
    publish('minemen-v4-response-archive',ranks[0]['name'],arms)
    target=next(r for r in ranks if r['name']==common['winner'])
    weakest=next(n for n in target['weakest'] if focus.identity(n,field[n])!=focus.identity(target['name'],field[target['name']]))
    dump(d/'results.json',dict(winner=ranks[0]['name'],incumbent=original,common=target['name'],counter=common['counter'],
        reply=common['reply'],weakest=weakest,ranks=ranks))
    print('Archive winner',ranks[0]['name'],'weakest distinct',weakest,flush=True)

if __name__=='__main__':main()
