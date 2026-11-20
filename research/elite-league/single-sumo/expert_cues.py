"""What does the reward-only GRU learn from the first bout against three real counter-targets?"""
import numpy as np
import torch

from expert_population import Actor,SEED,STUDY,load
from learner import write_json
from series_eval import tally
from series_hidden import donor_rows
from specialist_ablation import bootstrap


@torch.inference_mode()
def run():
    tr=load('train','small');xx,w,c,wt,_,fi,_,foes=load('dev','small')
    n,r,_,nf,na=w.shape;nr=n*r*nf;idx=torch.arange(nr)
    model=Actor(na,False);model.load_state_dict(torch.load(STUDY/'small'/f'seed-{SEED}'/'memory/u512.pt',weights_only=True))
    seat=c[:,:,None,:,:].expand(n,r,nf,10,3).reshape(-1,10,3);p=seat[:,:,1:].float()
    obs=xx.permute(0,1,3,2,4,5).reshape(-1,10,na,198);world=w.permute(0,1,3,2,4).reshape(-1,10,na)
    same,wrong=donor_rows(c,nf);blank=torch.zeros(1,nr,48)
    first=model.readout(blank[0],p[:,0])[0].argmax(1)
    if not torch.equal(first,first[wrong]) or not torch.equal(first,first[same]):raise ValueError('Mismatched opening expert')
    actual=obs[idx,0,first];actions={};outcomes={}
    for label,physics,feedback in [('real',idx,idx),('same',same,same),('wrong',wrong,wrong),
            ('same-motion',same,idx),('wrong-motion',wrong,idx),('same-feedback',idx,same),('wrong-feedback',idx,wrong)]:
        token=model.event(torch.cat((actual[physics,:192],actual[feedback,192:]),1),first,na)
        h=model.memory(model.encoder(token)[:,None],blank)[1][0]
        a=model.readout(h,p[:,1])[0].argmax(1);actions[label]=a;outcomes[label]=world[idx,1,a].numpy()
    bins=lambda p:2*(p[...,0]>=70)+(p[...,1]>=70)
    bt=bins(tr[2][...,1:].numpy());tw=tr[1].numpy();info=np.stack([np.stack([(tw[bt==k]==s).sum(0)+1 for s in (0,.5,1)],-1) for k in range(4)]).astype(float)
    prob=info/info.sum(-1,keepdims=True);table=(tw==1);pay=np.stack([table[bt==k].mean(0) for k in range(4)])
    pb=bins(p.numpy());opening=world[idx,0,first];symbols=(2*opening).long().numpy()
    posterior=prob[pb[:,0],:,first.numpy(),symbols];posterior/=posterior.sum(1,keepdims=True)
    a=np.einsum('bf,bfa->ba',posterior,pay[pb[:,1]]).argmax(1)
    outcomes['result-only-table']=world[idx,1,a].numpy();actions['result-only-table']=torch.from_numpy(a)
    outcomes['wtap']=wt[:,:,1,fi].reshape(-1).numpy()
    contrasts={}
    for l,b in [('same-motion','wrong-motion'),('same-feedback','wrong-feedback'),('real','result-only-table'),('same','wrong')]:
        delta=((outcomes[l]==1).astype(float)-(outcomes[b]==1)).reshape(n,r,-1).mean(2)
        contrasts[l+' minus '+b]=dict(delta=float(delta.mean()),conditional_95=bootstrap(delta))
    mask=np.tile(np.arange(nf),n*r)
    byfoe={}
    for j,f in enumerate(foes):
        byfoe[f]={str(z):dict(n=int(((mask==j)&(opening.numpy()==z)).sum()),actions=torch.bincount(actions['real'][(mask==j)&(opening.numpy()==z)],minlength=na).tolist()) for z in (0.,.5,1.)}
    write_json(STUDY/'small/second-game.json',dict(foes=foes,opening_action_counts=torch.bincount(first,minlength=na).tolist(),response_by_first_result=byfoe,
        scores={l:tally(z) for l,z in outcomes.items()},effects=contrasts,changes_vs_real={l:int((a!=actions['real']).sum()) for l,a in actions.items()},
        interpretation='Motion-only swaps keep the recipient result, rounded duration and pings. Feedback-only swaps keep recipient motion. The two views can then disagree. Whole-history replacement is the coherent intervention. All choices precede the ordinary second bout; W-tap played both bouts from scratch.'),fixed=True)


if __name__=='__main__':run()
