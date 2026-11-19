"""Approximate exploitability on v4 with a targeted best response, then train a robust reply on the enlarged league."""
from pathlib import Path
import gzip
import json
import subprocess
import tempfile
from adaptation import dump,freeze
import champions
from ladder import roster_fields
from recorded_mmc_league import DEST,TEMP,evaluate,publish
from recorded_range import roster as original_pool
from run import HERE,ROOT,calendars,compile_model,java

OUT=DEST/'range-responses'
def read(p):return json.loads(p.read_text())
def prepare():
    pool,students=original_pool();d=DEST/'range-model'
    title=read(d/'archive-ranks.json')[0]['name'];pool[title]=d/(title+'.net')
    return pool,students,title

def stage(number,pool,champion,foes,initial,exploit=None):
    name='counter' if number==1 else 'reply';d=OUT/name;d.mkdir(exist_ok=True);tmp=TEMP/('range-response-'+name);tmp.mkdir(exist_ok=True)
    focused=champion if number==1 else exploit
    spec={n:pool[n] for n in foes}
    for i in range(8 if number==1 else 4):spec[f'focus-{i}']=pool[focused]
    # Multiple strata give the targeted foe a declared share in the existing stratified PPO sampler.
    dump(d/'fit.json',dict(seed=893711+number,start=initial,focus=focused,fit_strata=list(spec),policy_uses_opponent_identity=False))
    result=tmp/'updates'
    if not result.is_dir():
        with tempfile.TemporaryDirectory(dir=tmp) as t:
            t=Path(t);roster_fields(t/'field',spec,{n:n for n in spec},{})
            subprocess.run(java('PPO',t/'updates',384,32,893711+number,pool[initial],HERE/'frozen',HERE/'challenge-frozen','--range-mouse-league',t/'field',*calendars('training_clocks')),cwd=ROOT,check=True)
            (t/'updates').rename(result)
    freeze(d/'training.jsonl',(result/'training.jsonl').read_bytes())
    checkpoints={p.stem:p for p in sorted(result.glob('*.net'))}
    incumbent={n:pool[n] for n in ('wtap',initial,champion) if n in pool}
    opponents={n:pool[n] for n in dict.fromkeys(('wtap',champion,focused,'v3-feed-39','population-09'))}
    screen=evaluate(d/'screen.jsonl.gz',incumbent|checkpoints,opponents,calendars('training_clocks')[::2],'mmc-range')
    order=(lambda r:(-r['rates'][champion],-r['rates']['wtap'],-r['merit'],r['name'])) if number==1 else (lambda r:(not r['qualified'],-r['merit'],-r['mean'],r['name']))
    ordered=sorted(screen,key=order);publish('minemen-v4-'+name+'-screen',ordered[0]['name'],incumbent|checkpoints)
    arms=incumbent.copy()
    for row in [r for r in ordered if r['name'] in checkpoints][:7]:
        n='v4-'+name+'-'+row['name'];arms[n]=d/(n+'.net');freeze(arms[n],checkpoints[row['name']].read_bytes())
    field={n:pool[n] for n in foes}
    dev=evaluate(d/'development.jsonl.gz',arms,field,calendars('development_selection_clocks'),'mmc-range')
    ranked=sorted(dev,key=order);student=next(r['name'] for r in ranked if r['name'].startswith('v4-'+name+'-'))
    pool[student]=arms[student]
    champions.install(student,arms[student],'Distance-calibrated '+name+' PPO',student,'direct')
    publish('minemen-v4-'+name,ranked[0]['name'],arms)
    dump(d/'selection.json',dict(winner=ranked[0]['name'],student=student,ranks=ranked))
    return student

def main():
    OUT.mkdir(exist_ok=True);pool,students,champion=prepare()
    field=list(dict.fromkeys(['wtap','hold',champion,'v3-feed-39','v3-memory-27',*students,'population-09','legacy-trade-wtap-late','adapt-01-recurrent','continuation-03-fit']))
    dump(OUT/'protocol.json',dict(champion=champion,development=field,method='First train a legal best response to the current champion from its weakest archived rival, rank by ring-outs against that champion then W-tap. Second train a generalist against the exposed counter plus the original field and rank by robust worst-opponent merit with the original W-tap requirement. A best response is a lower bound on exploitability within this opponent class. Only the robust common cup awards a generalist title.'))
    compile_model()
    counter=stage(1,pool,champion,field,'v3-feed-39')
    reply=stage(2,pool,champion,[*field,counter],champion,counter)
    d=OUT/'common';d.mkdir(exist_ok=True)
    arms={n:pool[n] for n in ['wtap',champion,counter,reply,'v3-feed-39','v3-memory-27',students[-1]]}
    foes={n:pool[n] for n in [*field,counter,reply]}
    ranks=evaluate(d/'development.jsonl.gz',arms,foes,calendars('development_selection_clocks'),'mmc-range')
    winner=ranks[0]['name'];publish('minemen-v4-response-cup',winner,arms)
    dump(d/'results.json',dict(winner=winner,counter=counter,reply=reply,ranks=ranks))
    print('Counter',counter,'reply',reply,'common',winner,flush=True)
if __name__=='__main__':main()
