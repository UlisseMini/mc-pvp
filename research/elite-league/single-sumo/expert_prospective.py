"""Prospectively confirm the reward-only three- and seven-opponent rematch results."""
import hashlib
import sys
import tempfile
import time
from pathlib import Path

import numpy as np
import torch
from scipy.stats import t

from expert_population import Actor, SEED, STUDY, play
from learner import ROOT, RTTS, Worker, write_json
from remote_league import read
from series_eval import series, tally
from series_targeted import DEST as LIB
from series_trace import CHANNELS
from specialist_diagnosis import fixture_inputs, signal
from specialist_router import visible_feedback

DEST = STUDY / 'prospective'
MODELS = {'small': STUDY/'small'/f'seed-{SEED}'/'memory/u512.pt',
          'seven': STUDY/'reward-debug/seven'/f'q-{SEED}/u2048.pt'}


def register():
    path = DEST/'assay.json'
    if path.exists():
        return
    latest = max(int(f.stem[1:]) for f in (ROOT/'runtime/results').glob('m*.json') if f.stem[1:].isdigit())
    write_json(path, dict(first_future_id=latest+6, calendar_count=24, minimum_gap_ms=60000,
        models={g:hashlib.sha256(f.read_bytes()).hexdigest() for g,f in MODELS.items()},
        field_hash=hashlib.sha256((LIB/'protocol.json').read_bytes()).hexdigest(), reps=2,
        selection='Enroll the first 24 valid bot-only sumo timing recordings starting at least sixty seconds after the previous included recording ended. All begin after both reward-only networks and all physical experts froze. Use timing only, never enroll matches based on outcomes.',
        scheduled='For each group and timing donor play two independent ten-game series per opponent and arm with alternating seats and all four RTT pairs. Score memory, reset, literal ID to the dedicated actor, both fixed broad controls, each dedicated as a fixed control across opponents and W-tap. Primary contrast is learned memory minus original-mixture outright wins; second is memory cleared. Report estimates with interval clustered by source timing fixture. Retain only scores and predeclared decisions in evidence. No training or checkpoint choice on these calendars.'), fixed=True)


def reserve():
    from calendars_holdout import collect
    from remote_fit import HOST, REMOTE, RSYNC, SSH, invoke
    from remote_league import OUT
    import shlex
    register()
    while not (DEST/'calendars/manifest.json').exists():
        try:
            collect(DEST)
        except FileNotFoundError as e:
            if str(e.filename) != str(ROOT/'runtime/status.json'):
                raise
            time.sleep(3)
    manifest = DEST/'calendars/manifest.json'
    files = [str((DEST/'assay.json').relative_to(ROOT)), str(manifest.relative_to(ROOT)), *read(manifest)['selected']]
    with tempfile.TemporaryDirectory() as temp:
        listing=Path(temp)/'files'
        listing.write_text(''.join(f+'\n' for f in files))
        invoke([*RSYNC,'--files-from='+str(listing),str(ROOT)+'/',HOST+':'+REMOTE+'/'])
    sub=str(DEST.relative_to(OUT))
    command = ("from pathlib import Path; from remote_jobs import start; d=Path("+repr(sub)+");"
               "\nfor g in ('small','seven'): start('ep-prospective-'+g,'expert_prospective.py',[g],d/(g+'.json'))")
    invoke([*SSH,'cd '+REMOTE+'/research/elite-league/single-sumo && ~/mc-sumo-league/venv/bin/python -c '+shlex.quote(command)])
    from remote_import import wait_for
    for group in MODELS:
        wait_for([sub+'/'+group+'.json'])


@torch.inference_mode()
def assess(group):
    spec = read(LIB/'protocol.json')
    reg = read(DEST/'assay.json')
    if hashlib.sha256((LIB/'protocol.json').read_bytes()).hexdigest()!=reg['field_hash']:
        raise ValueError('The field changed after registration')
    config=read(STUDY/'protocol.json')
    names = config['small'] if group=='small' else list(config['pairs'])
    counters = [config['pairs'][name] for name in names]
    if hashlib.sha256(MODELS[group].read_bytes()).hexdigest()!=reg['models'][group]:
        raise ValueError('The actor changed after registration')
    model=Actor(len(names),False)
    model.load_state_dict(torch.load(MODELS[group],weights_only=True))
    model.eval()
    arms=counters+['original-mixture','weakest-mixture','wtap']
    clocks=read(DEST/'calendars/manifest.json')['selected']
    nf=len(names)
    if len(set(clocks))!=reg['calendar_count']:
        raise ValueError('Incomplete calendar enrollment')
    worker=Worker(current_runtime=True)
    scores={}; contexts=[];choices=[]
    try:
        for i,clock in enumerate(clocks):
            for rep in range(reg['reps']):
                events=[];pay=[];ctx=[]
                for side,own,other,x,y in fixture_inputs('expert-prospective-762711',rep,i):
                    em=[];val=[]
                    for j,actor in enumerate(arms):
                        ref='wtap' if actor=='wtap' else spec['experts'][actor]['reference']
                        plan=[[ref,spec['field'][foe]['reference'],clock,'ab'[side],own,other,x,y] for foe in names]
                        games=worker.run(plan,j<nf)
                        val.append([w for w,_,_ in games])
                        if j<nf:
                            em.append([np.concatenate((signal(z[:,CHANNELS])[360:],visible_feedback(np.asarray(w),np.asarray(ms),np.array([own,other]))))
                                       for w,ms,z in games])
                    pay.append(np.stack(val,1))
                    events.append(np.stack(em,1))
                    ctx.append((side,own,other))
                raw=np.stack(pay).transpose(1,0,2)
                obs=torch.from_numpy(np.stack(events).transpose(1,0,2,3))
                ct=np.broadcast_to(np.array(ctx)[None],(nf,10,3)).copy()
                contexts.append(ct)
                result={a:raw[:,:,j] for j,a in enumerate(arms)}
                result['dedicated']=raw[np.arange(nf)[:,None],np.arange(10)[None,:],np.arange(nf)[:,None]]
                for label,erase in [('memory',False),('erased',True)]:
                    a,_,won,*_=play(model,obs,torch.from_numpy(raw[:,:,:nf]),torch.from_numpy(ct[:,:,1:]).float(),True,erase=erase)
                    result[label]=won.numpy()
                    if not erase:choices.append(torch.bincount(a.flatten(),minlength=nf).tolist())
                for name,value in result.items(): scores.setdefault(name,[]).append(value)
    finally:
        worker.close()
    w={k:np.stack(v) for k,v in scores.items()};ctx=np.stack(contexts)
    out={k:dict(games=tally(v),series=tally(series(v.reshape(-1,10))),opener=tally(v[:,:,0]),rematches=tally(v[:,:,1:]),
          opponents={foe:tally(v[:,j]) for j,foe in enumerate(names)},seats={s:tally(v[ctx[...,0]==j]) for j,s in enumerate('ab')},
          rtt={f'{a}/{b}':tally(v[(ctx[...,1]==a)&(ctx[...,2]==b)]) for a,b in RTTS}) for k,v in w.items()}
    contrasts={}
    for other in ('original-mixture','weakest-mixture','dedicated','erased'):
        for label,col in [('all',slice(None)),('rematches',slice(1,None))]:
            donor=((w['memory'][:,:,col]==1).astype(float)-(w[other][:,:,col]==1)).reshape(len(clocks),-1).mean(1)
            half=float(t.ppf(.975,len(donor)-1)*donor.std(ddof=1)/np.sqrt(len(donor)))
            mean=float(donor.mean())
            contrasts[other+' '+label]=dict(mean=mean,source_timing_95=[mean-half,mean+half])
    write_json(DEST/(group+'.json'),dict(scores=out,effects=contrasts,calendars=len(clocks),expert_choices=np.sum(choices,axis=0).tolist(),
        controls='All arms use the same simulator physics input and seed within each recorded timing fixture; labels and unchosen branches are absent from the decision.'),fixed=True)


if __name__=='__main__':
    if sys.argv[1]=='register':register()
    elif sys.argv[1]=='reserve':reserve()
    else:assess(sys.argv[1])
