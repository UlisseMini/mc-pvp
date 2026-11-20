"""Compare frozen starting points to their teachers and historical opponents on the common development panel."""
from league_tournament import *
import sys
def run(foe):
    base=EVIDENCE/'g001';field=historical();actors=['wtap',BASE_COUNTER,'sg001-counter']
    field['sg001-counter']=pin_model(base/'exploiter')
    for name,teacher in [('sg001-init-wtap','wtap'),('sg001-init-sumo-seven-first',BASE_COUNTER)]:
        p=EVIDENCE/('bootstrap-'+teacher)/'initial.net'
        field[name]=dict(reference=str(p.relative_to(ROOT)),sha256=hashlib.sha256(p.read_bytes()).hexdigest());actors.append(name)
    dest=base/'initialization-panel'/(foe+'.json')
    if dest.exists():return
    w=Worker()
    try:write_json(dest,{a:stats(games(w,field,a,foe)) for a in actors},fixed=True)
    finally:w.close()
if __name__=='__main__':
    if len(sys.argv)>1:run(sys.argv[1])
    else:
        for f in dict.fromkeys([BASE_COUNTER,'wtap',INCUMBENT,'hold','shift-check-v4-4',*FIELD]):
            subprocess.run([sys.executable,HERE.parent/'cpu_limit.py','nice','-n','8',sys.executable,__file__,f],check=True)
