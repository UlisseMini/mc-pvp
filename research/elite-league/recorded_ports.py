"""Test zero-shot transfer of the strongest fixed range policies on the same floor and clocks."""
from pathlib import Path
from recorded_range_responses import OUT,prepare
from recorded_mmc_league import DEST,evaluate,publish
from adaptation import dump,read
from run import compile_model,calendars

def main():
    d=DEST/'range-ports';d.mkdir(exist_ok=True);pool,_,parent=prepare();scores=read(OUT/'archive/results.json')
    for track in ('reply','counter'):
        n=scores[track];pool[n]=OUT/track/(n+'.net')
    arms={n:pool[n] for n in ('wtap',parent,scores['reply'])}
    names=['wtap','hold',parent,scores['counter'],scores['reply'],'v3-feed-39','v3-memory-27','population-09','legacy-trade-wtap-late']
    foes={n:pool[n] for n in names};clocks=calendars('development_selection_clocks')
    dump(d/'protocol.json',dict(arms=list(arms),foes=names,clocks=[p.name for p in clocks],profiles=['mmc-range','mmc-recorded','mmc-v2','hypixel'],
        method='Use identical input settings, recorded 137-block floor, 8-block opening, 0/80 ms and seat exposures on four physics presets. No retraining. The Hypixel approximation derives from Housing measurements.'))
    compile_model();allr={}
    for profile in ['mmc-range','mmc-recorded','mmc-v2','hypixel']:
        raw=d/(profile+'.jsonl.gz');ranks=evaluate(raw,arms,foes,clocks,profile)
        publish('minemen-v4-port-'+profile,ranks[0]['name'],arms)
        allr[profile]=ranks
    dump(d/'results.json',allr)
    print({k:v[0]['name'] for k,v in allr.items()},flush=True)
if __name__=='__main__':main()
