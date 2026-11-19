"""Stage a small native tournament for the frozen continuation champion and old Zest opponents."""
import argparse
import gzip
import json
from pathlib import Path
import tempfile
from adaptation import dump,freeze,sha
from audit import collect
import champions
from historical_model import head_replay,EXPANDED
from history_cup import counts
from ladder_transfer import plan,WT,HOLD,REFERENCE
from native_trials import run_batch
from results import native
from run import HERE,ROOT,compile_model,calendars
from tournament import grid

def run(work):
    import time
    while not EXPANDED.exists():time.sleep(1)
    freeze_dir=HERE/'continuation-frozen';d=json.loads((freeze_dir/'selection.json').read_text())
    candidate=d['challenger'];net=freeze_dir/candidate.removesuffix('-fit')/'contender.net'
    protocol=json.loads((HERE/'port-transfer-protocol.json').read_text());opps=protocol['field']
    dest=HERE/'evidence/port-transfer';dest.mkdir(exist_ok=True,parents=True)
    dump(dest/'entrants.json',dict(arms=[WT,candidate],challenger_sha256=sha(net),opponents={n:sha(ROOT/'policies'/(n+'.properties')) for n in opps}))
    trials=plan('eport',[WT,candidate],opps,1);manifest=work/'port-smoke-native.json'
    sim=dest/'development.jsonl.gz'
    if not sim.exists():
        compile_model();models={'wtap':None,'hold':None,REFERENCE:HERE/'robust-frozen/population.net'}
        for n in opps[3:]:models['legacy-'+n]=None
        with tempfile.TemporaryDirectory(dir=work) as temp:
            f=grid(Path(temp),'replay',{'wtap':None,candidate:net},models,calendars('development_selection_clocks'),mode='mouse')
            freeze(sim,f.read_bytes())
    run_batch(trials,manifest)
    bundle=dest/'native.json.gz'
    if not bundle.exists():
        scores=work/'port-smoke-scores';collect(manifest,scores,False);native(manifest,scores,bundle)
    out=dest/'historical-inputs.json'
    if not out.exists():head_replay(manifest,out)
    with gzip.open(bundle,'rt') as f:physical=counts(json.load(f)['matches'])
    with gzip.open(sim,'rt') as f:rows=list(map(json.loads,f))
    for r in rows:r['opponent']=r['opponent'].removeprefix('legacy-')
    model=counts(rows,True);winner=min(physical,key=lambda n:(-physical[n]['merit'],-physical[n]['mean'],n))
    champions.install('league-portcheck',ROOT/'policies'/(winner+'.properties'),'Five-opponent Minecraft transfer tournament winner','league-portcheck')
    dump(dest/'results.json',dict(winner=winner,native=physical,model=model))
    print(winner, {k:v['mean'] for k,v in physical.items()},flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('work',type=Path);run(p.parse_args().work)
