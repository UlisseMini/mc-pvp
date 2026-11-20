"""Capture only exogenous schedules from prospectively enrolled native fixtures."""
import gzip
import json
from pathlib import Path
import sys
import time
import subprocess
from learner import ROOT,freeze,write_json
sys.path.insert(0,str(ROOT/'research/sumo-rl'))
from calendars import tape

def collect(folder):
    spec=json.loads((folder/'assay.json').read_text())
    storage=folder/'calendars';storage.mkdir(exist_ok=True)
    done=storage/'manifest.json'
    if done.exists():return
    rejected={};selected=[];num=spec['first_future_id']
    while len(selected)<spec['calendar_count']:
        mid=f'm{num:04d}';path=ROOT/'runtime/results'/(mid+'.json')
        if not path.exists():
            # Native fixture IDs can be cancelled before an official result is saved.
            later=ROOT/'runtime/results'/f'm{num+12:04d}.json'
            if later.exists() and mid not in {m['id'] for m in json.loads((ROOT/'runtime/status.json').read_text())['matches']}:
                rejected[mid]='no completed fixture';num+=1;continue
            time.sleep(3);continue
        game=json.loads(path.read_text());out=storage/(mid+'.txt.gz');bad=storage/(mid+'.invalid.json')
        if game['mode']!='sumo' or any(not game[k].startswith('LabSlot') for k in 'ab'):
            rejected[mid]='outside bot-bot sumo cohort'
        elif bad.exists():rejected[mid]=json.loads(bad.read_text())['reason']
        else:
            if not out.exists():
                try:
                    run=subprocess.run([sys.executable,Path(__file__).resolve().parents[1]/'cpu_limit.py','nice','-n','8',sys.executable,
                                        __file__,'one',mid],capture_output=True)
                    if run.returncode:raise ValueError(run.stderr.decode().strip().splitlines()[-1])
                    encoded=run.stdout
                except (OSError,ValueError,StopIteration,EOFError) as e:
                    if time.time()-game['finish']/1000<90:time.sleep(3);continue
                    rejected[mid]=str(e);write_json(bad,{'reason':str(e)},fixed=True)
                else:freeze(out,gzip.compress(encoded,mtime=0))
            if out.exists():selected.append(str(out.relative_to(ROOT)))
        num+=1
    write_json(done,dict(selected=selected,rejected=rejected,first=spec['first_future_id'],last=num-1),fixed=True)
if __name__=='__main__':
    if sys.argv[1]=='one':
        sys.stdout.write(tape(sys.argv[2],maximum_ticks=160,explicit_polls=True,movement_flush=True))
    else:collect(Path(sys.argv[1]).resolve())
