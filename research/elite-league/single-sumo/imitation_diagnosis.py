from league_tournament import *
from assay import java_rows
def main(foe):
    folder=EVIDENCE/'g001';f=historical();name='sg001-init-sumo-seven-first';net=EVIDENCE/'bootstrap-sumo-seven-first/initial.net'
    f[name]=dict(reference=str(net.relative_to(ROOT)),sha256=hashlib.sha256(net.read_bytes()).hexdigest())
    rows,keys=schedule(name,foe,f);results=java_rows('ImitationDiagnosis',rows,[BASE_COUNTER])
    summary={};raw=[]
    for k,v in zip(keys,results):raw.append(dict(cell=k,arms=v))
    for arm in results[0]:
        vals=np.array([r[arm] for r in results]);score=vals[:,0]
        summary[arm]=dict(wins=int(sum(score==1)),losses=int(sum(score==0)),draws=int(sum(score==.5)),score=float(score.mean()),
            median_seconds=float(np.median(vals[:,1])/1000),sampled_pupil_disagreement_per_head=(vals[:,3:].sum(axis=0)/vals[:,2].sum()).tolist())
    write_json(folder/'imitation-diagnostics'/(foe+'.json'),dict(summary=summary,button_order=['walk/sprint','strafe','click','jump'],games=raw),fixed=True)
if __name__=='__main__':
    import sys
    if len(sys.argv)>1:main(sys.argv[1])
    else:
        for f in [BASE_COUNTER,INCUMBENT,'wtap']:
            subprocess.run([sys.executable,HERE.parent/'cpu_limit.py','nice','-n','8',sys.executable,__file__,f],check=True)
