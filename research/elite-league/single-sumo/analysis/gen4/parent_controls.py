"""Change one or more control groups from parent to child on common tournament fixtures."""
import concurrent.futures
import json
from math import comb
import struct
import subprocess
from review import CP, FIELD, FOES, G3, G4, WORK, fixtures, invoke

def analyze(foe):
    cases=fixtures(G4,foe)
    plan=WORK/('control-'+foe+'.tsv')
    plan.write_text(''.join('\t'.join(map(str,r[2:8]))+'\n' for r,*_ in cases))
    parent=invoke('parent-verify-'+foe,[[FIELD[G3]['reference'],*r[1:], 'intact','-'] for r,*_ in cases])
    cmd=['java','-XX:ActiveProcessorCount=1','-XX:+UseSerialGC','-Xmx640m','-cp',CP,'CheckpointSwap',
         FIELD[G3]['reference'],FIELD[G4]['reference'],FIELD[foe]['reference'],str(plan)]
    raw=subprocess.check_output(cmd,text=True)
    trials={}
    for line in raw.splitlines():
        i,m,s,t=line.split('\t');trials[int(i),int(m)]=(float(s),struct.unpack(">f",struct.pack(">f",float(t)))[0])
    wins=[0]*16
    for i,(_,k,w,t) in enumerate(cases):
        if trials[i,0]!=(parent[i]['outcome'],parent[i]['end']) or trials[i,15]!=(w,t):
            raise ValueError(('Endpoint changed',foe,k))
        for m in range(16):wins[m]+=trials[i,m][0]==1
    effects={}
    for bit,n in enumerate(['forward and sprint','strafe','click','jump']):
        effects[n]=sum((wins[m|1<<bit]-wins[m])/(4*comb(3,m.bit_count()))
                       for m in range(16) if not m&(1<<bit))/len(cases)
    assert abs(sum(effects.values())-(wins[15]-wins[0])/len(cases))<1e-8
    path=WORK/('control-'+foe+'.json')
    path.write_text(json.dumps(dict(wins=wins,effects=effects,fixtures=len(cases),
        endpoint_checks=2*len(cases)),indent=2))
    print('control swaps',foe,wins[0],wins[15],flush=True)

if __name__=='__main__':
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:list(pool.map(analyze,FOES))
