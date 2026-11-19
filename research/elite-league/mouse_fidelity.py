"""Replay real key presses, clicks and integer mouse input at the recorded 1.8 client frame opportunities."""
import argparse
import json
from pathlib import Path
from fidelity_panel import pick,compile_model,compare
from cpu_limit import supervise_script

def run(manifest,out,arms):
    ids=pick(manifest,arms);compile_model();result={}
    for mid in ids.values():
        measures={}
        for mode in ('recorded','generated'):
            try:
                row=compare(mid,[(.35,.35)],mouse=mode)
                measures[mode]=row[0] if isinstance(row,list) else row
            except (ValueError,IndexError,KeyError,StopIteration,AssertionError) as exc:
                measures[mode]=dict(unscored=repr(exc))
        result[mid]=measures
        print(mid,{k:r.get('exact',r.get('unscored',r.get('unsupported'))) for k,r in measures.items()},flush=True)
    report=dict(selection=ids,first_two_seconds=result)
    with out.open('x') as f:json.dump(report,f,indent=2);f.write('\n')

if __name__=='__main__':
    supervise_script();p=argparse.ArgumentParser(description=__doc__);p.add_argument('manifest',type=Path);p.add_argument('out',type=Path);p.add_argument('arms',nargs='+')
    a=p.parse_args();run(a.manifest,a.out,a.arms)
