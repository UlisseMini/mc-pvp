"""Enroll the predeclared rescue searches and privileged-teacher students without choosing by completion order."""
import sys
from league_tournament import *
import subprocess

def enrollment(gen):
    if gen<2:return {},{}
    p=EVIDENCE/f'g{gen-1:03d}';spec=p/'secondary.json'
    if not spec.exists():return {},{}
    request=json.loads(spec.read_text());field={};families={}
    for role,k in [('mentor-gru','gru'),('mentor-mlp','mlp')]:
        if not request['train_mentor_pair']:continue
        subprocess.run([sys.executable,HERE/'mentor.py',str(gen-1),k],check=True)
        name=f'sg{gen:03d}-{role}';field[name]=pin_model(p/role/'ppo')
        families.setdefault('archive imitation',[]).append(name)
    for teacher in request['rescue_teachers']:
        d=p/('rescue-'+teacher)
        if not (d/'result.json').exists():
            subprocess.run([sys.executable,HERE/'recover.py',str(gen-1),teacher],check=True)
        result=json.loads((d/'result.json').read_text());name=f'sg{gen:03d}-response-'+('wt' if teacher=='wtap' else 's7')
        field[name]=pin_model(d)
        if result['confirmed']:families.setdefault('demonstration responses',[]).append(name)
    nomination=json.loads((p/'nomination.json').read_text())
    if nomination['winner']!=nomination['incumbent']:
        subprocess.run([sys.executable,HERE/'frontier.py',str(gen-1),'native'],check=True)
        response=json.loads((p/'winner-response/result.json').read_text())
        name=response['name'];field[name]=response['policy']
        if response['confirmed']:families.setdefault('tournament winner responses',[]).append(name)
    return field,families

def declare(g):
    p=EVIDENCE/f'g{g:03d}';r=json.loads((p/'tournament.json').read_text());main=next(a for a in r['ranking'] if a['name']==f'sg{g:03d}-main')
    wt=next(a for a in r['ranking'] if a['name']=='wtap')
    # Pair escalation is triggered by an observed field deficit, with matching architecture controls.
    train=main['merit']<=wt['merit']
    data=dict(train_mentor_pair=train,rescue_teachers=['wtap','sumo-seven-first'] if g==1 else [],
              main_merit=main['merit'],wtap_merit=wt['merit'],conditional_teacher_receives_hidden_id_only_during_training=True)
    write_json(p/'secondary.json',data,fixed=True);return data
