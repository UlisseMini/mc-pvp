"""Audit each physical fixture once, then reuse its checked immutable score in table summaries."""
import bisect
import json
import sys
import time
from collections import defaultdict
from pathlib import Path
from live import B,D,ROOT,freeze,read,run_rows,wait
import audit
from final_inputs import verify_match
audit.class_path=lambda:B/'classes'
scores=B/'scores'
def check(mid,t):
    g=read(ROOT/'runtime/results'/(mid+'.json'));out=scores/'checked'/(mid+'.json')
    if out.exists():return read(out)
    r=verify_match(mid,t,scores)
    if not r['valid']:return dict(valid=False,mid=mid,reason=r.get('reason','key mismatch'))
    extra={}
    for s in 'ab':
        if not g['parameters_'+s].get('duel_network','').startswith(('INPUT8.','INPUT32.')):continue
        f,=(ROOT/'runtime/pov'/mid).glob(s+'-client-*-events.jsonl.gz')
        ev=[e for e in audit.captures.events(f,{'click','duel_local_timing'}) if e.get('match')==mid]
        clicks=sorted(e['monotonic_ns'] for e in ev if e['type']=='click');heads=[e for e in ev if e['type']=='duel_local_timing']
        for h in heads:
            k=bisect.bisect_right(clicks,h['head_ns']);expected=clicks[k-1] if k else 0
            if h['last_press_ns']!=expected:raise ValueError((mid,s,'different last physical press',h,expected))
        extra[s]=len(heads)
    score=read(scores/(mid+'.json'));win=g['winner']
    row=dict(valid=True,mid=mid,arm=t['arm'],foe=t['opponent'],seat=t['side'],own=t['rtt'],other=t['opponent_rtt'],
        outcome='D' if not win else ('W' if win==g[t['side']] else 'L'),checked_heads=sum(v['heads'] for v in r['sides'].values()),
        local_timing=extra,click_min_ms=min((v[1] for v in score['press'].values() if v[1] is not None),default=None),
        max_server_gap_ms=score['max_server_gap_ms'])
    out.parent.mkdir(exist_ok=True,parents=True);freeze(out,row);return row
def certify(stage):
    path=D/(stage+'.json');wait(path.exists);checked={}
    while True:
        d=read(path)
        for q,m in d['matches'].items():
            if q in checked:continue
            try:checked[q]=check(m,d['plan'][q])
            except (EOFError,FileNotFoundError):
                if time.time()-read(ROOT/'runtime/results'/(m+'.json'))['finish']/1000>90:raise
        if checked.keys()==d['plan'].keys():break
        time.sleep(1)
    original={q:v['mid'] for q,v in checked.items()};invalid=[]
    for rep in range(3):
        bad={q for q,r in checked.items() if not r['valid']}
        if not bad:break
        rows={}
        for q in bad:
            invalid.append(checked[q]);new=q+'-r'+str(rep+1);r=d['plan'][q]
            rows[new]=dict(r,command=r['command'].replace(q,new))
        run_rows(stage+'-retry'+str(rep+1),rows)
        for q in bad:
            new=q+'-r'+str(rep+1);mid=read(D/(stage+'-retry'+str(rep+1)+'.json'))['matches'][new]
            checked[q]=check(mid,rows[new])
    assert all(r['valid'] for r in checked.values())
    by_arm=defaultdict(dict)
    for r in checked.values():
        v=by_arm[r['arm']].setdefault(r['foe'],[0,0,0]);v['WLD'.index(r['outcome'])]+=1
    freeze(D/(stage+'-audit.json'),dict(by_arm=by_arm,rows=checked,original=original,invalid=invalid))
if __name__=='__main__':
    for stage in ('controls','screen','confirmation'):certify(stage)
