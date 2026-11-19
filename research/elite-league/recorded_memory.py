"""Use the archive loss distribution to test whether recurrent control beats a matched memoryless search."""
from pathlib import Path
import json
import subprocess
import tempfile
import time

from adaptation import dump,freeze
from recorded_archive import field
from recorded_mmc_league import DEST,TEMP,evaluate,publish
from run import ROOT,calendars,compile_model,java
from tournament import roster

def main():
    while not (DEST/'archive/ranks.json').exists():time.sleep(5)
    field_paths,students=field();parent=students[-1]
    ranks=json.loads((DEST/'archive/ranks.json').read_text());rp=next(r for r in ranks if r['name']==parent)
    names=list(dict.fromkeys(['wtap',students[0],*[n for n in rp['weakest'] if n!=parent][:4],'mmc13-trade-2-fit','hold']))
    foes={n:field_paths[n] for n in names};d=DEST/'memory';d.mkdir(exist_ok=True)
    tmp=TEMP/'memory';tmp.mkdir(exist_ok=True);train=calendars('training_clocks');dev=calendars('development_selection_clocks')
    dump(d/'protocol.json',dict(parent=parent,foes=names,observed='Round two beats W-tap in 32/32 selection runs. Against round one the 12 lost bouts had 22.5 own vs 26 incoming fresh hits at the median; wins had 51 vs 48. Test whether remembering earlier exchanges helps maintain an edge through longer trades.',tracks='8 recurrent softsign units lifted exactly from the PPO MLP. Change only 64 hidden-to-hidden weights. Matched control varies 64 hidden-layer observation weights; identical variation seed and common panels. 48 generations, 18 candidates per track. Separate fitting and selection calendars.',
        selection='Evaluate twelve saved checkpoints per track plus W-tap and PPO parent; publish each track winner and the common winner. Treat the calendar panel as development.'))
    compile_model()
    if not (tmp/'candidates/training.jsonl').exists() or len((tmp/'candidates/training.jsonl').read_text().splitlines())!=96:
        with tempfile.TemporaryDirectory(dir=tmp) as path:
            t=Path(path);roster(t/'opponents',foes);subprocess.run(java('RecordedMemorySearch',t/'candidates',field_paths[parent],t/'opponents',*train),cwd=ROOT,check=True)
            (t/'candidates').rename(tmp/'candidates')
    arms={n:field_paths[n] for n in ['wtap',parent]}
    for p in sorted((tmp/'candidates').glob('*.net')):
        name='v3-'+p.stem;arms[name]=d/(name+'.net');freeze(arms[name],p.read_bytes())
    ranks=evaluate(d/'development.jsonl.gz',arms,foes,dev)
    selected={}
    for track in ('memory','feed'):
        sub=[r for r in ranks if r['name'].startswith('v3-'+track) or r['name'] in ('wtap',parent)]
        name=sub[0]['name'];selected[track]=name;publish('minemen-v3-'+track+'-track',name,arms)
    winner=ranks[0]['name'];publish('minemen-v3-memory-cup',winner,arms)
    dump(d/'results.json',dict(winner=winner,tracks=selected,ranks=ranks))
    print(winner,selected,flush=True)
if __name__=='__main__':main()
