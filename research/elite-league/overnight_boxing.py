"""Preserve independently audited boxing drills and score only the gate used in each bout."""
import argparse
from collections import defaultdict
import gzip
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time

from adaptation import dump,sha
from boxing_breadth import FOES,trial_plan
from final_inputs import verify_match
from ladder_transfer import WT
from mmc_league import WORK
from results import collect_maintenance,native
from run import HERE

SCORES=WORK/'box-breadth-scores'
CHECKED=SCORES/'checked'
CHALLENGER='boxing-03-contender'
RESULT=HERE/'evidence'


def read(path):
    return json.loads(path.read_text())


def scan():
    data=read(WORK/'box-breadth-native.partial.json');plan=trial_plan()
    if data['plan']!=plan:raise ValueError('Different boxing breadth schedule')
    CHECKED.mkdir(parents=True,exist_ok=True)
    for request,mid in data['matches'].items():
        out=CHECKED/(mid+'.json')
        if out.is_file():continue
        trial=plan[request];audit=verify_match(mid,trial,SCORES)
        if audit['request']!=request:raise ValueError('Wrong boxing request '+mid)
        row=dict(mid=mid,request=request,controls=audit)
        if audit['valid']:
            with tempfile.TemporaryDirectory(dir=WORK) as tmp:
                tmp=Path(tmp);manifest=tmp/'single.json';bundle=tmp/'bundle.json.gz'
                dump(manifest,dict(plan={request:trial},matches={request:mid}))
                native(manifest,SCORES,bundle)
                with gzip.open(bundle,'rt') as f:g,=json.load(f)['matches']
            row.update(gate=g['gate'],outcomes=g['first_boxing']['outcomes'])
        dump(out,row)


def follow():
    deadline=read(WORK/'admission-deadline.json')['unix_time'];seen=-1
    while time.time()<deadline+180:
        data=read(WORK/'box-breadth-native.partial.json')
        if len(data['matches'])!=seen:
            subprocess.run([sys.executable,str(HERE/'cpu_limit.py'),'--audit',sys.executable,__file__,'scan'],check=True)
            seen=len(data['matches'])
        if seen==len(data['plan']):return
        time.sleep(15)


def panel(snapshot):
    plan=trial_plan();observed=defaultdict(dict);invalid=[];unchecked=[];starts={}
    for request,receipt in snapshot['raw_native'].items():
        trial=plan.get(request)
        if trial is None:continue
        mid=receipt['mid'];path=CHECKED/(mid+'.json')
        if not path.is_file():unchecked.append(mid);continue
        row=read(path)
        if row['request']!=request or row['mid']!=mid:raise ValueError('Unexpected boxing receipt '+mid)
        if not row['controls']['valid'] or request in snapshot['known_invalid']:invalid.append(mid);continue
        k=trial['gate_k'];cell=trial['rtt'],trial['side'],trial['rep']
        observed[k,trial['defender_style'],trial['arm']][cell]=row['outcomes']
        starts[mid]=dict(request=request,**row['gate'])
    def summary(items,k):
        return dict(bouts=len(items),landmark=collect_maintenance(items)[str(k)])
    cells={}
    for k in (3,5,10):
        for foe in FOES:
            wt=observed[k,foe,WT];candidate=observed[k,foe,CHALLENGER]
            overlap=sorted(wt.keys() & candidate.keys())
            def matched(keys):
                return dict(plain_wtap=summary([wt[i] for i in keys],k),candidate=summary([candidate[i] for i in keys],k))
            cells[f'{k}/{foe}']=dict(available_wtap=len(wt),available_candidate=len(candidate),
                jointly_checked=matched(overlap),by_seat_rtt={f'{r}/{s}':matched([i for i in overlap if i[:2]==(r,s)])
                    for r in (0,80) for s in 'ab'})
    score=RESULT/'boxing100/scorecard.json';established=RESULT/'boxing-distilled/matched-scorecard.json'
    breadth=RESULT/'boxing-breadth/prospective.json'
    reg=read(score);drill=read(established)
    return dict(regulation_first_to_100=dict(source_sha256=sha(score),native=reg['native'],model=reg['model'],
                    per_match='evidence/boxing100/scorecard.json'),
                established_combo=dict(source_sha256=sha(established),native=drill['native_common_first'],
                    model_same_state=drill['model_common_forks'],seat_rtt=drill['cells']),
                multi_defender_maintenance=dict(scheduled=len(plan),checked_starts=starts,invalid=invalid,
                    finished_pending_audit=unchecked,native_paired_cells=cells,model=read(breadth),
                    model_source_sha256=sha(breadth),rule='After the registered independent native acquisitions, compare only the gate in the fixture. Pair complete challenger and W-tap fixtures on opponent, seat, RTT and repeat. The full model forks both controls from one established state.'))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('mode',choices=('scan','follow'))
    (scan if p.parse_args().mode=='scan' else follow)()
