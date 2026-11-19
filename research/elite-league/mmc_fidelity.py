"""Check exact travel and isolate how much early native divergence is caused by velocity timing."""
import argparse
import gzip
import json
from pathlib import Path
from fidelity_panel import pick,compile_model,compare,client,HERE,ROOT
from cpu_limit import supervise_script

def run(manifest,out,arms):
    ids=pick(manifest,arms);compile_model();evidence={}
    fitted=json.loads((HERE/'evidence/v1/transport-shape-fit.json').read_text())['selected']
    for stratum,mid in ids.items():
        g=json.loads((ROOT/'runtime/results'/(mid+'.json')).read_text());ping=int(g['initial_ping_a'])
        settings=[(.35,.35),fitted[str(ping)],'observed-velocity-drains']
        try:
            predictions=compare(mid,settings,'recorded',True)
            if not isinstance(predictions,list):raise ValueError(str(predictions))
            observed=dict(zip(('fixed','fit_on_old_training_donors','oracle_velocity_delivery'),predictions,strict=True))
        except (ValueError,IndexError,KeyError,StopIteration,AssertionError) as e:observed=dict(unscored=repr(e))
        evidence[mid]=dict(cell=stratum,arena=g['sumo_arena'],travel={s:client(mid,s) for s in 'ab'},forecasts=observed)
        print(mid,[r.get('exact') if isinstance(r,dict) else r for r in observed.values()],flush=True)
    data=dict(selection=ids,first_two_seconds=evidence,method='Actual physical controls and host stages. Oracle consumes recorded S12 drain times only. All trajectories and impulse values are computed. Its timing information is unavailable to an online policy.')
    with out.open('xb') as f:f.write(gzip.compress(json.dumps(data,separators=(',',':')).encode(),mtime=0))
if __name__=='__main__':
    supervise_script();p=argparse.ArgumentParser(description=__doc__);p.add_argument('manifest',type=Path);p.add_argument('out',type=Path);p.add_argument('arms',nargs='+');a=p.parse_args()
    run(a.manifest,a.out,a.arms)
