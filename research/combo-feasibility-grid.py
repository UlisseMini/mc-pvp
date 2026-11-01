#!/usr/bin/env python3
"""Small factorial oracle map on one compiled, validated fight kernel.

Found witnesses establish finite model feasibility at the stated clocks.
No-seed / pruned-search failures leave a cell unresolved; they are not proof
of infeasibility. New native tests must confirm any proposed live controller.
"""
import argparse
import concurrent.futures
import hashlib
import itertools
import json
from pathlib import Path
import subprocess
import tempfile
import time

ROOT=Path(__file__).resolve().parents[1]
JAVA=Path('/opt/homebrew/opt/openjdk@17/bin/java')
SOURCES=['src/lab/ExactTravel.java','src/lab/Json.java','tests/PeriodicComboFightModel.java',
         'tests/PeriodicComboTransport.java','tests/PeriodicComboClock.java',
         'tests/ComboCycleSearch.java','tests/ComboOracleSearch.java','tests/ComboFeasibilitySearch.java','tests/ComboBaselineCheck.java']


def values(text):
    return [float(v) for v in text.split(',')]


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output',required=True,type=Path)
    p.add_argument('--horizontal',type=values,default=[.9,1,1.1])
    p.add_argument('--vertical',type=values,default=[.9,1,1.1])
    p.add_argument('--ticks',type=values,default=[9,10])
    p.add_argument('--pings',type=values,default=[0,60,120])
    p.add_argument('--seconds',type=float,default=12)
    p.add_argument('--beam',type=int,default=64)
    p.add_argument('--jobs',type=int,choices=[1,2],default=1)
    p.add_argument('--strafe',type=int,choices=[0,1],default=1)
    p.add_argument('--engine',choices=['ComboOracleSearch','ComboFeasibilitySearch'],default='ComboFeasibilitySearch')
    p.add_argument('--dry-run',action='store_true')
    a=p.parse_args()
    if not all(0<=v<=2 for v in a.horizontal+a.vertical):p.error('KB multipliers must be 0..2')
    if not all(v.is_integer() and 1<=v<=20 for v in map(float,a.ticks)):p.error('Ticks must be integers 1..20')
    if not all(0<=v<=500 for v in a.pings):p.error('Pings must be 0..500')
    if not (1<=a.beam<=4096 and 1<=a.seconds<=180):p.error('Use a positive bounded beam/horizon')
    phases=[(10,25),(25,5),(40,40)]
    cells=[(*cell,*phase) for cell in itertools.product(a.horizontal,a.vertical,map(int,a.ticks),a.pings) for phase in phases]
    # Begin with the vanilla high-ping reference and nearby cells. All cells
    # still run; ordering is not selection based on their outcomes.
    cells.sort(key=lambda c:(abs(c[0]-1)+abs(c[1]-1)+abs(c[2]-10)*.1+abs(c[3]-120)/1200,c))
    if a.dry_run:
        print(json.dumps({'cells':len(cells),'grid':cells,'beam':a.beam,'seconds':a.seconds,'strafe':a.strafe}));return
    if a.output.exists():p.error('Output already exists; use a new directory to preserve evidence')
    a.output.mkdir(parents=True)
    metadata={'scope':__doc__,'axes':{'horizontal':a.horizontal,'vertical':a.vertical,'hit_ticks':a.ticks,'opponent_rtt_ms':a.pings},
              'engine':a.engine,'defender_reach':3,'own_rtt_ms':0,'beam':a.beam,'seconds':a.seconds,'strafe':a.strafe,
              'phase_pairs_ms':phases,'a_tick_phase_ms':0,'leg_residual_ms':.4,
              'baseline_policies':['hold-w','wtap-zero-delay','wtap-historical-150ms'],
              'source_sha256':{f:hashlib.sha256((ROOT/f).read_bytes()).hexdigest() for f in SOURCES}}
    (a.output/'manifest.json').write_text(json.dumps(metadata,indent=2)+'\n')
    rows=[]
    def save():
        report={**metadata,'completed_cells':len(rows),'planned_cells':len(cells),'rows':sorted(rows,key=lambda r:(r['horizontal'],r['vertical'],r['hit_ticks'],r['opponent_rtt_ms']))}
        temp=a.output/'summary.tmp';temp.write_text(json.dumps(report,indent=2)+'\n');temp.replace(a.output/'summary.json')
    with tempfile.TemporaryDirectory(prefix='combo-grid-build-') as classes:
        subprocess.run([str(JAVA.with_name('javac')),'-proc:none','-d',classes,*[str(ROOT/f) for f in SOURCES]],check=True)
        if metadata['source_sha256']!={f:hashlib.sha256((ROOT/f).read_bytes()).hexdigest() for f in SOURCES}:
            raise RuntimeError('Sources changed during compilation; rerun from a stable snapshot')
        def run(cell):
            h,v,t,ping,b_phase,server_phase=cell
            stem=f'h{h:g}-v{v:g}-t{t}-p{ping:g}-phase{b_phase}-{server_phase}'
            path=(a.output/(stem+'.json')).resolve();start=time.monotonic()
            cmd=[str(JAVA),'-Xmx512m',f'-Dcombo.b_phase={b_phase}',f'-Dcombo.server_phase={server_phase}','-cp',classes,a.engine,str(path),'3',str(h),str(v),str(t),str(a.seconds),str(a.beam),str(ping),str(a.strafe),'auto','2']
            with (a.output/(stem+'.log')).open('w') as log:
                result=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT)
            row={'horizontal':h,'vertical':v,'hit_ticks':t,'opponent_rtt_ms':ping,'b_phase_ms':b_phase,'server_phase_ms':server_phase,'elapsed_seconds':time.monotonic()-start,'file':path.name}
            if result.returncode or not path.exists():
                row.update(status='tool_error',exit_code=result.returncode)
            else:
                data=json.loads(path.read_text());row['status']=data.get('result','unknown')
                for key in ('seed_ms','fresh_hits','maintained_seconds','minimum_defender_margin','known_word_survived_seconds'):
                    row[key]=data.get(key)
                row['finite_witness_found']=data.get('result')=='finite_witness'
                seed=data.get('seed_ms',0) or 0
                # Compare maintenance from exactly the oracle's seed, including
                # all delayed packets. Also measure full-fight acquisition under
                # each baseline's own controls, with the same opponent handicap.
                row['baselines']={}
                for scope,seed_ms,duration in [('maintenance',seed,a.seconds),('fresh_start',0,a.seconds+seed/1000)]:
                    if scope=='maintenance' and not seed:continue
                    base_path=(a.output/(stem+'-baseline-'+scope+'.json')).resolve()
                    base_cmd=[str(JAVA),'-Xmx256m','-cp',classes,'ComboBaselineCheck',str(base_path),'3',str(h),str(v),str(t),str(duration),str(ping),str(b_phase),str(server_phase),str(seed_ms),'2']
                    baseline=subprocess.run(base_cmd,capture_output=True,text=True)
                    if baseline.returncode:
                        row['baselines'][scope]={'error':baseline.stderr[-2000:]};continue
                    base=json.loads(base_path.read_text());row['baselines'][scope]=[
                        {k:r.get(k) for k in ('policy','reaction_ms','fresh_hits','fresh_hits_before_first_return','zero_returns_through_executed_horizon','first_return_ms','longest_unanswered_fresh_run','longest_denied_regular_chain','best_chain_ready_windows','best_chain_missed_ready_windows','stop','duration_seconds')}
                        for r in base['results']]
            return row
        with concurrent.futures.ThreadPoolExecutor(max_workers=a.jobs) as pool:
            futures=[pool.submit(run,cell) for cell in cells]
            for future in concurrent.futures.as_completed(futures):
                row=future.result();rows.append(row);save();print(json.dumps(row),flush=True)
    print('Completed',len(rows),'cells;',sum(r.get('finite_witness_found',False) for r in rows),'finite witnesses.',flush=True)


if __name__=='__main__':main()
