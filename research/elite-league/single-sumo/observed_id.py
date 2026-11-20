"""Hold the 20-way known-ID controller fixed and measure the price of inferring its ID from play."""
import argparse
import fcntl
import gzip
import io
import json
import os
import random
import subprocess
import sys
import tempfile
from pathlib import Path
import numpy as np
import torch
from torch import nn
from torch.nn import functional as F
from learner import BUILD,CLOCKS,EVIDENCE,FIELD,HERE,ROOT,RTTS,Worker,atomic,freeze,write_json
from oracle_student import TEACHERS,sha,teachers,teacher_scores,button_kl
from opponent_id import DIR,runtime
from student_evaluation import STUDY, bound, capture, register as register_timing

OUT=EVIDENCE/'observed-id'
HEADS=(6,24,48)
SEEDS=(62157,83317,75167)
OPENER=DIR/'shared-mlp/initial.net'

def register():
    register_timing()
    write_json(OUT/'protocol.json',dict(
        population=FIELD,teacher={f:sha(TEACHERS/(f+'.net')) for f in FIELD},opener=sha(OPENER),
        horizons=list(HEADS),primary_head=24,primary_seed=SEEDS[0],training_seeds=list(SEEDS),
        training='192 balanced worlds x all 20 opponents on training clocks; identical physical opener with independent seat and both RTTs across worlds',
        train_scope='classify opponents from own observations, no opponent identifier at inference; frozen GRU32 and last-observation MLP180 after 24 epochs; equal weights per opponent at each horizon',
        branches='true ID, inferred ID, last-observation ID, opposite earlier memory with final six observations fixed, independently trained blind response, fixed cap-ramp, plain W-tap and same-state sham',
        opposite='fixed cyclic next eligible different opponent inside same world, identical pings/side/physical random draws. Omit the contrast on unmatched rows and report coverage.',
        ringout='complete both counterfactual fighters, queues and clocks from the same legal opening. If a fight already ended, every treatment keeps the ordinary result.',
        splits='development: 8 old timings x 2 seats x 4 RTT pairs; prospective: separately registered 32 spaced timings x 2 seats x 4 RTT pairs; new simulator random seeds on each panel',
        inference='one average per timing recording, confidence across spaced recordings; original eight development recordings overlap. Three trained seeds for identification, first seed only for ring-outs.',
        limitation='known-ID gives access to information before it may be visible; ID-specific PPO responses can still be inferior to a blind or dedicated response.'
    ),fixed=True)

def worlds(split):
    if split=='train':
        rng=random.Random(710581);options=[x for x in range(151) if x not in (31,109)]
        return [[rng.choice(CLOCKS['train']),rng.choice('ab'),rng.choice(options),rng.choice(options),
                 rng.getrandbits(60),rng.getrandbits(60)] for _ in range(192)]
    clocks=CLOCKS['dev'] if split=='dev' else json.loads((STUDY/'calendars/manifest.json').read_text())['selected']
    rng=random.Random(289713 if split=='dev' else 972141)
    return [[clock,side,a,b,rng.getrandbits(60),rng.getrandbits(60)] for clock in clocks for side in 'ab' for a,b in RTTS]

def cache(split):
    folder=BUILD/'observed-id';folder.mkdir(exist_ok=True)
    path=folder/(split+'-observations.npz')
    if path.exists():
        with np.load(path) as saved:return saved['x'],saved['length'],saved['full']
    schedule=worlds(split);shape=(len(schedule),len(FIELD))
    x=np.zeros((*shape,max(HEADS),25),np.float32);length=np.zeros(shape,np.int32);full=np.zeros(shape,np.float32)
    worker=Worker(runtime_directory=BUILD/runtime()['simulator'])
    try:
        for i,fixture in enumerate(schedule):
            rows=worker.run([[OPENER,f,*fixture] for f in FIELD],True)
            for j,(score,_,data) in enumerate(rows):
                n=min(len(data),max(HEADS));x[i,j,:n]=data[:n,:25];length[i,j]=len(data);full[i,j]=score
            if not np.all(x[i,:,0]==x[i,0,0]):raise ValueError('Opponent identity visible at spawn')
    finally:worker.close()
    buf=io.BytesIO();np.savez_compressed(buf,x=x,length=length,full=full);atomic(path,buf.getvalue())
    return x,length,full

class Identifier(nn.Module):
    def __init__(self,recurrent):
        super().__init__();self.recurrent=recurrent
        self.encoder=nn.GRU(25,32,batch_first=True) if recurrent else nn.Linear(25,180)
        self.readout=nn.Linear(32 if recurrent else 180,len(FIELD))
    def forward(self,x):
        h=self.encoder(x)[0] if self.recurrent else F.softsign(self.encoder(x))
        return self.readout(h)

def fit():
    register();x,length,_=cache('train');x=torch.from_numpy(x).flatten(0,1);length=torch.from_numpy(length.flatten())
    truth=torch.arange(len(FIELD)).repeat(len(worlds('train')))
    for seed in SEEDS:
        for recurrent in (False,True):
            path=OUT/f'{"gru" if recurrent else "mlp"}-{seed}.pt'
            if path.exists():continue
            torch.manual_seed(seed);net=Identifier(recurrent);opt=torch.optim.Adam(net.parameters(),lr=.002)
            rng=np.random.default_rng(seed)
            for _ in range(24):
                for indices in np.array_split(rng.permutation(len(x)),len(x)//96):
                    out=net(x[indices]);terms=[]
                    for head in HEADS:
                        mask=length[indices]>=head
                        if mask.any():terms.append(F.cross_entropy(out[mask,head-1],truth[indices][mask]))
                    opt.zero_grad();torch.stack(terms).mean().backward();nn.utils.clip_grad_norm_(net.parameters(),1);opt.step()
            buf=io.BytesIO();torch.save(net.state_dict(),buf);atomic(path,buf.getvalue())

def load_model(seed,kind):
    net=Identifier(kind=='gru');net.load_state_dict(torch.load(OUT/f'{kind}-{seed}.pt',weights_only=True))
    return net.eval()

@torch.no_grad()
def predict(x,length,head,seed):
    worlds_count,n=x.shape[:2];t=torch.from_numpy(x[:,:,:head]).flatten(0,1)
    gru=load_model(seed,'gru');mlp=load_model(seed,'mlp')
    intact=gru(t)[:,-1].argmax(-1);last=mlp(t[:,-1:])[:,-1].argmax(-1)
    swapped=intact.clone();available=np.zeros((worlds_count,n),bool)
    if head>6:
        history=gru.encoder(t[:,:-6])[1];donors=np.arange(len(t)).reshape(worlds_count,n)
        for w in range(worlds_count):
            for j in range(n):
                donor=next(((j+k)%n for k in range(1,n) if length[w,(j+k)%n]>=head-6),None)
                if donor is not None:
                    donors[w,j]=w*n+donor;available[w,j]=True
        tail=t[:,-6:]
        def choose(h):return gru.readout(gru.encoder(tail,h)[0][:,-1]).argmax(-1)
        if not torch.equal(intact,choose(history)):raise ValueError('Sham memory changed an ID choice')
        swapped=choose(history[:,donors.flatten(),:].contiguous())
    return {k:v.reshape(worlds_count,n).numpy() for k,v in [('inferred',intact),('last-head',last),('swapped',swapped)]},available

def classifier_scores(split):
    x,length,_=cache(split);answers={};truth=np.broadcast_to(np.arange(len(FIELD)),length.shape)
    for seed in SEEDS:
        answers[seed]={}
        for head in HEADS:
            choices,_=predict(x,length,head,seed);eligible=length>=head
            answers[seed][head]={'eligible':int(eligible.sum()),'total':int(eligible.size)}
            for k,pred in choices.items():
                matrix=np.zeros((len(FIELD),len(FIELD)),dtype=int)
                np.add.at(matrix,(truth[eligible],pred[eligible]),1)
                answers[seed][head][k]={'correct':float((truth[eligible]==pred[eligible]).mean()),'confusion':matrix.tolist()}
    write_json(OUT/(split+'-identification.json'),answers,fixed=True)

def classpath():
    source=HERE/'ResponseReversal.java';base=BUILD/runtime()['simulator']
    out=BUILD/('observed-id-'+sha(source)[:15]+'-'+base.name)
    if not (out/'ResponseReversal.class').exists():
        with tempfile.TemporaryDirectory(dir=BUILD) as tmp:
            classes=Path(tmp)/'classes';classes.mkdir()
            subprocess.run(['javac','-J-XX:ActiveProcessorCount=1','-cp',base,'-d',classes,source],check=True)
            try:classes.rename(out)
            except OSError:
                if not out.is_dir():raise
    return os.pathsep.join(map(str,(out,base)))

def branches(rows,head,cp):
    with tempfile.TemporaryDirectory(dir=BUILD) as tmp:
        plan=Path(tmp)/'plan';out=Path(tmp)/'results'
        plan.write_text(''.join('\t'.join(map(str,r))+'\n' for r in rows))
        subprocess.run(['java','-XX:ActiveProcessorCount=1','-XX:+UseSerialGC','-Xmx650m',f'-Dleague.commitHeads={head}',
                        '-cp',cp,'ResponseReversal',plan,out,'--per-row'],check=True)
        return [json.loads(l) for l in out.read_text().splitlines()]

def ringouts(split,head,first,last):
    dest=OUT/split/f'head-{head}'/f'worlds-{first:03}-{last:03}.json.gz'
    if dest.exists():return
    x,length,full=cache(split);plan=worlds(split);choices,match=predict(x[first:last],length[first:last],head,SEEDS[0]);keys=[];rows=[]
    for i,fixture in enumerate(plan[first:last]):
        for j,foe in enumerate(FIELD):
            ref=lambda who:str((TEACHERS/(FIELD[who]+'.net')).relative_to(ROOT))
            lookup={k:ref(v[i,j]) for k,v in choices.items()}
            lookup.update({'true-id':ref(j),'blind':str((DIR/'blind/u256/unknown.net').relative_to(ROOT)),
                           'cap-ramp':'sumo-cap-ramp','wtap-fork':'wtap'})
            keys.append(lookup);rows.append([OPENER,foe,*fixture,*dict.fromkeys(lookup.values())])
    results=branches(rows,head,classpath());out=[]
    worker=Worker(runtime_directory=BUILD/runtime()['simulator'])
    try:
        for i,fixture in enumerate(plan[first:last]):
            direct=worker.run([['wtap',f,*fixture] for f in FIELD],False)
            oracle=worker.run([[TEACHERS/(f+'.net'),f,*fixture] for f in FIELD],False)
            for j,foe in enumerate(FIELD):
                idx=i*len(FIELD)+j;v=results[idx];got=v['cut'] is not None
                if got!=(length[first+i,j]>=head) or v['full']!=full[first+i,j]:raise ValueError('Ordinary match diverged from opening')
                scores={k:v[n] if got else v['full'] for k,n in keys[idx].items()}
                scores.update({'wtap-spawn':direct[j][0],'true-id-spawn':oracle[j][0],'sham':v['full']})
                out.append(dict(world=first+i,foe=foe,eligible=got,donor=bool(match[i,j]),scores=scores))
    finally:worker.close()
    freeze(dest,gzip.compress(json.dumps(out,separators=(',',':')).encode(),mtime=0))

def summarize(split,head):
    p=OUT/split/f'head-{head}';raw=[row for f in sorted(p.glob('worlds-*.json.gz')) for row in json.loads(gzip.decompress(f.read_bytes()))]
    nworld=len(worlds(split));n=len(FIELD)
    if [(r['world'],r['foe']) for r in raw]!=[(i,f) for i in range(nworld) for f in FIELD]:raise ValueError('Incomplete matched field')
    scores={k:np.array([r['scores'][k] for r in raw]).reshape(nworld,n) for k in raw[0]['scores']}
    control=scores['wtap-spawn'];cells={}
    def tally(v):return dict(wins=int((v==1).sum()),draws=int((v==.5).sum()),losses=int((v==0).sum()),score=float(v.mean()))
    for k,v in scores.items():cells[k]={f:tally(v[:,j]) for j,f in enumerate(FIELD)}
    diffs={}
    for a,b in [('true-id','inferred'),('inferred','blind'),('inferred','last-head'),('true-id-spawn','true-id'),('inferred','swapped')]:
        diff=scores[a]-scores[b]
        valid=np.array([r['eligible'] and r['donor'] for r in raw]).reshape(nworld,n) if b=='swapped' else np.ones_like(diff,dtype=bool)
        blocks=[]
        for value,mask in zip(diff.reshape(-1,8,n),valid.reshape(-1,8,n)):
            if mask.any():blocks.append(float(value[mask].mean()))
        if len(blocks)>1:diffs[a+' minus '+b]={**bound(blocks),'covered':int(valid.sum()),'total':int(valid.size)}
    write_json(p/'summary.json',dict(cells=cells,field={k:tally(v) for k,v in scores.items()},paired=diffs,
        eligible=sum(r['eligible'] for r in raw),fixtures=len(raw),matched_plain_wtap=tally(control),
        scope='causal comparison on identical full physical state for post-opening branches; from-spawn score includes acquisition'),fixed=True)

def governed(*args):
    subprocess.run([sys.executable,HERE.parent/'cpu_limit.py','--policy','nice','-n','8',sys.executable,__file__,*map(str,args)],check=True)

def run():
    register()
    with (OUT/'study.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX);governed('fit')
        for split in ('dev','spaced'):
            if split=='spaced':capture()
            governed('classify',split)
            for h in (24,6,48):
                for i in range(0,len(worlds(split)),8):governed('score',split,h,i,i+8)
                summarize(split,h)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('op');parser.add_argument('args',nargs='*');a=parser.parse_args()
    if a.op=='run':run()
    elif a.op=='fit':fit()
    elif a.op=='classify':classifier_scores(*a.args)
    elif a.op=='score':ringouts(a.args[0],*map(int,a.args[1:]))
    else:raise ValueError(a.op)
