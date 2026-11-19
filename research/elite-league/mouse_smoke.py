"""Exercise the native mouse entry point before admitting the full selection tournament."""
import argparse
from pathlib import Path
from audit import collect
from results import native
from ladder_transfer import plan,WT,HOLD
from native_trials import run_batch
from run import HERE,calendars,compile_model
from tournament import grid

def run(work):
    evidence=HERE/'evidence/mouse-smoke';evidence.mkdir(exist_ok=True)
    native_file=work/'mouse-smoke-native.json'
    run_batch(plan('emouse',[WT,'game-06'],[WT,HOLD],1),native_file)
    scores=work/'mouse-smoke-scores';collect(native_file,scores,False)
    out=evidence/'native.json.gz'
    if not out.exists():native(native_file,scores,out)
    model=work/'mouse-smoke-model'
    if not (evidence/'development.jsonl.gz').exists():
        compile_model();model.mkdir(exist_ok=True)
        p=grid(model,'development',{'wtap':None,'game-06':HERE/'empirical-game-frozen/explicit.net'},
               {'wtap':None,'hold':None},calendars('development_selection_clocks'),mode='mouse')
        (evidence/'development.jsonl.gz').write_bytes(p.read_bytes())
    import gzip,json
    with gzip.open(out,'rt') as f:d=json.load(f)
    checks=[m['mouse'][s] for m in d['matches'] for s in 'ab']
    if any(v['between_event_resets'] for v in checks) or d['summary']['binding_disagreements']:
        raise ValueError('Input discontinuity in the first physical mouse cohort')
    print(sum(v['transitions'] for v in checks),'verified native mouse updates across',len(checks),'player POVs')

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('work',type=Path);run(p.parse_args().work)
