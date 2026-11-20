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
        import successor_pool as pool
        from adversary import import_originals
        import_originals()
        for key in pool.KEYS:
            name='sg003-long-'+key
            field[name]=pool.model_ref(pool.RUN/key/'n65536.net')
            families.setdefault('65k single-opponent PPO',[]).append(name)
        from adversary import obtain
        for key,seed in [('incumbent',1840291),('seven',1870327),('g1',1996409)]:
            target=field['sg003-long-'+key]
            origin='g001/frontier-generalists/response/trainer.pt' if key=='g1' else 'g001/mlp/trainer.pt'
            response=obtain(target,EVIDENCE/origin,seed)
            name='sg003-counter-response-'+key;field[name]=response['policy']
            if response['confirmed']:families.setdefault('65k single-opponent PPO',[]).append(name)
        # The series pilot identified the reply to the G1 specialist as an uncovered opponent.
        # Use the same frozen response search already running for it when training the next ordinary generation.
        study=EVIDENCE/'robustness-001/stationary-field/series-ten/weakest-response/protocol.json'
        if study.is_file():
            target=json.loads(study.read_text())['policy']
            if target!=field['sg003-counter-response-g1']:raise ValueError('Series weakness no longer matches the registered G1 response')
            reply=obtain(target,EVIDENCE/'robustness-001/weak-matchups/original-mixture/trainer.pt',371016)
            if reply['confirmed'] and all(v['sha256']!=reply['policy']['sha256'] for v in field.values()):
                name='sg003-counter-to-g1-response'
                field[name]=reply['policy']
                families.setdefault('65k single-opponent PPO',[]).append(name)
        subprocess.run([sys.executable,HERE/'successor_pool.py'],check=True)
        for role,actor in json.loads((pool.OUT/'weights.json').read_text()).items():
            name='sg003-counter-trained-'+role
            field[name]=actor
            families.setdefault('counter-trained generalists',[]).append(name)
        from remote_import import enroll
        remote=enroll()
        field.update(remote)
        families['remote mixed PPO']=list(remote)
    if gen==4:
        from remote_league import OUT
        from weak_field import enroll as enroll_weak_matchups
        for role,ref in enroll_weak_matchups().items():
            name='sg004-'+role
            field[name]=ref
            families.setdefault('weak matchup curriculum',[]).append(name)
        import time
        while not (OUT/'winner-response.json').is_file():time.sleep(15)
        response=json.loads((OUT/'winner-response.json').read_text())
        prior=json.loads((p/'tournament.json').read_text())['field']
        if all(ref['sha256']!=response['policy']['sha256'] for ref in prior.values()):
            name='sg004-remote-winner-response'
            field[name]=response['policy']
            if response['confirmed']:families.setdefault('65k single-opponent PPO',[]).append(name)
    return field,families

def declare(g):
    p=EVIDENCE/f'g{g:03d}';r=json.loads((p/'tournament.json').read_text());main=next(a for a in r['ranking'] if a['name']==f'sg{g:03d}-main')
    wt=next(a for a in r['ranking'] if a['name']=='wtap')
    # Pair escalation is triggered by an observed field deficit, with matching architecture controls.
    train=main['merit']<=wt['merit']
    data=dict(train_mentor_pair=train,rescue_teachers=['wtap','sumo-seven-first'] if g==1 else [],
              main_merit=main['merit'],wtap_merit=wt['merit'],conditional_teacher_receives_hidden_id_only_during_training=True)
    write_json(p/'secondary.json',data,fixed=True);return data
