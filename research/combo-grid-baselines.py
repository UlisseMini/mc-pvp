#!/usr/bin/env python3
"""Re-score saved oracle paths and matched baselines on the user's primary task.
No new optimization; fixed full horizon and predeclared W-tap fallback.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import time

ROOT=Path(__file__).resolve().parents[1]
JAVA=Path('/opt/homebrew/opt/openjdk@17/bin/java')
SOURCES=['src/lab/ExactTravel.java','src/lab/Json.java','tests/PeriodicComboFightModel.java','tests/PeriodicComboTransport.java','tests/PeriodicComboClock.java','tests/ComboCycleSearch.java','tests/ComboOracleSearch.java','tests/ComboBaselineCheck.java','tests/ComboWitnessCheck.java']
FIELDS=['policy','reaction_ms','fresh_hits_before_first_return','primary_metric_complete','zero_return_horizon','first_return_ms','longest_unanswered_fresh_run','longest_denied_regular_chain','stop','requested_ms','executed_ms']


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('grid',type=Path);p.add_argument('--output',required=True,type=Path);a=p.parse_args()
    if a.output.exists():raise RuntimeError('Refuse overwrite')
    a.output.mkdir(parents=True)
    manifest=json.loads((a.grid/'manifest.json').read_text())
    result={'scope':__doc__,'seconds':manifest['seconds'],'source_sha256':{f:hashlib.sha256((ROOT/f).read_bytes()).hexdigest()for f in SOURCES},'rows':[]}
    with tempfile.TemporaryDirectory(prefix='combo-baselines-')as classes:
        subprocess.run([str(JAVA.with_name('javac')),'-proc:none','-d',classes,*[str(ROOT/f)for f in SOURCES]],check=True)
        while True:
            grid=json.loads((a.grid/'summary.json').read_text());done={r['file']for r in result['rows']}
            for row in grid['rows']:
                if row['file']in done:continue
                d=json.loads((a.grid/row['file']).read_text());seed=d.get('seed_ms',0)or 0
                record={k:row[k]for k in ('file','horizontal','vertical','hit_ticks','opponent_rtt_ms','b_phase_ms','server_phase_ms','status')};record['seed_ms']=seed
                if not seed:
                    record['comparison']='no_acquisition_seed';record['wtap']='not_comparable_from_missing_seed';result['rows'].append(record);continue
                common=['3',str(row['horizontal']),str(row['vertical']),str(row['hit_ticks']),str(manifest['seconds']),str(row['opponent_rtt_ms']),str(row['b_phase_ms']),str(row['server_phase_ms']),str(seed),str(d.get('opening_burst',2))]
                stem=Path(row['file']).stem;bp=a.output/(stem+'-baselines.json');wp=a.output/(stem+'-witness.json')
                subprocess.run([str(JAVA),'-Xmx256m','-cp',classes,'ComboBaselineCheck',str(bp.resolve()),*common],check=True,capture_output=True)
                controls='\n'.join(f"{c['key']} {c.get('strafe',0)} {int(c['click'])}"for c in d.get('controls',[]))+'\n'
                subprocess.run([str(JAVA),'-Xmx256m','-cp',classes,'ComboWitnessCheck',str(wp.resolve()),*common],input=controls,text=True,check=True,capture_output=True)
                bases=json.loads(bp.read_text())['results'];witness=json.loads(wp.read_text())
                record['candidate']={k:witness.get(k)for k in FIELDS}
                for key,name in [('wtap','wtap-zero-delay'),('hold_w','hold-w'),('wtap_150ms','wtap-historical-150ms')]:
                    r=next(r for r in bases if r['policy']==name);record[key]={k:r.get(k)for k in FIELDS}
                record['comparison']='same_seed_same_horizon_oracle_prefix_then_wtap'
                result['rows'].append(record)
                print(json.dumps({k:record[k]for k in ('file','candidate','wtap','hold_w')}),flush=True)
            temp=a.output/'summary.tmp';temp.write_text(json.dumps(result,indent=2)+'\n');temp.replace(a.output/'summary.json')
            if grid['completed_cells']==grid['planned_cells'] and len(result['rows'])==grid['planned_cells']:break
            time.sleep(1)
    print('Completed primary comparisons',len(result['rows']),flush=True)


if __name__=='__main__':main()
