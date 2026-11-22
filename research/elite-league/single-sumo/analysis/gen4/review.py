"""Replay tournament fixtures on the frozen Java classes. Run from the repository root."""
import concurrent.futures
import gzip
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path.cwd()
EV = ROOT / 'research/elite-league/single-sumo/evidence'
BASE = EV / 'robustness-001/continuous-league/g004'
FIELD = json.loads((BASE/'tournament.json').read_text())['field']
CLOCKS = [r.split('\t')[1] for r in (ROOT/'research/elite-league/ping-ppo/clocks.tsv').read_text().splitlines()
          if r.startswith('dev\t')]
G4, G3 = 'remote-004-main-gru', 'remote-003-counter-gru'
SEVEN, WT, COUNTER = 'sumo-seven-first', 'wtap', 'remote-004-counter-gru'
FOES = [SEVEN, WT, G3, COUNTER]
WORK = Path(sys.argv[1]).resolve()
CP = os.pathsep.join(map(str, [WORK, ROOT/'build/elite-league/single-sumo/sim-0de762acda668fc8af56']))

def fixtures(actor, opponent):
    left,right=sorted([actor,opponent])
    stem='--'.join(n+'_'+FIELD[n]['sha256'][:10] for n in (left,right))
    rows=json.loads((EV/'dev-games'/(stem+'.json')).read_text())
    result=[]
    for clock,rep,seat,p,q,win,end in rows:
        flip='b' if seat=='a' else 'a'
        def seed(n,s):
            v=f'league-dev-1/{n}/{rep}/{clock}/{s}/{p if seat=="a" else q}/{q if seat=="a" else p}'
            return int(hashlib.sha256(v.encode()).hexdigest()[:15],16)
        x,y=seed(left,seat),seed(right,flip)
        if actor!=left:seat,p,q,x,y,win=flip,q,p,y,x,1-win
        result.append(([FIELD[actor]['reference'],FIELD[opponent]['reference'],CLOCKS[clock],seat,p,q,x,y],
                       (clock,rep,seat,p,q),win,end))
    return result

def invoke(name, rows):
    plan=WORK/(name+'.tsv');output=WORK/(name+'.jsonl')
    plan.write_text(''.join('\t'.join(map(str,row))+'\n' for row in rows))
    subprocess.run(['java','-XX:ActiveProcessorCount=1','-XX:+UseSerialGC','-Xmx640m',
                    '-cp',CP,'Gen4Review',str(plan),str(output)],check=True)
    data=[json.loads(s) for s in output.read_text().splitlines()]
    if len(data)!=len(rows):raise ValueError(name)
    return data

def run_pair(pair):
    actor,opponent=pair;cases=fixtures(*pair);name=actor+'--'+opponent
    out=[]
    arms=['intact']
    if actor==G4:arms+=['catalog','no-jump','no-strafe','forward-sprint','always-click','no-state','recent6']
    for arm in arms:
        rows=[[*r,arm,'detail' if arm=='intact' and k[0]==0 and k[1]==0 and k[3:]==(31,31) else '-']
              for r,k,_,_ in cases]
        raw=invoke(name+'-'+arm,rows)
        for v,(_,k,w,t) in zip(raw,cases):
            if arm in ('intact','catalog') and (v['outcome'],v['end'])!=(w,t):raise ValueError((name,k,v['outcome'],w,v['end'],t))
            out.append(dict(actor=actor,foe=opponent,arm=arm,k=k,**v))
    with gzip.open(WORK/(name+'.gz'),'wt') as f:
        for row in out:f.write(json.dumps(row,separators=(',',':'))+'\n')
    print('verified',name,len(cases),flush=True)

def swaps():
    catalogs={}
    for foe in FOES:
        with gzip.open(WORK/(G4+'--'+foe+'.gz'),'rt') as f:
            rows=[json.loads(s) for s in f]
        catalogs[foe]={tuple(r['k']):r for r in rows if r['arm']=='catalog'}
    out=[]
    # Each pair uses the same recorded clock, both pings and seat. For donor controls
    # use a different action-seed repetition, matched across the two donor identities.
    alternatives={SEVEN:WT,WT:SEVEN,G3:SEVEN,COUNTER:SEVEN}
    for foe in FOES:
        cases=fixtures(G4,foe)
        records=[]
        for r,k,w,t in cases:
            c,rep,s,p,q=k
            otherkey=(c,(rep+1)%4,s,p,q)
            refs=[catalogs[foe][k],catalogs[foe][otherkey],catalogs[alternatives[foe]][otherkey]]
            if not refs[0]['cut'] or any(v['donor'] is None for v in refs):continue
            for arm,ref in zip(['swap-sham','swap-same','swap-other'],refs):
                records.append(([*r,arm,'-',','.join(map(str,ref['donor']))],k,w,t))
        results=invoke('swap-'+foe,[r[0] for r in records])
        for (row,k,w,t),v in zip(records,results):
            if row[8]=='swap-sham' and (v['outcome'],v['end'])!=(w,t):raise ValueError(('sham',foe,k))
            out.append(dict(actor=G4,foe=foe,arm=row[8],k=k,**v))
    with gzip.open(WORK/'swaps.gz','wt') as f:
        for row in out:f.write(json.dumps(row)+'\n')
    print('verified swap records',len(out),flush=True)

if __name__=='__main__':
    pairs=[(a,o) for a in (G4,G3,WT) for o in FOES if a!=o or a==WT]
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(run_pair,pairs))
    swaps()
