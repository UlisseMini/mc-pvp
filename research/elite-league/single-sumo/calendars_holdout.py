"""Capture only exogenous schedules from prospectively enrolled native fixtures."""
import gzip
import json
from pathlib import Path
import sys
import time
import subprocess
import fcntl
from learner import ROOT,freeze,write_json
sys.path.insert(0,str(ROOT/'research/sumo-rl'))
from calendars import tape

def supply(spec,num):
    # Keep an idle lab producing independently enrolled timing donors when the old match queue has ended.
    from native_league import BUILD,WT,fixtures,play
    from native_trials import active_bridge
    with (BUILD.parent/'main-cohort.lock').open('a') as lock:
        try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:return
        try:bridge=active_bridge();matches=json.loads((ROOT/'runtime/status.json').read_text())['matches']
        except (OSError,ValueError,KeyError):return
        if not bridge or matches:return
        latest=max(int(p.stem[1:]) for p in (ROOT/'runtime/results').glob('m[0-9]*.json') if p.stem[1:].isdigit())
        name=f'sg-clock-{spec["first_future_id"]}-{num}-{latest}'
        rows=fixtures(name,[WT],[WT],8,7)
        play(rows,BUILD/'calendar-donors'/(name+'.json'),bridge)

def collect(folder):
    spec=json.loads((folder/'assay.json').read_text())
    storage=folder/'calendars';storage.mkdir(exist_ok=True)
    done=storage/'manifest.json'
    if done.exists():return
    rejected={};selected=[];num=spec['first_future_id'];last_completion=time.monotonic()
    while len(selected)<spec['calendar_count']:
        mid=f'm{num:04d}';path=ROOT/'runtime/results'/(mid+'.json')
        if not path.exists():
            # Native fixture IDs can be cancelled before an official result is saved.
            later=ROOT/'runtime/results'/f'm{num+12:04d}.json'
            if later.exists() and mid not in {m['id'] for m in json.loads((ROOT/'runtime/status.json').read_text())['matches']}:
                rejected[mid]='no completed fixture';num+=1;continue
            if time.monotonic()-last_completion>150:
                supply(spec,num);last_completion=time.monotonic()
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
        num+=1;last_completion=time.monotonic()
    write_json(done,dict(selected=selected,rejected=rejected,first=spec['first_future_id'],last=num-1),fixed=True)
if __name__=='__main__':
    if sys.argv[1]=='one':
        sys.stdout.write(tape(sys.argv[2],maximum_ticks=160,explicit_polls=True,movement_flush=True))
    else:collect(Path(sys.argv[1]).resolve())
