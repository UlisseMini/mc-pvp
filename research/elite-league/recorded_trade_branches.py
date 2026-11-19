"""Measure how much a three-input legal midtrade change can rescue before fitting another bot."""
import gzip
import json
import shutil
import subprocess
import tempfile
from collections import Counter,defaultdict
from pathlib import Path
from adaptation import dump,freeze
from recorded_archive import field
from recorded_mmc_league import DEST,TEMP
from run import ROOT,calendars,compile_model,java
from trade_response import WORDS
from tournament import roster

def main():
    models,students=field();parent=students[1]
    foes={n:models[n] for n in ['wtap','mmc13-trade-2-fit','population-09',students[0],'adapt-01-recurrent']}
    d=DEST/'trade-branches';d.mkdir(exist_ok=True)
    clocks=calendars('training_clocks')
    dump(d/'protocol.json',dict(parent=parent,foes=list(foes),clocks=[p.name for p in clocks],
         observed='Losses in the corrected-law archive concentrate against population-09, especially seat B at zero latency. The direct actor also had a native loss against the previous trades policy. Diagnose both from the same primed midtrade state.',
         choices=WORDS,limits='Three local input heads, then restore the actor. W-tap and Hold-W branches continue throughout. An oracle may select a suffix after seeing its final reward only for estimating headroom within this menu. A legal selector can see only the observation at the branch. Require complete no-op state and terminal hashes.'))
    compile_model()
    for stage in (1,2):
        raw=d/f'exchange-{stage}.jsonl.gz'
        if not raw.exists():
            with tempfile.TemporaryDirectory(dir=TEMP) as temp:
                t=Path(temp);roster(t/'arm',{parent:models[parent]});roster(t/'foes',foes)
                with (t/'raw.gz').open('wb') as out,gzip.GzipFile(fileobj=out,mode='wb',mtime=0) as z:
                    with subprocess.Popen(java('RecordedTradeBranches',t/'arm',t/'foes',stage,*clocks),stdout=subprocess.PIPE) as proc:
                        shutil.copyfileobj(proc.stdout,z)
                        if proc.wait():raise ValueError('Exact fork verification failed')
                freeze(raw,(t/'raw.gz').read_bytes())
        by=defaultdict(Counter);cells=defaultdict(lambda:defaultdict(Counter))
        with gzip.open(raw,'rt') as f:
            for r in map(json.loads,f):
                cs=r.get('continuations');b=r['base']
                for group in (by[r['opponent']],cells[r['opponent']][r['side']+str(r['rtt'])]):
                    group['total']+=1;group['uninterrupted_points']+=b
                    if not cs:group['never_reached']+=1;continue
                    group['reached']+=1;group['after_landmark_base_points']+=b;group['after_landmark_wtap_points']+=cs['wtap'][0]
                    group['base_losses']+=b==0
                    group['recoverable_losses']+=b==0 and max(cs[k][0] for k in WORDS)==1
                    for name in WORDS:group[name+'_gain']+=cs[name][0]-b
        dump(d/f'exchange-{stage}-summary.json',dict(opponents=by,seats=cells))
        print('Measured midtrade',stage,flush=True)
if __name__=='__main__':main()
