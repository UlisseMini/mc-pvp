"""Can an observation-only learner distinguish the two capacity-test opponents during one duel?"""
from opponent_id import DIR,oracle_worker
from learner import *
from league_tournament import interval
from assay import lower_contrast

OUT=DIR/'recognition'
HEADS=(1,6,12,24,48)
FOES=(INCUMBENT,BASE_COUNTER)

def fixtures(split,clocks=None):
    pairs=[];rng=random.Random(919816 if split=='train' else 981125 if split=='dev' else 882147)
    for c,tape in enumerate(clocks or CLOCKS[split]):
        for rep in range(1 if split=='sealed' else 4):
            for side in 'ab':
                for cell in range(4):
                    if split=='train':p,q=[rng.choice([j for j in range(151) if j not in (31,109)]) for _ in range(2)]
                    else:p,q=RTTS[cell]
                    args=[tape,side,p,q,rng.getrandbits(60),rng.getrandbits(60)]
                    pairs.append((c,[[DIR/'shared-mlp/initial.net',n,*args] for n in FOES]))
    return pairs

def dataset(split,clocks=None):
    suffix='-'+hashlib.sha256(json.dumps(clocks).encode()).hexdigest()[:12] if clocks else ''
    cache=BUILD/('recognition-'+split+suffix+'.npz')
    if cache.is_file():
        data=np.load(cache)
        return data['x'],data['length'],data['clock']
    worker=oracle_worker();pairs=fixtures(split,clocks)
    x=np.zeros((len(pairs),2,max(HEADS),25),dtype=np.float32);length=np.zeros((len(pairs),2),dtype=np.int32)
    try:
        for j in range(0,len(pairs),16):
            roll=worker.run([r for _,pair in pairs[j:j+16] for r in pair],True)
            for k,(_,_,arr) in enumerate(roll):
                i=j+k//2;side=k%2;n=min(len(arr),max(HEADS))
                x[i,side,:n]=arr[:n,:25];length[i,side]=n
            for i in range(j,min(j+16,len(pairs))):
                if not np.array_equal(x[i,0,0],x[i,1,0]):raise AssertionError('Assigned opponent leaks into the spawn observation')
    finally:worker.close()
    clocks=np.array([c for c,_ in pairs]);buf=io.BytesIO();np.savez_compressed(buf,x=x,length=length,clock=clocks);atomic(cache,buf.getvalue())
    return x,length,clocks

class Identifier(nn.Module):
    def __init__(self,memory):
        super().__init__();self.memory=memory
        self.encoder=nn.GRU(25,32,batch_first=True) if memory else nn.Linear(25,180)
        self.readout=nn.Linear(32 if memory else 180,2)
    def forward(self,x):
        features=self.encoder(x)[0] if self.memory else F.softsign(self.encoder(x))
        return self.readout(features)

def run():
    write_json(OUT/'protocol.json',dict(foes=FOES,observer='the same frozen 180-unit legal initializer on every trial',
        heads=HEADS,split='16 training and eight exposed development timing donors, separate match seeds and reserved dev RTTs',
        conditioning='include both opponents in a fixture at a horizon only if both survived; equal labels and same seat, clock and RTTs',
        training_seeds=[621,834,751],epochs=24,metric='balanced identification accuracy by horizon; no ring-out or memory-payoff claim'),fixed=True)
    train,tl,_=dataset('train');dev,dl,clocks=dataset('dev');results={};truth=torch.arange(2)
    for seed in (621,834,751):
        trace={}
        for name,memory in [('one-head',False),('history',True)]:
            torch.manual_seed(seed);model=Identifier(memory);opt=torch.optim.Adam(model.parameters(),lr=.002)
            rng=np.random.default_rng(seed);xt=torch.tensor(train)
            for _ in range(24):
                for ix in np.array_split(rng.permutation(len(xt)),max(1,len(xt)//32)):
                    y=model(xt[ix].flatten(0,1)).reshape(len(ix),2,max(HEADS),2)
                    loss=[]
                    for k in HEADS:
                        mask=np.min(tl[ix],axis=1)>=k
                        if mask.any():loss.append(F.cross_entropy(y[mask,:,k-1].reshape(-1,2),truth.repeat(int(mask.sum()))))
                    opt.zero_grad();torch.stack(loss).mean().backward();opt.step()
            checkpoint=OUT/f'{name}-{seed}.pt'
            if checkpoint.exists():
                old=torch.load(checkpoint,map_location='cpu',weights_only=False)
                if not all(torch.equal(v,old[k]) for k,v in model.state_dict().items()):raise ValueError('Classifier reproduction differs')
            else:
                buf=io.BytesIO();torch.save(model.state_dict(),buf);atomic(checkpoint,buf.getvalue())
            with torch.no_grad():pred=model(torch.tensor(dev).flatten(0,1)).reshape(len(dev),2,max(HEADS),2).argmax(-1).numpy()
            trace[name]={}
            for k in HEADS:
                allowed=dl.min(axis=1)>=k
                correct=(pred[allowed,:,k-1]==np.arange(2)).mean(axis=1)
                by=[float(correct[clocks[allowed]==c].mean()) for c in range(len(CLOCKS['dev'])) if np.any(clocks[allowed]==c)]
                trace[name][k]=dict(accuracy=float(correct.mean()),pairs=int(allowed.sum()),total=len(dl),donors=by,bounds=interval(by))
            if trace[name][1]['accuracy']!=.5:raise AssertionError('Spawn sham differs from balanced chance')
        trace['history_gain']={k:lower_contrast(np.array(trace['history'][k]['donors'])-trace['one-head'][k]['donors'],.025) for k in HEADS}
        results[seed]=trace
    write_json(OUT/'results.json',results,fixed=True)
if __name__=='__main__':run()
