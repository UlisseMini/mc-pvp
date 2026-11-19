"""Measure strafe readout changes on both finalists' actual own Minecraft observation histories."""
from collections import Counter,defaultdict
import json
import math
import subprocess
import sys
import time

from adaptation import dump,sha
from audit import captures
from mmc_edge_ablation import BASE,CONTACT,DEST,SPLIT
from mmc_league import EVIDENCE,WORK,WT
from mmc_scorecard import record
from run import HERE,ROOT,compile_model

STAGE=EVIDENCE/'edge-confirm'


def read(p):return json.loads(p.read_text())


def score(row,fit,cp):
    mid=row['mid'];seat=row['side'];g=read(ROOT/'runtime/results'/(mid+'.json'))
    path,=(ROOT/'runtime/pov'/mid).glob(seat+'-client-*-events.jsonl.gz')
    heads=[v for v in captures.events(path,{'duel_network'}) if v.get('match')==mid]
    if not heads or not all(h['released'] and h['proposed']==h['action'] for h in heads):raise ValueError('Incomplete regulation policy '+mid)
    lines=[]
    for h in heads:
        v=h['observation']
        lines.append(' '.join(map(str,[*v[:9],int(v[9]),int(v[10]),*v[11:],h['action']])))
    cmd=['java','-Xms12m','-Xmx64m','-XX:+UseSerialGC','-XX:ActiveProcessorCount=1','-cp',str(cp),
         'EdgeFitAblation','--native',str(CONTACT/(BASE+'.net')),str(SPLIT/(fit+'.net')),'fit' if row['arm']==fit else 'parent']
    sides=subprocess.run(cmd,input='\n'.join(lines)+'\n',text=True,capture_output=True,check=True).stdout.splitlines()
    origin=heads[0]['observation'];cx=(origin[0]+origin[3])/2;cz=(origin[2]+origin[5])/2
    if (cx,cz)!=((g['arena']+1)*4096+.5,.5):raise ValueError('Wrong opening center')
    entry=None;counts=Counter();first=None;window=[0,0,0,0]
    for h,output in zip(heads,sides,strict=True):
        if not g['start']<=h['time']<=g['finish']:continue
        a,b=map(int,output.split());v=h['observation'];x,z=v[0]-cx,v[2]-cz
        r=math.hypot(x,z);out=(x*v[6]+z*v[8])/r if r else 0
        if entry is None and any(age!=-1 for age in v[11:13]) and r>=3.5 and out>=.02:entry=h['tick']
        region='before' if entry is None else 'inside' if h['tick']<entry+6 else 'after'
        if region=='inside':
            project=-x*(v[5]-v[2])+z*(v[3]-v[0]);sign=(project>0)-(project<0)
            window[0]+=1
            if sign:window[1]+=1;window[2]+=int(sign==a);window[3]+=int(sign==b)
        if a!=b:
            counts[region]+=1
            if first is None:first=dict(region=region,ms=h['time']-g['start'],radius=r,outward=out,parent=a,fit=b)
    return dict(verified=len(heads),first=first,counts={k:counts[k] for k in ('before','inside','after')},window=window)


def run():
    fit=read(SPLIT/'selection.json')['contender'];protocol=DEST/'native-protocol.json'
    dump(protocol,dict(confirmation=sha(SPLIT/'confirmation.json'),diagnosis=sha(DEST/'summary.json'),
        rule='Use every physically eligible registered edge-confirm bout. On both the parent and fitted player, replay both networks with the action actually applied fed back to both. Require exact reproduction of the logged network action on every head. Record differences before, inside, and after the first six-input 3.5-block outward landmark and each local strafe relative to center. Include full-interval plain W-tap and nominee outcomes by opponent, seat, and added RTT. Native games are independent; the restricted continuations are model diagnostics.'))
    until=read(WORK/'admission-deadline.json')['unix_time']+600
    while not (STAGE/'outcomes.json').exists():
        if time.time()>until:return
        time.sleep(15)
    cp=compile_model()
    subprocess.run([sys.executable,str(HERE/'cpu_limit.py'),'--audit',sys.executable,__file__,'--score',str(cp)],check=True)


def run_score(fit,cp):
    rows=read(STAGE/'outcomes.json')['records'];cache=WORK/'edge-native-shadow';cache.mkdir(exist_ok=True);cells=defaultdict(list);records=[]
    for r in rows:
        v=dict(r,score=1 if r['win'] else .5 if r['draw'] else 0)
        if r['arm'] in (BASE,fit):
            p=cache/(r['mid']+'.json')
            if not p.exists():dump(p,score(r,fit,cp))
            v['shadow']=read(p)
        for cut in ('all',f"{r['rtt']}/{r['side']}"):cells[r['arm'],r['opponent'],cut].append(v)
        records.append(v)
    stats={}
    for (a,o,cut),rs in cells.items():
        comparison=cells[WT,o,cut]
        if len(comparison)!=len(rs):raise ValueError('Unequal plain W-tap comparison')
        first=Counter(r['shadow']['first']['region'] for r in rs if r.get('shadow',{}).get('first'))
        stats['/'.join((a,o,cut))]=dict(controller=record([r['score'] for r in rs]),plain_wtap=record([r['score'] for r in comparison]),
            shadow_rounds=sum('shadow' in r for r in rs),first_disagreement=first,
            cumulative_disagreements={k:sum(r['shadow']['counts'][k] for r in rs if 'shadow' in r) for k in ('before','inside','after')})
    dump(DEST/'native.json',dict(source=sha(STAGE/'native.json.gz'),stats=stats,matches=records))


if __name__=='__main__':
    if '--score' in sys.argv:run_score(read(SPLIT/'selection.json')['contender'],sys.argv[-1])
    else:run()
