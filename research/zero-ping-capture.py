#!/usr/bin/env python3
"""Small native fixtures for pipeline validation; no parameter search."""
import argparse,asyncio,hashlib,json,sys,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
from experiments import match
from parity import archive
from burst_science import diagnostic
from lab_runtime import send,properties

async def main(path,warmup=0):
    expected=hashlib.sha256((ROOT/'build/practice-client/practice-bridge.jar').read_bytes()).hexdigest()
    for _ in range(240):
        try:
            status=json.loads((ROOT/'runtime/status.json').read_text())
            healthy=all((lambda h: h.get('bridge_sha256')==expected and h.get('policy_errors')==0 and time.time()*1000-h['time']<5000)(json.loads((ROOT/f'runtime/bots/{i}/heartbeat.json').read_text())) for i in range(2))
            if healthy and all(f'LabSlot{i}' in status['players'] for i in range(2)):break
        except (OSError,ValueError,KeyError):pass
        await asyncio.sleep(1)
    else:raise RuntimeError('Current-build research clients did not become ready')
    if warmup:await asyncio.sleep(warmup)
    send('lab combat vanilla')
    fixtures=[('calibration','combo-hold-w20','research-still20'),
              ('held_out','combo-hold-w20','combo-hold-w20'),
              ('held_out','combo-wtap20','combo-hold-w20')]
    if path.exists():raise RuntimeError('Fixture results already exist; do not overwrite')
    rows=[]
    for role,a,b in fixtures:
        g=await match(a,b,0,0,duration_ms=15000)
        archive(g);d=diagnostic(g);rows.append({'role':role,'game':g,'quality':d})
        path.write_text(json.dumps(rows,indent=2)+'\n')
        print(json.dumps({'match':g['id'],'role':role,'score':[g['hits_a'],g['hits_b']],'quality':d['passed'],'issues':d['issues']}),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,default=ROOT/'research/zero-ping-native-fixtures-20261101.json');p.add_argument('--warmup',type=float,default=0)
    a=p.parse_args();asyncio.run(main(a.output,a.warmup))
