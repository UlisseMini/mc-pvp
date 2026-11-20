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
        if response['confirmed']:
            subprocess.run([sys.executable,HERE/'frontier_generalist.py',str(gen-1)],check=True)
            mixed=json.loads((p/'frontier-generalists/weights.json').read_text())
            for source,ref in mixed.items():
                name=f'sg{gen:03d}-mix-{source}'
                field[name]=ref;families.setdefault('frontier generalists',[]).append(name)
    if gen==2:
        # Both policies always play the same physical controller, irrespective of the opponent.
        source=EVIDENCE/'id-oracle'
        for label,folder in [('draw','dedicated-sumo-seven-first'),('wins','ringout-only-sumo-seven-first')]:
            net=source/folder/'n16384.net';name='sg002-seven-'+label
            field[name]=dict(reference=str(net.relative_to(ROOT)),sha256=hashlib.sha256(net.read_bytes()).hexdigest())
            families.setdefault('Seven specialists',[]).append(name)
    if gen==3:
        from oracle_student import OUT, ROLES
        subprocess.run([sys.executable,HERE/'oracle_student.py','run'],check=True)
        for role in ROLES:
            name='sg003-student-'+role;field[name]=pin_model(OUT/role/'ppo')
            families.setdefault('oracle-distilled students',[]).append(name)
    if gen==3:
        # All three weights come from the same predeclared training budget on the original platform.
        import time
        from long_specialist import RUN
        for key in ('incumbent','seven','g1'):
            net=RUN/key/'n32768.net'
            while not net.exists():time.sleep(5)
            name='sg003-long-'+key
            field[name]=dict(reference=str(net.relative_to(ROOT)),sha256=hashlib.sha256(net.read_bytes()).hexdigest())
            families.setdefault('32k single-opponent PPO',[]).append(name)
    return field,families

def declare(g):
    p=EVIDENCE/f'g{g:03d}';r=json.loads((p/'tournament.json').read_text());main=next(a for a in r['ranking'] if a['name']==f'sg{g:03d}-main')
    wt=next(a for a in r['ranking'] if a['name']=='wtap')
    # Pair escalation is triggered by an observed field deficit, with matching architecture controls.
    train=main['merit']<=wt['merit']
    data=dict(train_mentor_pair=train,rescue_teachers=['wtap','sumo-seven-first'] if g==1 else [],
              main_merit=main['merit'],wtap_merit=wt['merit'],conditional_teacher_receives_hidden_id_only_during_training=True)
    write_json(p/'secondary.json',data,fixed=True);return data
