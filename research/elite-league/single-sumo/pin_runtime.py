"""Fingerprint the actual worker and re-run early cached outcomes with the immutable classpath."""
from league_tournament import *
from build import sim_paths
def main():
    d=sim_paths()[0];actual={str(f.relative_to(d)):hashlib.sha256(f.read_bytes()).hexdigest() for f in d.rglob('*.class')}
    field=historical();field['sg001-counter']=pin_model(EVIDENCE/'g001/exploiter')
    w=Worker();certificate={}
    try:
        for left,right in [('sg001-counter',INCUMBENT),('wtap',INCUMBENT)]:
            a,b=sorted((left,right));fixed=games(w,field,a,b);plan,keys=schedule(a,b,field)
            measured=[]
            for j in range(0,len(plan),32):measured.extend(w.run(plan[j:j+32],False))
            output=[k+[r[0],r[1]] for k,r in zip(keys,measured)]
            if output!=fixed:raise AssertionError('Frozen simulation changed: '+a+' / '+b)
            certificate[a+' / '+b]=len(fixed)
    finally:w.close()
    write_json(EVIDENCE/'simulator-runtime.json',dict(classpath=d.name,classes=actual,source_pins=json.loads((d/'source-pins.json').read_text()),
        unchanged_fixtures=certificate,comparison='All ring-outs, draws and terminal times match the pre-snapshot evidence.'),fixed=True)
if __name__=='__main__':main()
