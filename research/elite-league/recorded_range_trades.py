"""Train a three-input reply at the second paired hurt cue, preserving the actor's complete fork and both policies."""
from collections import Counter,defaultdict
import gzip
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
from recorded_range_responses import prepare,OUT
from recorded_mmc_league import DEST,TEMP,evaluate,publish
from run import compile_model,calendars,java
from adaptation import dump,freeze
from tournament import roster
from trade_response import WORDS
import champions

def read(path):return json.loads(path.read_text())
def main():
    p,_,original=prepare();reply=read(OUT/'common/results.json')['reply'];p[reply]=OUT/'reply'/(reply+'.net')
    d=DEST/'range-trade-replies';d.mkdir(exist_ok=True)
    names=['wtap',original,reply,'v3-feed-39','legacy-trade-wtap-late','v3-memory-27','adapt-01-recurrent']
    foes={n:p[n] for n in names};train=calendars('training_clocks');dev=calendars('development_selection_clocks')
    baseids=[v.name.split('.')[0] for v in train]
    dump(d/'protocol.json',dict(parent=reply,observations='The physical champion conceded the opening in all eight native W-tap games, including its seven wins. The 80 ms loss m6297 stopped braking before its first travel on the last three incoming fresh hits; the opponent then delivered three successive fresh hits. Test if changing three physical input heads at the second locally paired exchange improves survival without sacrificing the established first-hit selection.',
        arm=reply,foes=names,fit=baseids[:12],threshold=baseids[12:],development=[p.name for p in dev],phase_shifts_ms=[[0,0],[9,13],[18,26]],
        words=WORDS,diagnostic='An offline oracle picks the best terminal suffix after seeing the answer. The fitted bot only sees its own 22-feature observation and must choose before playing. The same underlying donor and all its phase variants remain in one split.'))
    compile_model();raw=d/'forks.jsonl.gz'
    if not raw.exists():
        with tempfile.TemporaryDirectory(dir=TEMP) as sub:
            t=Path(sub);roster(t/'arm',{reply:p[reply]});roster(t/'foes',foes)
            with (t/'data').open('wb') as out,gzip.GzipFile(fileobj=out,mode='wb',mtime=0) as outz:
                with subprocess.Popen(java('RecordedRangeBranches',t/'arm',t/'foes',*train),stdout=subprocess.PIPE) as job:
                    shutil.copyfileobj(job.stdout,outz)
                    if job.wait():raise ValueError('Exact counterfactual failed')
            freeze(raw,(t/'data').read_bytes())
    rows=[[],[]];stats=defaultdict(Counter)
    for r in map(json.loads,gzip.open(raw,'rt')):
        id=r['clock'].split('-')[0]
        if id not in baseids:raise ValueError('Unknown donor '+id)
        cs=r.get('continuations');b=r['base'];v=[r['opponent'],b,cs['wtap'][0] if cs else b];c=stats[r['opponent']]
        c['scheduled']+=1;c['base_points']+=b
        if cs:
            c['enrolled']+=1;c['base_losses']+=b==0;c['oracle_rescued_losses']+=b==0 and max(cs[k][0] for k in WORDS)==1
            v.extend(r['x']);v.extend(cs[n][0] for n in WORDS)
            for n in WORDS:c[n+'_gain']+=cs[n][0]-b
        rows[int(id in baseids[12:])].append(' '.join(map(str,v)))
    assert [len(v) for v in rows]==[12*3*4*len(foes),4*3*4*len(foes)]
    dump(d/'mechanism.json',stats)
    n='v4-second-exchange';net=d/(n+'.net')
    if not net.exists():
        with tempfile.TemporaryDirectory(dir=TEMP) as sub:
            t=Path(sub)
            for i,v in enumerate(rows):(t/str(i)).write_text('\n'.join(v)+'\n')
            subprocess.run(java('TradeFit',t/'0',t/'1',p[reply],t/'actor',2),check=True)
            freeze(net,(t/'actor.net').read_bytes());freeze(d/'fit.json',(t/'actor.json').read_bytes())
    arms={'wtap':None,reply:p[reply],original:p[original],n:net}
    champions.install(n,net,'Range-preset second exchange control',n,'adaptive')
    ranks=evaluate(d/'development.jsonl.gz',arms,foes,dev,'mmc-range')
    publish('minemen-v4-trade-response',ranks[0]['name'],arms);dump(d/'results.json',dict(winner=ranks[0]['name'],ranks=ranks))
    print('Second trade response',ranks[0]['name'],read(d/'fit.json'),flush=True)
if __name__=='__main__':main()
