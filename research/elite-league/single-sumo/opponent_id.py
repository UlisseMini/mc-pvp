"""Privileged opponent-ID controls. An ID is supplied by the experiment, never inferred from a fight."""
import argparse
import copy
import fcntl
from functools import lru_cache
import time
from learner import *
from league_tournament import historical

DIR=EVIDENCE/'id-oracle'
N=len(FIELD)

def runtime():
    manifest=DIR/'runtime.json'
    if not manifest.exists():
        sim=json.loads((BUILD/'sim-current.json').read_text())['directory']
        bridge=json.loads((BUILD/'forge-current.json').read_text())
        write_json(manifest,dict(simulator=sim,forge=bridge,
            source_hash=hashlib.sha256((BUILD/sim/'source-pins.json').read_bytes()).hexdigest()),fixed=True)
    return json.loads(manifest.read_text())

def oracle_worker():
    return Worker(runtime_directory=BUILD/runtime()['simulator'])

class IdentifiedMLP(nn.Module):
    """A one-hot ID adds one selected column of the first linear layer."""
    def __init__(self,base):
        super().__init__()
        self.base=copy.deepcopy(base)
        self.context=nn.Parameter(torch.zeros(N,self.base.encoder.out_features))

    def sequences(self,x,ids):
        bias=F.embedding(ids.clamp_min(0),self.context)*(ids>=0)[:,None]
        return self.base.head(F.softsign(self.base.encoder(x.data)+bias))

    def export(self,path,i):
        net=self.base;enc=net.encoder
        bias=enc.bias if i<0 else enc.bias+self.context[i]
        weights=np.concatenate([a.detach().numpy().ravel() for a in (enc.weight,bias,net.head.weight,net.head.bias)])
        atomic(path,f'LEAGUE_MLP{enc.out_features}.'.encode()+base64.b64encode(weights.astype('>f4').tobytes())+b'\n')

class RoutedMLP(nn.Module):
    """Completely independent encoders, action heads and critics, routed by a known opponent."""
    def __init__(self,base):
        super().__init__()
        self.experts=nn.ModuleList(copy.deepcopy(base) for _ in FIELD)

    def sequences(self,x,ids):
        out=x.data.new_zeros(len(ids),6)
        for idx in ids.unique().tolist():
            if idx<0:raise ValueError('A routed expert needs an explicit ID')
            mask=ids==idx;net=self.experts[idx]
            out[mask]=net.head(F.softsign(net.encoder(x.data[mask])))
        return out

    def export(self,path,i):self.experts[i].export(path)

@lru_cache(maxsize=1024)
def opponent_order(cycle):
    permutation=list(range(N))
    random.Random(486581+cycle).shuffle(permutation)
    return tuple(permutation)

def schedule(step,mode,paths):
    # Repeated independent permutations give exactly one exposure per opponent per 20 matches.
    out=[];ids=[];rng=random.Random(276833+step*9011)
    options=[x for x in range(151) if x not in (31,109)]
    for episode in range(64*step,64*(step+1)):
        cycle,k=divmod(episode,N)
        f=opponent_order(cycle)[k];idx=-1 if mode=='blind' else f;ids.append(idx)
        out.append([paths[idx],FIELD[f],rng.choice(CLOCKS['train']),rng.choice('ab'),rng.choice(options),rng.choice(options),
                    rng.getrandbits(60),rng.getrandbits(60)])
    return out,ids

def architecture(mode):
    torch.manual_seed(3176221)
    base=Actor('mlp');state=torch.load(DIR/'shared-mlp/trainer.pt',map_location='cpu',weights_only=False)
    base.load_state_dict(state['net'])
    if mode=='wide':
        # New readout columns start at zero; random extra hidden rows break symmetry once gradients arrive.
        enc=nn.Linear(25,360);head=nn.Linear(360,6)
        with torch.no_grad():
            enc.weight[:180].copy_(base.encoder.weight);enc.bias[:180].copy_(base.encoder.bias)
            head.weight[:,:180].copy_(base.head.weight);head.weight[:,180:].zero_();head.bias.copy_(base.head.bias)
        base.encoder=enc;base.head=head
    return RoutedMLP(base) if mode=='routed' else IdentifiedMLP(base)

def exports(net,folder,mode):
    ids=[-1] if mode=='blind' else list(range(N))+([-1] if mode in ('wide','informed') else [])
    paths={}
    for i in ids:
        tag='unknown' if i<0 else FIELD[i]
        paths[i]=folder/(tag+'.net');net.export(paths[i],i)
    return paths

def plan():
    spec=dict(roles=['blind','informed','routed'],ids=FIELD,shared_teacher=BASE_COUNTER,
         shared_initialization='same 32-update behavior-cloned softsign MLP; initial ID contributions zero',
         comparison='same schedule, physical sampling seeds, adversary seeds, optimizer and training budget per arm',
         batches=256,duels_per_update=64,training='uniform balanced opponent permutations; independent per-match RTTs',
         informed='one-hot opponent ID folded into the hidden bias for a duel',blind='same architecture with ID permanently masked',
         routed='20 independent MLPs with the same initialization; total duel budget matched across arms',
         wrong_id='balanced rotation among the other 19 live IDs; actual opponent and all fight seeds fixed',
         masked_id='off-support stress test for informed arm; directly compared with trained blind arm',
         primary='paired correct minus wrong ID and correct minus trained blind field score, with donor-clock intervals',
         provisional_update=64,primary_update=256,versioned_observation_count=25,eligible_for_best_sumo=False)
    write_json(DIR/'protocol.json',spec,fixed=True)
    return spec

def train_to(mode,end):
    p=DIR/mode;p.mkdir(exist_ok=True,parents=True)
    with (p/'fit.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        _train_to(mode,end,p)

def _train_to(mode,end,p):
    spec=plan();net=architecture(mode)
    opt=torch.optim.Adam(net.parameters(),lr=.00035);save=p/'optimizer.pt';start=0;log=[]
    if save.exists():
        state=torch.load(save,map_location='cpu',weights_only=False);net.load_state_dict(state['net'])
        opt.load_state_dict(state['opt']);start=state['step'];log=state['log'];write_json(p/'progress.json',log[-1])
    roller=oracle_worker() if mode=='wide' else Worker()
    try:
        for step in range(start,end):
            t=time.monotonic();paths=exports(net,roller.folder,mode);rows,ids=schedule(step,mode,paths)
            data=roller.run(rows,True);ep=prepare(data)
            episodes=[torch.cat((v,v.new_full((len(v),1),idx)),dim=1) for idx,v in zip(ids,ep)]
            stats=train_update(net,opt,episodes,5176201+step,conditioned=True)
            log.append(dict(update=step+1,duels=64*(step+1),heads=sum(map(len,episodes)),seconds=time.monotonic()-t,
                            score=float(np.mean([r[0] for r in data])),**stats))
            buf=io.BytesIO();torch.save(dict(net=net.state_dict(),opt=opt.state_dict(),step=step+1,log=log),buf)
            atomic(save,buf.getvalue());write_json(p/'progress.json',log[-1])
            if (step+1) in (spec['provisional_update'],spec['primary_update']):
                export_checkpoint(p,net,mode,step+1)
        if end==start and end in (64,256):export_checkpoint(p,net,mode,end)
    finally:roller.close()

def export_checkpoint(p,net,mode,step):
    dst=p/f'u{step:03d}'
    with tempfile.TemporaryDirectory(dir=BUILD) as tmp:
        paths=exports(net,Path(tmp),mode)
        for _,source in paths.items():freeze(dst/source.name,source.read_bytes())
    n=sum(x.numel() for x in net.parameters() if x.requires_grad)
    write_json(dst/'schema.json',dict(architecture=mode,trainable_parameters=n,update=step,physically_delivered_inputs=25,
            referee_selects_branch_by_id=mode!='blind'),fixed=True)

def self_check():
    torch.manual_seed(1282);base=Actor('mlp');net=IdentifiedMLP(base)
    with torch.no_grad():net.context.normal_(0,.38)
    route=RoutedMLP(base)
    with torch.no_grad():route.experts[2].encoder.bias.add_(net.context[2])
    with tempfile.TemporaryDirectory(dir=BUILD) as tmp:
        tmp=Path(tmp);paths={}
        for i in (-1,2,11):
            p=tmp/f'{i}.net';net.export(p,i);paths[i]=p
        rp=tmp/'r.net';route.export(rp,2)
        assert rp.read_bytes()==paths[2].read_bytes()
        w=Worker()
        try:
            rows=[[p,INCUMBENT,CLOCKS['train'][0],'a',41,97,193,29] for p in paths.values()]
            rolled=w.run(rows,True)
            errs=[]
            for i,r in zip(paths,rolled):
                x=torch.from_numpy(r[2][:,:25]);seq=pack_sequence([x])
                y=net.sequences(seq,torch.full((len(x),),i,dtype=torch.long)).detach()
                err=float(abs(y-torch.from_numpy(r[2][:,26:32])).max());errs.append(err)
                if err>1e-5:raise AssertionError(err)
            # A change of ID cannot directly change the raw opening observation.
            if not all(np.array_equal(r[2][0,:25],rolled[0][2][0,:25]) for r in rolled):raise AssertionError('ID leaked into POV')
            if np.array_equal(rolled[1][2][0,26:32],rolled[2][2][0,26:32]):raise AssertionError('ID did not reach the action/value heads')
            print('Masked and two distinct IDs matched Java; independent routed expert agreed byte-for-byte.',errs)
        finally:w.close()

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('op',choices=['train','check','plan']);p.add_argument('role',nargs='?');p.add_argument('end',type=int,nargs='?')
    a=p.parse_args()
    if a.op=='check':self_check()
    elif a.op=='plan':plan()
    else:train_to(a.role,a.end)
