"""Audit fresh games as they finish, persisting an original and replacement ledger."""
import sys
import time
from study import D,ROOT,BUILD,freeze,read,play
import audit_run
from audit_run import check
from native_trials import ready
audit_run.scores=BUILD/'scores'
def main():
    directory=D/'verified';directory.mkdir(exist_ok=True)
    seen={}
    while True:
        for file in sorted((D/'native').glob('[gs][0-9][0-9].json')):
            tag=file.stem;dest=directory/(tag+'.json')
            if dest.exists():continue
            d=read(file);checks=seen.setdefault(tag,{})
            for q,mid in d['matches'].items():
                if q in checks:continue
                try:r=check(mid,d['plan'][q])
                except (EOFError,FileNotFoundError):
                    if time.time()-read(ROOT/'runtime/results'/(mid+'.json'))['finish']/1000>120:raise
                    continue
                if r['valid']:
                    raw=read(ROOT/'runtime/results'/(mid+'.json'));pins=read(D/'pins.json')
                    assert raw['request']==q and all(raw['client_'+s]['bridge_sha256']==pins['bridge'] for s in 'ab')
                checks[q]=r
            if checks.keys()!=d['plan'].keys():continue
            # A separate serial recovery pass handles invalid attempts without racing the live scheduler.
            if all(r['valid'] for r in checks.values()):
                freeze(dest,dict(original=d['matches'],rows=checks,invalid=[]))
            else:
                freeze(directory/(tag+'-invalid.json'),{q:r for q,r in checks.items() if not r['valid']})
        complete=len(list(directory.glob('[gs][0-9][0-9].json')))
        if complete==56:break
        time.sleep(2)
if __name__=='__main__':main()
