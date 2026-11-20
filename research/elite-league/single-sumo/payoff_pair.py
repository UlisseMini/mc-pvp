"""Can an observation-only router realize a measured two-opponent payoff advantage?"""
import io
import random
import sys

import numpy as np
import torch

from assay import java_rows,lower_contrast
from learner import BUILD,CLOCKS,RTTS,Worker,atomic,write_json
from recognition import Identifier
from remote_league import OUT,read
from remote_round import entrants

FOLDER=OUT/'payoff-pair'
FOES=('sg002-mentor-gru','sumo-seven-first')
EXPERTS=('sg002-mix-generalist','sg003-long-seven')
OPENER='sg003-long-g1'
SEED=2467187


def register():
    field,_,_=entrants()
    write_json(FOLDER/'protocol.json',dict(foes={n:field[n] for n in FOES},experts={n:field[n] for n in EXPERTS},
               opener=field[OPENER],source='Retrospective screen of 35 common-field policies. Among opponent pairs in the top 15 by mean wins, this pair had the largest from-spawn gap between a known-ID choice of disjoint archive actors and the strongest fixed disjoint actor.',
               hypothesis='A common strong opener may erase that advantage before head 24. Measure the known-ID and fixed-opener controls on identical continuations.',
               fitting='Two action responses, reward-supervised. First 24 legal observations, absolute difference of outright ring-out labels as the sample weight. One 32-wide GRU and one 180-wide one-head MLP; seed 2467187, Adam .001, 24 epochs. Final weights only. No opponent ID supplied during play.',
               controls='Fixed responses A and B, keeping the opener, W-tap from spawn and fork, known-ID favored assignment, same-opponent and opposite-opponent older histories with recent six heads preserved, and zero memory.',
               split='16 training recordings with independent sampled pings and eight repetitions; eight exposed development recordings with both seats, four paired RTTs and eight repetitions. A positive development result needs separate prospective confirmation.',
               primary='Outright wins, paired by matched fixture then original timing recording. For development qualification require positive simultaneous lower bounds over the best fixed policy chosen on training, wrong history, and same-versus-wrong donor history. Report all candidate fixed arms.'),fixed=True)
    return field


def clocks_for(split):
    return read(FOLDER/'prospective/calendars/manifest.json')['selected'] if split=='prospective' else CLOCKS[split]


def collect(split,field):
    clocks=clocks_for(split)
    cached=BUILD/('payoff-pair-v1-'+split+'.npz')
    if cached.is_file():
        with np.load(cached) as data:return tuple(data[k] for k in ('x','reward','plain','donors','early'))
    opts=[n for n in range(151) if n not in (31,109)]
    experts=[field[n]['reference'] for n in EXPERTS]
    w=Worker(current_runtime=True)
    xx=[];yy=[];plain=[];donors=[];early=[]
    try:
        for i,clock in enumerate(clocks):
            rows=[];rng=random.Random(SEED+i*17977+{'train':0,'dev':88,'prospective':91517}[split])
            for _ in range(8):
                for seat in 'ab':
                    for rtt in RTTS:
                        own,foe=(rng.choice(opts),rng.choice(opts)) if split=='train' else rtt
                        seeds=[rng.getrandbits(60),rng.getrandbits(60)]
                        rows.extend([[field[OPENER]['reference'],field[n]['reference'],clock,seat,own,foe,*seeds] for n in FOES])
            fork=java_rows('ResponseReversal',rows,[','.join([*experts,'wtap'])],java_opts=['-Dleague.commitHeads=24'])
            for j in range(0,len(rows),16):
                group=rows[j:j+16]
                control=w.run([['wtap',*r[1:]] for r in group],False)
                for r,(_,_,heads),wt in zip(fork[j:j+16],w.run(group,True),control,strict=True):
                    x=np.zeros((24,25),dtype='f4');n=min(len(heads),24);x[:n]=heads[:n,:25]
                    active=r['cut'] is not None
                    if active!=(n==24):raise ValueError('Input and continuation disagree on the landmark')
                    xx.append(x);donors.append(i);plain.append(wt[0]);early.append(not active)
                    yy.append([r[a] if active else r['full'] for a in (*experts,'intact','wtap')])
    finally:w.close()
    x=np.asarray(xx).reshape(-1,2,24,25)
    if not np.array_equal(x[:,0,0],x[:,1,0]):raise ValueError('Opponent identity leaks into the initial inputs')
    buf=io.BytesIO()
    np.savez_compressed(buf,x=x,reward=np.asarray(yy).reshape(-1,2,4),plain=np.asarray(plain).reshape(-1,2),
                        donors=np.asarray(donors)[::2],early=np.asarray(early).reshape(-1,2))
    atomic(cached,buf.getvalue())
    return collect(split,field)


def fit(x,ys,memory):
    t=torch.from_numpy(x.reshape(-1,24,25))
    outcomes=(ys==1).astype('f4')
    advantages=torch.from_numpy((outcomes[:,:,1]-outcomes[:,:,0]).reshape(-1))
    torch.manual_seed(SEED);model=Identifier(memory)
    optimizer=torch.optim.Adam(model.parameters(),lr=.001);rng=np.random.default_rng(SEED)
    for _ in range(24):
        for idx in np.array_split(rng.permutation(len(t)),max(1,len(t)//64)):
            gain=advantages[idx]
            if not gain.abs().any():continue
            logits=model(t[idx] if memory else t[idx,-1:])[:,-1]
            loss=(torch.nn.functional.cross_entropy(logits,(gain>0).long(),reduction='none')*gain.abs()).mean()
            optimizer.zero_grad();loss.backward();torch.nn.utils.clip_grad_norm_(model.parameters(),1.0);optimizer.step()
    key='gru' if memory else 'mlp';state=FOLDER/(key+'.pt');buf=io.BytesIO()
    torch.save(model.state_dict(),buf)
    if not state.exists():atomic(state,buf.getvalue())
    elif any(not torch.equal(v,torch.load(state,weights_only=True)[k]) for k,v in model.state_dict().items()):
        raise ValueError('Frozen payoff router failed to reproduce')
    return model


@torch.no_grad()
def measure(split='dev'):
    field=register();train,ty,*_=collect('train',field)
    with torch.enable_grad():
        recurrent=fit(train,ty,True);mlp=fit(train,ty,False)
    x,y,plain,donors,early=collect(split,field)
    t=torch.from_numpy(x.reshape(-1,24,25));_,state=recurrent.encoder(t[:,:-6])
    paired=state.reshape(1,-1,2,32)
    # Rotate complete paired replications within the same donor, seat and RTT cell.
    matched=paired.reshape(1,len(clocks_for(split)),8,8,2,32).roll(1,dims=2).reshape_as(paired)
    def choose(h):
        logits=recurrent.readout(recurrent.encoder(t[:,-6:],h.reshape_as(state).contiguous())[0][:,-1])
        return logits.argmax(-1).reshape(-1,2).numpy()
    def select(c):return np.take_along_axis(y,c[:,:,None],axis=2)[:,:,0]
    decisions={'history':choose(paired),'same-history':choose(matched),'wrong-history':choose(matched.flip(2)),
               'zero-history':choose(torch.zeros_like(paired)),'one-head':mlp(t[:,-1:])[:,-1].argmax(-1).reshape(-1,2).numpy()}
    scores={name:select(c) for name,c in decisions.items()}
    arms=['response-A','response-B','keep-opener','wtap-from-fork']
    scores.update({n:y[:,:,k] for k,n in enumerate(arms)})
    scores.update({'known-ID':np.column_stack((y[:,0,0],y[:,1,1])),'wtap-from-spawn':plain})
    fixed=arms[int((ty==1).mean(axis=(0,1)).argmax())]
    def counts(v):return dict(wins=int((v==1).sum()),draws=int((v==.5).sum()),losses=int((v==0).sum()))
    tables={k:{f:counts(v[:,j]) for j,f in enumerate(FOES)} for k,v in scores.items()}
    def contrast(a,b):
        d=((scores[a]==1).astype(float)-(scores[b]==1)).mean(axis=1)
        by=[float(d[donors==i].mean()) for i in range(len(clocks_for(split)))]
        return dict(mean=float(np.mean(by)),interval=lower_contrast(by,.05/3),donors=by)
    primary={a+' minus '+b:contrast(a,b) for a,b in [('history',fixed),('history','wrong-history'),('same-history','wrong-history')]}
    result=dict(cells=tables,strongest_fixed_on_training=fixed,primary=primary,
                qualified_in_development=all(r['interval'][0]>0 for r in primary.values()),
                known_id_advantage=contrast('known-ID',fixed),one_head_difference=contrast('history','one-head'),
                full_fixtures=len(t),ended_before_route=int(early.sum()),choice_B_by_opponent=decisions['history'].mean(axis=0).tolist())
    if split=='prospective':result['qualified_on_prospective_calendars']=result.pop('qualified_in_development')
    write_json(FOLDER/('development.json' if split=='dev' else 'prospective-result.json'),result,fixed=True)
    from router_export import export_router
    from learner import ROOT
    refs=[ROOT/field[n]['reference'] for n in (OPENER,*EXPERTS)]
    exported=export_router(refs,recurrent.state_dict(),{'history':0,'response-A':1,'response-B':2},FOLDER/'actors')
    # Check the ordinary Java game against the independently forked payoff target, cell by cell.
    # Recreate the predeclared development fixture seeds for all three deployable actors.
    if split=='dev':
        worker=Worker(current_runtime=True)
        try:
            for label,path in exported.items():
                direct=[]
                for i,clock in enumerate(clocks_for(split)):
                    rng=random.Random(SEED+i*17977+88);rows=[]
                    for rep in range(8):
                        for seat in 'ab':
                            for a,b in RTTS:
                                seeds=[rng.getrandbits(60),rng.getrandbits(60)]
                                rows.extend([[path,field[n]['reference'],clock,seat,a,b,*seeds] for n in FOES])
                    direct.extend(v[0] for j in range(0,len(rows),32) for v in worker.run(rows[j:j+32],False))
                if not np.array_equal(scores[label].reshape(-1),direct):raise ValueError('Exported '+label+' diverges from the forks')
            write_json(FOLDER/'parity.json',dict(actors=list(exported),fights_per_actor=len(t),identical=True),fixed=True)
        finally:worker.close()


if __name__=='__main__':measure('prospective' if sys.argv[1:]==['prospective'] else 'dev')
