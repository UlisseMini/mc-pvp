"""Measure P(Y | recorded X,Z) without changing the joint forecast or fitted gates."""
import argparse
import json
from pathlib import Path

import numpy as np
import torch

from context_models import (
    GATES, HORIZONS, STANDARD, Gate, Heatmap, MetaHistory, data_for, forecast_gate, get_predictions,
    load_games, make_motion, map_only_pool, write_csv,
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('results', type=Path)
    args=parser.parse_args()
    torch.set_num_threads(4)
    root = Path(__file__).resolve().parents[3]
    settings = json.loads(Path(__file__).with_name('protocol.json').read_text())
    games=load_games(root/settings['input'])
    data=data_for(games)
    base=Heatmap()
    meta=MetaHistory()
    checkpoint=torch.load(root/'runtime/bedwars-forecast/advanced/heatmap-models.pt', weights_only=True)
    base.load_state_dict(checkpoint['heatmap'])
    meta.load_state_dict(checkpoint['meta-history'])
    refs=make_motion(data,settings['development'])
    pool=map_only_pool(data,[g for g in games if g['id'] in settings['development']],settings['seed'])
    rows=get_predictions(data,[g for g in games if g['id'] in settings['evaluation']],
                         base,meta,refs,pool,stride=7)
    binary=np.load(args.results/'experts-test.npz')
    key=lambda group,hi,t,name:(str(group),int(hi),int(t),str(name))
    lookup={key(g,h,t,p):i for i,(g,h,t,p) in enumerate(zip(binary['game'],binary['hindex'],binary['time'],binary['player']))}
    aligned=np.array([lookup[key(g,h,t,p)] for g,h,t,p in zip(rows['game'],rows['hindex'],rows['time'],rows['player'])])
    np.testing.assert_allclose(rows['exact'],binary['exact'][aligned],rtol=3e-4,atol=1e-7)
    np.testing.assert_allclose(rows['baselines'][:,:,:2],binary['baselines'][aligned,:,:2],rtol=3e-4,atol=1e-7)
    binary.close()
    weights=torch.load(args.results/'context-gates.pt',weights_only=True)
    estimates={key:rows['baselines'][:,i] for i,key in enumerate(STANDARD)}
    for name,(mode,component) in GATES.items():
        model=Gate(mode,component,rows['features'].shape[1])
        model.load_state_dict(weights[name])
        joint,w=forecast_gate(model,rows)
        horizontal=(w*rows['plane'][:,model.channels]).sum(1)
        estimates[name]=np.column_stack((joint,horizontal))
    records=[]
    for name,probs in estimates.items():
        if np.any(probs[:,0]-probs[:,2] > 2e-7):
            raise ValueError('Joint probability exceeded its horizontal marginal')
        losses=-np.log(probs[:,[0,2]].clip(min=1e-40))
        for game in settings['evaluation']:
            for i,h in enumerate(HORIZONS):
                for cohort, inside in [('all',True),('late',rows['late'])]:
                    keep=(rows['hindex']==i)&(rows['game']==game)&inside
                    if keep.any():
                        joint,xz=losses[keep].mean(0)
                        records.append({'model':name,'game':game,'horizon':h,'cohort':cohort,'n':int(keep.sum()),
                                        'block':float(joint),'horizontal':float(xz),'height_given_xz':float(joint-xz)})
    write_csv(args.results/'height-audit.csv',records)
    print(f'Verified {len(rows["time"])} matching 3D forecasts and exact horizontal marginals.',flush=True)


if __name__=='__main__':
    main()
