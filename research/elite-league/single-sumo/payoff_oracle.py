"""Measure the value of opponent identity conditional on the current visible state of the fight."""
import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

from assay import lower_contrast
from learner import write_json
from payoff_pair import FOLDER, FOES, collect, register

SEEDS=(19351,73429,259697)


def examples(history, informed):
    view=torch.from_numpy(history[:,:,-1].reshape(-1,25))
    identity=torch.eye(2).repeat(len(history),1) if informed else torch.zeros(len(view),2)
    return torch.cat((view,identity),dim=1)


def run():
    field=register()
    write_json(FOLDER/'oracle-protocol.json',dict(seeds=SEEDS,
        question='Does knowing the true opponent identity help choose the better of the two existing responses once the current observation is known?',
        arms='Matched 27-input softsign MLPs with 180 hidden units. Give two models real or zero identity bits during training and test. Swap only these bits as a negative control for the trained informed model. This is a privileged offline diagnostic.',
        training='Fit to the difference in outright wins between the two counterfactual responses using the same 2048 training examples, 24 epochs, Adam .001 and identical initialization and minibatches in each pair.',
        grade='Existing exposed 1024-game development panel, both seats, four RTT pairs. Average paired outright-win differences over training seeds and calculate the interval across the eight source recordings. Future outcomes supply only a separate hindsight ceiling.'),fixed=True)
    x,y,*_=collect('train',field)
    test,values,plain,donors,_=collect('dev',field)
    reward=torch.from_numpy(((y[:,:,1]==1).astype('f4')-(y[:,:,0]==1).astype('f4')).reshape(-1))
    models={}
    for seed in SEEDS:
        for informed in (False,True):
            torch.manual_seed(seed)
            net=nn.Sequential(nn.Linear(27,180),nn.Softsign(),nn.Linear(180,2))
            opt=torch.optim.Adam(net.parameters(),lr=.001)
            rng=np.random.default_rng(seed)
            data=examples(x,informed)
            for _ in range(24):
                for ids in np.array_split(rng.permutation(len(data)),len(data)//64):
                    gain=reward[ids]
                    if not gain.abs().any():continue
                    loss=(F.cross_entropy(net(data[ids]),(gain>0).long(),reduction='none')*gain.abs()).mean()
                    opt.zero_grad();loss.backward();nn.utils.clip_grad_norm_(net.parameters(),1.0);opt.step()
            def outcomes(inputs):
                with torch.no_grad():choice=net(inputs).argmax(-1).reshape(-1,2).numpy()
                return np.take_along_axis(values,choice[:,:,None],axis=2)[:,:,0]
            test_input=examples(test,informed)
            role='true-id' if informed else 'no-id'
            models.setdefault(role,[]).append(outcomes(test_input))
            if informed:
                test_input[:,-2:]=test_input[:,-2:].flip(1)
                models.setdefault('swapped-id',[]).append(outcomes(test_input))
    models['plain-wtap']=[plain]
    models['hindsight-two-response-ceiling']=[values[:,:,:2].max(axis=2)]
    def counts(v):
        return {name:[int((v[:,i]==j).sum()) for j in (1,.5,0)] for i,name in enumerate(FOES)}
    wins={role:np.mean(np.array(rows)==1,axis=0) for role,rows in models.items()}
    margins={}
    for role in ('no-id','swapped-id'):
        diff=(wins['true-id']-wins[role]).mean(axis=1)
        by=[float(diff[donors==i].mean()) for i in np.unique(donors)]
        margins[role]=dict(mean=float(np.mean(by)),recording_bounds=lower_contrast(by,.025),recordings=by)
    write_json(FOLDER/'oracle-development.json',dict(counts_by_training_seed={n:[counts(row) for row in rows] for n,rows in models.items()},
               outright_rates={n:float(arr.mean()) for n,arr in wins.items()},id_advantage=margins,
               field='reused development timings',fixture_count=len(plain)*2),fixed=True)


if __name__=='__main__':run()
