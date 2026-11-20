"""Learn an in-duel response from counterfactual ring-out rewards, with no opponent ID labels."""
from pathlib import Path
from recognition import Identifier
from recognition_payoff import FOLDER,FOES,CUTOFF,dataset,responders,outcomes,lower_contrast
from learner import torch,np,json,io,atomic,write_json
import sys

OUT=FOLDER/'reward-trained'
SEEDS=(407,719,993)

def protocol():
    write_json(OUT/'protocol.json',dict(
        inputs='25 lawful features from the first 24 local heads; identical regulation opener',
        teachers='two frozen PPO response policies, both primed on the same actual controls',
        objective='weighted binary cross-entropy on which response earned more ring-out reward from the exact same world, weight is absolute score difference; no opponent identity target',
        sources='all stored 16 training donor clocks, no development or prospective forks in fitting',
        architectures=['one 32-unit GRU','one 180-unit memoryless MLP'],seeds=SEEDS,
        optimizer='Adam lr .001, batch size 64 full prefixes, 24 epochs, final snapshot',
        primary=407,comparator='stronger of the two fixed responses on training clocks, previous ID-supervised router, current-head model, reset and opposite histories, and W-tap',
        status='supplementary diagnostic; prospective scores use the same timing panel as the frozen ID-classifier test',
    ),fixed=True)

def fit(seed,memory):
    x,length,_=dataset('train');r=outcomes('train',responders())
    mask=length.flatten()>=CUTOFF
    t=torch.from_numpy(x[:,:,:CUTOFF].reshape(-1,CUTOFF,25)[mask])
    gain=torch.tensor((r['routed-1']-r['routed-0']).flatten()[mask],dtype=torch.float32)
    torch.manual_seed(seed);m=Identifier(memory);opt=torch.optim.Adam(m.parameters(),lr=.001)
    rng=np.random.default_rng(seed);logs=[]
    for epoch in range(24):
        loss_sum=0.
        for ix in np.array_split(rng.permutation(len(t)),max(1,len(t)//64)):
            if not gain[ix].abs().any():continue
            logits=m(t[ix])[:,-1];loss=(torch.nn.functional.cross_entropy(logits,(gain[ix]>0).long(),reduction='none')*gain[ix].abs()).mean()
            opt.zero_grad();loss.backward();torch.nn.utils.clip_grad_norm_(m.parameters(),1.0);opt.step();loss_sum+=float(loss.detach())
        logs.append(loss_sum)
    name=('gru' if memory else 'mlp')+'-'+str(seed)
    dest=OUT/(name+'.pt')
    if dest.exists():
        prior=torch.load(dest,weights_only=True);assert all(torch.equal(v,prior[k]) for k,v in m.state_dict().items())
    else:
        buf=io.BytesIO();torch.save(m.state_dict(),buf);atomic(dest,buf.getvalue())
    write_json(OUT/(name+'-fit.json'),dict(examples=len(t),unequal=int((gain!=0).sum()),loss_sums=logs),fixed=True)
    return m

@torch.no_grad()
def route(model,t,memory):
    if not memory:return {'one-head':model(t[:,-1:])[:,-1].argmax(-1).numpy()}
    _,h=model.encoder(t[:,:-6]);opposite=h.reshape(1,-1,2,32).flip(2).reshape_as(h).contiguous()
    return {name:model.readout(model.encoder(t[:,-6:],hidden)[0][:,-1]).argmax(-1).numpy()
        for name,hidden in [('intact',h),('reset',torch.zeros_like(h)),('opposite',opposite)]}

def measure(split):
    protocol()
    clocks=None if split=='dev' else json.loads((FOLDER/'prospective/calendars/manifest.json').read_text())['selected']
    r=outcomes(split,responders(),clocks)
    x,length,donors=dataset(split,clocks);t=torch.from_numpy(x[:,:,:CUTOFF].reshape(-1,CUTOFF,25))
    previous=json.loads((FOLDER/('routed-'+split+'.json')).read_text())
    report={}
    def counts(v):return dict(wins=int((v==1).sum()),draws=int((v==.5).sum()),losses=int((v==0).sum()),score=float(v.mean()))
    for seed in SEEDS:
        choices={}
        for mem,name in [(True,'gru'),(False,'mlp')]:
            state=OUT/(name+'-'+str(seed)+'.pt')
            if split=='dev':m=fit(seed,mem)
            else:
                m=Identifier(mem);m.load_state_dict(torch.load(state,weights_only=True))
            choices.update(route(m,t,mem))
        arms={k:np.where(v.reshape(-1,2)==0,r['routed-0'],r['routed-1']) for k,v in choices.items()}
        arms['fixed']=r[previous['fixed']];arms['cap-ramp']=r['cap-ramp'];arms['wtap-from-fork']=r['wtap']
        cells={k:{f:counts(v[:,j]) for j,f in enumerate(FOES)} for k,v in arms.items()}
        cells['wtap-from-spawn']=previous['cells']['wtap-from-spawn'];cells['ID-supervised']=previous['cells']['history']
        gains={}
        for k,v in arms.items():
            if k=='intact':continue
            dif=(arms['intact']-v).mean(axis=1);blocks=[dif[donors==c].mean() for c in np.unique(donors)]
            gains[k]=[float(np.mean(blocks)),*lower_contrast(blocks,.0125)]
        report[str(seed)]=dict(cells=cells,gains=gains,qualifies=bool(gains['fixed'][1]>0 and gains['opposite'][1]>0))
    write_json(OUT/(split+'.json'),report,fixed=True)

if __name__=='__main__':measure(sys.argv[1] if len(sys.argv)>1 else 'dev')
