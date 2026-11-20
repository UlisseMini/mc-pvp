"""Train a legal opponent-history gate behind a fixed strong opening on a frozen feed-forward field."""
import argparse
import hashlib
import io
import random

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

from assay import java_rows, lower_contrast
from learner import BUILD, CLOCKS, EVIDENCE, FIELD_PATH, ROOT, RTTS, Worker, atomic, write_json
from recognition import Identifier
from remote_league import OUT, read
from remote_round import entrants
from router_export import export_router

DEST=OUT/'stationary-field'
OPENER='sg003-long-g1'
GENERALIST='remote-001-mlp'
SEED=314071
REPS={'screen':1,'train':3,'dev':4,'prospective':2}


def register():
    field,_,_=entrants()
    field[GENERALIST]=read(OUT/'mlp/result.json')
    for source,ref in read(OUT/'protocol.json')['entrants'].items():
        final=EVIDENCE/'fixed-adversaries'/ref['sha256']/'final.net'
        field['reply-to-'+source]=dict(reference=str(final.relative_to(ROOT)),sha256=hashlib.sha256(final.read_bytes()).hexdigest())
    files={r[0]:r[1] for line in FIELD_PATH.read_text().splitlines() if len(r:=line.split('\t'))>1}
    registry={};excluded={}
    for name,ref in sorted(field.items()):
        family='script' if name in ('hold','wtap','legacy-wtap-clickphase') else (ROOT/files.get(ref['reference'],ref['reference'])).read_bytes().split(b'.',1)[0].decode()
        if family in ('MLP','LEAGUE_MLP180','PING_MLP','INPUT8','INPUT32') or name in ('wtap','hold'):
            registry[name]=dict(**ref,family=family)
        else:excluded[name]=family
    pool=[GENERALIST,'sg002-mix-generalist','sg002-mix-response','sg003-long-seven','sg003-long-incumbent','reply-to-g1','reply-to-seven','reply-to-incumbent']
    assert len({v['sha256'] for v in registry.values()})==len(registry)
    write_json(DEST/'protocol.json',dict(field=registry,excluded=excluded,opener=OPENER,responses=pool,repetitions=REPS,seed=SEED,
        interpretation='Two fixed scripted baselines and all distinct frozen feed-forward opponents in the registered field. Inputs can contain local motion, the previous action and hurt timers. No opponent uses a learned recurrent state or an expert router.',
        schedule='All arms execute the broad league-001 specialist for the first 24 decisions. The GRU then chooses whether to keep it or switch to one alternative for the rest of this single fight. Each new match starts with empty memory.',
        fitting='Choose one alternative on a training-only screen by its mean positive per-opponent gain over keeping the opener. Freeze the choice. Train GRU32, single-observation softsign MLP180 and single-observation tanh MLP32 from paired ring-out outcomes. Separate action seeds for screening, fitting and evaluation.',
        controls='Frozen opener, alternative always chosen after the identical opening, equal-data memoryless choosers, broad MLP and plain W-tap from spawn, known-ID lookup fixed from training, intact and wrong histories retaining the same last six inputs.',
        qualification='Same intact local state and recent six observations in history tests. Require simultaneous positive recording-level lower bounds for ring-outs versus both memoryless choosers, both fixed branches, broad MLP, wrong history, and same-opponent donor versus wrong-opponent donor. Report worst opponent and per-opponent regressions. Test the exact frozen actor on fresh timing recordings before claiming transfer. No adaptive-versus-adaptive grade.'),fixed=True)


def policy(spec,name):
    return spec.get('experts',{}).get(name) or spec['field'][name]


def register_strong_opening():
    spec=read(DEST/'protocol.json')
    controls=read(DEST/'frontier-protocol.json')['controls']
    updated=dict(spec,opener='original-mixture',experts=controls,
                 responses=['weakest-mixture',OPENER,*spec['responses']],crossfit_pair=True,opponent_payoff_targets=True,
                 schedule='The 131072-fight highest ranked fixed generalist plays the first 24 decisions. Either keep it or switch once for the rest of this duel. Same 27 frozen opponents, seeds, RTTs and recorded clocks as the original stationary study.',
                 fitting='For each possible response, learn the opponent-ID table and best fixed choice on half the training recordings. Measure its outright win gain over both the best fixed choice and keeping the opener on the other half, then reverse. Choose the response with the highest smaller gain. On new fitting seeds train all legal choosers on equal parts single-world payoff difference and mean difference for that opponent on other training recordings. Identity is available only for training labels. No ID at inference.',
                 rationale='In the first study the train-fitted known-ID table beat switching every time by only 34/6912 fights. Choose a pair that rewards identifying the opponent and preserve the newer strong opening.')
    write_json(DEST/'strong-opening/protocol.json',updated,fixed=True)


def plans(split,names,spec):
    from pathlib import Path
    clocks=(read(Path(spec.get('prospective_manifest',DEST/'prospective/calendars/manifest.json')))['selected'] if split=='prospective' else CLOCKS['train' if split in ('screen','train') else 'dev'])
    for i,clock in enumerate(clocks):
        rng=random.Random(SEED+{'screen':0,'train':10**6,'dev':2*10**6,'prospective':3*10**6}[split]+i*7703)
        allowed=[i for i in range(151) if i not in (31,109)]
        rows=[];keys=[]
        for rep in range(REPS[split]):
            for side in 'ab':
                for a,b in RTTS:
                    if split in ('train','screen'):a,b=rng.choice(allowed),rng.choice(allowed)
                    seeds=[rng.getrandbits(60),rng.getrandbits(60)]
                    keys.append((i,rep,side,a,b))
                    rows.extend([[policy(spec,spec['opener'])['reference'],spec['field'][n]['reference'],clock,side,a,b,*seeds] for n in names])
        yield keys,rows


def collect(split,spec,choices,folder=None):
    folder=DEST if folder is None else folder
    names=list(spec['field'])
    refs=[policy(spec,n)['reference'] for n in choices]
    fp=hashlib.sha256((folder/'protocol.json').read_bytes()+'|'.join(refs).encode()).hexdigest()[:12]
    w=Worker(current_runtime=True);assets=[]
    try:
        for index,(_,rows) in enumerate(plans(split,names,spec)):
            cache=BUILD/'stationary-field'/fp/(split+str(index)+'.npz')
            if not cache.exists():
                data=[];payoffs=[];baselines=[];early=[]
                all_forks=java_rows('ResponseReversal',rows,[','.join(refs)],java_opts=['-Dleague.commitHeads=24'])
                for j in range(0,len(rows),64):
                    block=rows[j:j+64]
                    # Each fork receives the same queues, random state and applied opening controls.
                    forks=all_forks[j:j+len(block)]
                    for r in forks:
                        payoffs.append([r.get('intact',r['full']),*[r.get(ref,r['full']) for ref in refs]])
                        early.append(r['cut'] is None)
                    if split=='screen':continue
                    for (outcome,_,heads),fork in zip(w.run(block,True),forks,strict=True):
                        assert outcome==fork['full'] and (len(heads)>=24)==(fork['cut'] is not None)
                        x=np.zeros((24,25),dtype='f4');n=min(24,len(heads));x[:n]=heads[:n,:25];data.append(x)
                    if split!='train':
                        controls=['wtap',spec['field'][GENERALIST]['reference']]
                        vals=[[r[0] for r in w.run([[c,*row[1:]] for row in block],False)] for c in controls]
                        baselines.extend(zip(*vals,strict=True))
                buf=io.BytesIO();np.savez_compressed(buf,x=np.array(data),y=np.array(payoffs),plain=np.array(baselines),early=np.array(early))
                atomic(cache,buf.getvalue())
            with np.load(cache) as saved:assets.append(tuple(saved[n] for n in ('x','y','plain','early')))
    finally:w.close()
    return [np.concatenate(v) for v in zip(*assets,strict=True)]


def train_model(kind,x,y,opponent_count=None):
    torch.manual_seed(SEED)
    if kind=='history':model=Identifier(True)
    elif kind=='one-head':model=Identifier(False)
    else:model=nn.Sequential(nn.Linear(25,32),nn.Tanh(),nn.Linear(32,2))
    t=torch.from_numpy(x if kind=='history' else x[:,-1:])
    gain=(y[:,1]==1).astype('f4')-(y[:,0]==1).astype('f4')
    if opponent_count is not None:
        rows=gain.reshape(len(CLOCKS['train']),-1,opponent_count)
        # A different timing recording supplies the opponent-level label. No ID enters the model.
        other=(rows.sum(axis=(0,1))-rows.sum(axis=1))/((len(rows)-1)*rows.shape[1])
        gain=(.5*rows+.5*other[:,None,:]).reshape(-1)
    gain=torch.from_numpy(gain)
    rng=np.random.default_rng(SEED);optimizer=torch.optim.Adam(model.parameters(),lr=.001)
    for _ in range(24):
        for ids in np.array_split(rng.permutation(len(t)),max(1,len(t)//128)):
            g=gain[ids]
            if not g.abs().any():continue
            loss=(F.cross_entropy(model(t[ids])[:,-1],(g>0).long(),reduction='none')*g.abs()).mean()
            optimizer.zero_grad();loss.backward();nn.utils.clip_grad_norm_(model.parameters(),1.);optimizer.step()
    return model


def train(folder=None):
    folder=DEST if folder is None else folder
    spec=read(folder/'protocol.json');names=list(spec['field']);count=len(names)
    _,payoffs,_,_=collect('screen',spec,spec['responses'],folder)
    rates=(payoffs.reshape(-1,count,payoffs.shape[1])==1).mean(axis=0)
    gains=(rates[:,1:]-rates[:,:1]).clip(0).mean(axis=0)
    if spec.get('crossfit_pair'):
        wins=payoffs.reshape(len(CLOCKS['train']),-1,count,payoffs.shape[1])==1
        estimates=[]
        for half in (0,1):
            test=np.arange(len(wins))%2==half
            fit=wins[~test].mean(axis=(0,1));held=wins[test].mean(axis=(0,1))
            choice_by_id=fit[:,1:]>fit[:,:1]
            by_id=np.where(choice_by_id,held[:,1:],held[:,:1]).mean(axis=0)
            fixed=np.where(fit[:,1:].mean(axis=0)>fit[:,0].mean(),held[:,1:].mean(axis=0),held[:,0].mean())
            estimates.append([by_id-fixed,by_id-held[:,0].mean()])
        gains=np.mean(estimates,axis=0).min(axis=0)
    choice=spec['responses'][int(gains.argmax())]
    write_json(folder/'selection.json',dict(alternative=choice,expected_known_opponent_gain=dict(zip(spec['responses'],gains)),
               by_opponent={name:list(row) for name,row in zip(names,rates,strict=True)}),fixed=True)
    x,y,_,_=collect('train',spec,[choice],folder)
    fitted={}
    for kind in ('history','one-head','tanh-head'):
        path=folder/(kind+'.pt')
        if not path.is_file():
            model=train_model(kind,x,y,count if spec.get('opponent_payoff_targets') else None)
            buf=io.BytesIO();torch.save(model.state_dict(),buf);atomic(path,buf.getvalue())
        fitted[kind]=torch.load(path,map_location='cpu',weights_only=True)
    known=((y.reshape(-1,count,2)==1).mean(axis=0)[:,1]>(y.reshape(-1,count,2)==1).mean(axis=0)[:,0]).astype(int)
    sources=[ROOT/policy(spec,n)['reference'] for n in (spec['opener'],spec['opener'],choice)]
    export_router(sources,fitted['history'],{'history':0,'keep':1,'switch':2,'recent-six':3},folder/'actors')
    # Compile the tanh MLP as a GRU whose update gate is exactly zero in Java double precision.
    net=Identifier(True);state={k:torch.zeros_like(v) for k,v in net.state_dict().items()}
    state['encoder.bias_ih_l0'][32:64].fill_(-1000)
    state['encoder.weight_ih_l0'][64:]=fitted['tanh-head']['0.weight'];state['encoder.bias_ih_l0'][64:]=fitted['tanh-head']['0.bias']
    state['readout.weight']=fitted['tanh-head']['2.weight'];state['readout.bias']=fitted['tanh-head']['2.bias']
    export_router(sources,state,{'tanh-head':0},folder/'actors')
    write_json(folder/'trained.json',dict(known_id=known.tolist(),actors={p.stem:hashlib.sha256(p.read_bytes()).hexdigest()
               for p in sorted((folder/'actors').glob('*.net'))},training_fights=len(y)),fixed=True)


@torch.no_grad()
def evaluate(split,folder=None):
    folder=DEST if folder is None else folder
    spec=read(folder/'protocol.json');names=list(spec['field']);n=len(names)
    choice=read(folder/'selection.json')['alternative'];frozen=read(folder/'trained.json')
    if split=='prospective':spec=dict(spec,prospective_manifest=str(folder/'prospective/calendars/manifest.json'))
    x,y,plain,early=collect(split,spec,[choice],folder)
    t=torch.from_numpy(x);y=y.reshape(-1,n,2);plain=plain.reshape(-1,n,2)
    if not np.all(x.reshape(-1,n,24,25)[:,:,0]==x.reshape(-1,n,24,25)[:,:1,0]):raise ValueError('Identity leak at spawn')
    gru=Identifier(True);gru.load_state_dict(torch.load(folder/'history.pt',weights_only=True))
    single=Identifier(False);single.load_state_dict(torch.load(folder/'one-head.pt',weights_only=True))
    small=nn.Sequential(nn.Linear(25,32),nn.Tanh(),nn.Linear(32,2));small.load_state_dict(torch.load(folder/'tanh-head.pt',weights_only=True))
    _,h=gru.encoder(t[:,:18]);reps=REPS[split];by=h.reshape(-1,reps,8,n,32)
    if by.shape[0]<2:raise ValueError('Need multiple timing recordings')
    controls=by.roll(1,1)
    # Every opponent appears once as a donor in each fixture, and never for itself.
    rng=np.random.default_rng(SEED+1381)
    wrong=controls.clone().reshape(-1,n,32)
    same=controls.reshape_as(wrong)
    for i in range(len(wrong)):
        perm=rng.permutation(n)
        wrong[i,perm]=same[i,np.roll(perm,1)]
    def route(hidden):
        enc,_=gru.encoder(t[:,-6:],hidden.reshape_as(h).contiguous())
        return gru.readout(enc[:,-1]).argmax(-1).numpy().reshape(-1,n)
    decisions={'history':route(by),'same':route(controls),'wrong':route(wrong),'recent-six':route(torch.zeros_like(by)),
               'one-head':single(t[:,-1:])[:,-1].argmax(-1).numpy().reshape(-1,n),
               'tanh-head':small(t[:,-1]).argmax(-1).numpy().reshape(-1,n),
               'known-id':np.broadcast_to(frozen['known_id'],y.shape[:2])}
    scores={k:np.take_along_axis(y,d[:,:,None],axis=2)[:,:,0] for k,d in decisions.items()}
    scores.update({'keep':y[:,:,0],'switch':y[:,:,1],'wtap':plain[:,:,0],'broad-mlp':plain[:,:,1]})
    keys=[row for group,_ in plans(split,names,spec) for row in group]
    def tally(v):return [int(np.sum(v==i)) for i in (1,.5,0)]
    def compare(a,b):
        diff=(scores[a]==1).astype(float)-(scores[b]==1)
        donor=diff.reshape(by.shape[0],-1).mean(axis=1)
        return dict(mean=float(donor.mean()),bounds=lower_contrast(donor,.05/7),recordings=donor.tolist())
    comparisons=[('history',k) for k in ('one-head','tanh-head','keep','switch','broad-mlp','wrong')]+[('same','wrong')]
    bounds={a+' minus '+b:compare(a,b) for a,b in comparisons}
    cells={a:{name:dict(wdl=tally(v[:,i]),seat={s:tally(v[[k[2]==s for k in keys],i]) for s in 'ab'},
                        rtt={str(p)+'/'+str(q):tally(v[[k[3:]==(p,q) for k in keys],i]) for p,q in RTTS})
              for i,name in enumerate(names)} for a,v in scores.items()}
    weak=min(names,key=lambda f:cells['history'][f]['wdl'][0])
    result=dict(fights_per_arm=y.shape[0]*n,ended_before_decision=int(early.sum()),weakest=weak,cells=cells,
        totals={k:tally(v) for k,v in scores.items()},switch_fraction={f:float(v) for f,v in zip(names,decisions['history'].mean(axis=0),strict=True)},
        primary=bounds,passed=all(r['bounds'][0]>0 for r in bounds.values()))
    # Test the packaged controllers in ordinary, unforked games before accepting their outcomes.
    worker=Worker(current_runtime=True)
    try:
        _,opening_checks=next(plans(split,names,spec))
        for row in opening_checks[:2*n:n//2]:
            ordinary=worker.run([row],True)[0][2][:24]
            test=worker.run([[folder/'actors/history.net',*row[1:]]],True)[0][2][:24]
            if not np.array_equal(ordinary[:,:26],test[:,:26]):raise ValueError('Opening inputs or buttons changed')
        for role in frozen['actors']:
            actor=folder/'actors'/(role+'.net')
            if hashlib.sha256(actor.read_bytes()).hexdigest()!=frozen['actors'][role]:raise ValueError('Frozen weights changed')
            for i,(_,fixture) in enumerate(plans(split,names,spec)):
                full=[[actor,*row[1:]] for row in fixture]
                got=[r[0] for j in range(0,len(full),32) for r in worker.run(full[j:j+32],False)]
                if not np.array_equal(got,scores[role].reshape(by.shape[0],-1)[i]):raise ValueError('Full match differs for '+role)
    finally:worker.close()
    write_json(folder/(split+'-result.json'),result,fixed=True)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('task',choices=('register','run','prospective'));p.add_argument('--strong-opening',action='store_true')
    args=p.parse_args();folder=DEST/'strong-opening' if args.strong_opening else DEST
    if args.task=='register':
        if args.strong_opening:register_strong_opening()
        else:register()
    elif args.task=='run':train(folder);evaluate('dev',folder)
    else:evaluate('prospective',folder)
