"""Run the reserved native continuation study after the league reaches its stopping rule."""
import json,sys,subprocess,time
from pathlib import Path
from collections import Counter
lab=Path(__file__).resolve().parent
from season import EVIDENCE,WORK,save,hash_policy
from report import export
from aim_audit import audit as picks
from continuation_score import score
from setup import BUILD
plan=json.loads((EVIDENCE/'perfect-aim/native-continuation-plan.json').read_text())
state=json.loads((EVIDENCE/'state.json').read_text());assert state['status']=='plateau'
actor=lab/state['incumbent'];opponent=lab/plan['opponent']
d=EVIDENCE/('terminal-continuation-%03d'%state['season']);w=WORK/d.name
d.mkdir(exist_ok=True);w.mkdir(exist_ok=True)
registration=dict(plan=plan,actor=state['incumbent'],actor_sha256=hash_policy(actor),opponent_sha256=hash_policy(opponent))
if (d/'registration.json').exists():assert registration==json.loads((d/'registration.json').read_text())
else:save(d/'registration.json',registration)
for rtt in plan['rtts_ms']:
    tag='java-'+str(rtt);seed=plan['calendar_seeds'][str(rtt)]
    if (d/(tag+'-run.json')).is_file():continue
    completed=None;prior=None
    for logfile in sorted(w.glob(tag+'-*.log')):
        for line in logfile.read_text().splitlines():
            if line.startswith('Round recordings: '):
                run=Path(line.split(': ',1)[1]);js=run/'results.json'
                if js.is_file():
                    prior=run
                    if len(json.loads(js.read_text()))==4*plan['pairs_per_rtt']:completed=run
    if completed is None:
        cmd=[sys.executable,lab/'native.py','--clock','fast','--landmark','3','--weights',actor,'--opponents',opponent,'--rtts',str(rtt),'--seed',str(seed),'--pairs',str(plan['pairs_per_rtt']),'--aim-profile','perfect']
        if prior is not None:cmd+=['--resume',prior]
        logfile=w/(tag+'-'+str(time.time_ns())+'.log')
        with logfile.open('w') as log:subprocess.run(cmd,stdout=log,stderr=log,check=True)
        completed=Path(next(l.split(': ',1)[1] for l in logfile.read_text().splitlines() if l.startswith('Round recordings: ')))
    data=export(completed,d,tag);picks(completed,d/(tag+'-aim.csv'))
    save(d/(tag+'-run.json'),dict(run=completed.name,games=len(data),branch_entries={a:sum(r['branch'] is not None for r in data if r['policy']==a) for a in ('learned','wtap')}))
    print(tag,len(data),[(a,sum(r['branch'] is not None for r in data if r['policy']==a)) for a in ('learned','wtap')],flush=True)
panels=[]
for rtt in plan['rtts_ms']:
    tag='java-'+str(rtt)
    run=BUILD/json.loads((d/(tag+'-run.json')).read_text())['run']
    audit=json.loads((d/(tag+'-audit.json')).read_text())
    if any(any(v>1e-5 for v in row['first_input_spawn_error_blocks'].values()) for row in audit):
        raise ValueError('A native continuation opening missed its declared spawn')
    panels.append((run,audit))
score(panels,d)
status=subprocess.check_output(['limactl','list','-f','{{.Name}} {{.Status}}'],text=True)
if 'sword26 Running' in status and subprocess.run(['limactl','shell','--workdir','/tmp','sword26','pgrep','-x','java'],stdout=subprocess.DEVNULL).returncode==1:
    subprocess.run(['limactl','stop','sword26'],check=True)
