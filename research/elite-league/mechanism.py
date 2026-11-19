"""Isolate actions in the frozen policy, and construct a vanilla Ctrl feedback control."""
import argparse
import base64
import itertools
import json
import random
import struct
from pathlib import Path
from run import HERE,ROOT,calendars
from tournament import grid
from native_trials import run_batch

def unpack(net):
    family,blob=net.strip().split('.')
    raw=base64.b64decode(blob,validate=True)
    return family,list(struct.unpack('>'+str(len(raw)//4)+'f',raw))
def encode(f,w):
    return f+'.'+base64.b64encode(struct.pack('>'+str(len(w))+'f',*w)).decode()

def intervention(net,name):
    family,w=unpack(net)
    if family not in ('HYBRID','MLP'):raise ValueError('Action interventions require an MLP or hybrid')
    h=8;inputs=22;outputs=5;block=h+1;base=len(w)-outputs*block
    if name=='no-memory':
        if family!='HYBRID':raise ValueError('An MLP has no recurrent weights')
        for i in range(h):
            start=i*(1+inputs+h)+1+inputs
            w[start:start+h]=[0]*h
    else:
        channel,value={'ctrl-on':(1,4),'forward':(0,4),'click-on':(2,4),'no-jump':(3,-4),'no-strafe':(4,0)}[name]
        offset=base+channel*block
        w[offset:offset+block]=[value]+[0]*h
    return encode(family,w)

def feedback():
    inputs=22;block=inputs+1
    w=[0.]*(block*5)
    w[0]=1;w[block+1+9]=-1;w[2*block]=1;w[3*block]=-1
    return encode('LINEAR',w)

def prepare(out):
    out.mkdir(parents=True,exist_ok=True)
    source=(HERE/'frozen/hybrid.net').read_text()
    net={n:intervention(source,n) for n in ('ctrl-on','forward','click-on','no-jump','no-strafe','no-memory')}
    net['ctrl-feedback']=feedback()
    paths={}
    for n,weights in net.items():
        path=out/(n+'.net')
        if path.exists() and path.read_text().strip()!=weights:raise ValueError('Prior weights differ')
        path.write_text(weights+'\n');paths[n]=path
        f=ROOT/'policies'/('elite-mech-'+n+'.properties')
        base=(ROOT/'policies/research-hold20.properties').read_text().splitlines()
        data='\n'.join([l for l in base if not l.startswith('description=')]+[
            'description=Frozen tournament action intervention '+n,'duel_network='+weights,'duel_network_landmark=0'])+'\n'
        if f.exists() and f.read_text()!=data:raise ValueError(f'Prior policy {f} differs')
        f.write_text(data)
    return paths

def native(out,paths):
    rows=[];wt='research-wtap20';hold='research-hold20'
    for opp,arm,rep,rtt,side in itertools.product((wt,hold),(wt,hold,'elite-v1-hybrid',*['elite-mech-'+n for n in paths]),range(2),(0,80),'ab'):
        req=f'elite-mech-{opp}-{arm}-{rep}-{rtt}-{side}';a,b=(arm,opp) if side=='a' else (opp,arm)
        rows.append((req,dict(mode='sumo',profile='mmc',opponent=opp,arm=arm,rep=rep,rtt=rtt,side=side,
            command=f'lab sumo {a} {b} {rtt} {rtt} {req} preset=mmc reach=3 cps=20 tapdelay=0 aimdelay=0 aimnoise=0 aimrate=0')))
    random.Random(410133).shuffle(rows);run_batch(dict(rows),out/'native.json')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('out',type=Path);p.add_argument('mode',choices=('model','native'));a=p.parse_args()
    paths=prepare(a.out)
    if a.mode=='model':grid(a.out,'causal',dict.fromkeys(('wtap','hold'))|{'hybrid':HERE/'frozen/hybrid.net'}|paths,
        dict.fromkeys(('wtap','hold','trade','reset','pressure','select','counterstrafe','zest-strafe'))|{'hybrid':HERE/'frozen/hybrid.net'},calendars('development_selection_clocks'))
    else:native(a.out,paths)
