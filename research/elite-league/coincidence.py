"""Check the native coincident-position knockback distribution and replay the previously unscored regulation bouts."""
import argparse
import gzip
import json
from pathlib import Path
import subprocess
from run import ROOT,compile_model,java

def verify(native,after=0):
    with gzip.open(native,'rt') as f:games=json.load(f)['matches']
    fixtures=[]
    for g in games:
        if g['profile']!='hypixel' or int(g['mid'][1:])<=after:continue
        mid=g['mid']
        with (ROOT/'runtime/replays'/(mid+'.jsonl')).open() as stream:
            for line in stream:
                if '"science_server_attack"' not in line:continue
                h=json.loads(line);a=h['attacker_before'];b=h['target_before']
                if h['score_before']==h['score_after'] or b['hurtResistantTime']>9:continue
                packet=h.get('velocity_packet')
                if packet is None:raise ValueError('No outgoing native S12 on accepted fresh hit '+mid)
                fixtures.append(' '.join(map(str,[mid,h['packet_id'],b['posX']-a['posX'],b['posZ']-a['posZ'],b['motionX'],b['motionY'],b['motionZ'],
                                                  a['rotationYaw'],int(a['sprint']),*packet])))
    return json.loads(subprocess.run(java('CoincidenceChecks'),input='\n'.join(fixtures)+'\n',text=True,capture_output=True,check=True).stdout)

def replay(old,clocks,model,out):
    tapes=sorted(clocks.glob('*.txt.gz'));assert len(tapes)==8
    prefix=model/'hypixel';args=[model/(prefix.name+'-arms.txt'),model/(prefix.name+'-opponents.txt'),*tapes]
    def key(r):return (r['arm'],r['opponent'],r['clock'],r['rtt'],r['side'])
    with gzip.open(old,'rt') as f:before={key(r):r for r in map(json.loads,f)}
    raw={}
    for seed in (17,1109,31297,871099):
        proc=subprocess.run(java('Matches','coincidence-'+str(seed),'hypixel',*args),capture_output=True,text=True,check=True)
        results={key(r):r for r in map(json.loads,proc.stdout.splitlines())}
        if results.keys()!=before.keys():raise ValueError('Changed schedule')
        for k,r in results.items():
            b=before[k]
            if 'unknown' in b:
                if b['unknown']!='Unsupported degenerate knockback separation' or r.get('coincidences',0)<1:raise ValueError('Unrelated unsupported case')
            elif r.get('coincidences') or any(r.get(v)!=b.get(v) for v in ('score','winner','end_ms','fresh','incoming')):
                raise ValueError('Affected a previously deterministic result')
        raw[seed]=list(results.values())
    with out.open('xb') as f:f.write(gzip.compress(json.dumps(raw,separators=(',',':')).encode(),mtime=0))

if __name__=='__main__':
    from cpu_limit import supervise_script
    supervise_script()
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('out',type=Path);p.add_argument('--native',type=Path)
    p.add_argument('--model',type=Path);p.add_argument('--clocks',type=Path);p.add_argument('--after-id',type=int,default=0);a=p.parse_args();compile_model()
    if a.native:
        result=verify(a.native,a.after_id)
        with a.out.open('x') as f:json.dump(result,f,indent=2)
    else:replay(a.model/'hypixel.jsonl.gz',a.clocks,a.model,a.out)
