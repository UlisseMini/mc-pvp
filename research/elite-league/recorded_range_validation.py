"""Keep eight new native-timing donors sealed until all v4 generalist and counter policies have frozen."""
import gzip
import json
import subprocess
import sys
import time
from collections import Counter,defaultdict
from adaptation import dump,freeze,sha
from recorded_mmc_league import DEST,TEMP,evaluate,publish
from recorded_range_responses import prepare,OUT
from run import compile_model,ROOT

def read(p):return json.loads(p.read_text())
def main():
    d=DEST/'range-validation';d.mkdir(exist_ok=True);stage=DEST/'v4-distance-01'
    original=read(stage/'schedule.json')['plan']
    ids=[q for q,r in original.items() if r['arm']==r['opponent']=='research-wtap20']
    if len(ids)!=8:raise ValueError('Expected eight prospective W-tap mirrors')
    dump(d/'donors.json',dict(requests=ids,selection='All eight mirror donor request IDs fixed before physical outcomes. Candidates, counter and robust reply must be frozen before exporting timing. No donor actions or match outcomes go into the simulator. Retain eligible replacements under the same immutable base requests.'))
    while not (stage/'results.json').exists() or not (OUT/'common/results.json').exists():time.sleep(8)
    actual=read(stage/'results.json')['accepted']
    pool,students,champion=prepare();league=read(OUT/'common/results.json')
    for k in ('counter','reply'):
        n=league[k];pool[n]=OUT/k/(n+'.net')
    arms={n:pool[n] for n in dict.fromkeys(('wtap',champion,league['counter'],league['reply'],league['winner'],'v3-feed-39','v3-memory-27',students[-1]))}
    names=read(OUT/'protocol.json')['development']+[league['counter'],league['reply']]
    foes={n:pool[n] for n in names}
    dump(d/'lineup.json',dict(arms={n:sha(p) if p else 'builtin '+n for n,p in arms.items()},foes=list(foes)))
    manifest=TEMP/'range-validation-donors.json';dump(manifest,dict(plan={q:original[q] for q in ids},matches={q:actual[q] for q in ids}))
    clocks=TEMP/'range-validation-clocks'
    subprocess.run([sys.executable,str(ROOT/'research/sumo-rl/calendars.py'),str(clocks),'--frozen-manifest',str(manifest)],check=True)
    files=sorted(clocks.glob('*.txt.gz'));assert len(files)==8
    for f in files:freeze(d/f.name,f.read_bytes())
    compile_model();raw=d/'prospective.jsonl.gz';ranks=evaluate(raw,arms,foes,files,'mmc-range')
    cells=defaultdict(lambda:defaultdict(lambda:defaultdict(Counter)))
    for r in map(json.loads,gzip.open(raw,'rt')):cells[r['arm']][r['opponent']][r['side']+str(r['rtt'])][{0:'L',.5:'D',1:'W'}[r['score']]]+=1
    publish('minemen-v4-clock-final',ranks[0]['name'],arms);dump(d/'results.json',dict(ranks=ranks,cells=cells))
    print('v4 prospective winner',ranks[0]['name'],flush=True)
if __name__=='__main__':main()
