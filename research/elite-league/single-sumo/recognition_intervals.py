"""Uncertainty of the frozen three-way comparison, paired by original timing recording."""
import numpy as np
from scipy import stats
from recognition_payoff import FOLDER, responders, outcomes, dataset, decisions
from learner import json,write_json

def run(split):
    clocks=None if split=='dev' else json.loads((FOLDER/'prospective/calendars/manifest.json').read_text())['selected']
    r=outcomes(split,responders(),clocks);x,_,donor=dataset(split,clocks);choices=decisions(x)
    fixed=json.loads((FOLDER/('routed-'+split+'.json')).read_text())['fixed']
    def scored(route):return np.where(route==0,r['routed-0'],r['routed-1'])
    arms={'history':scored(choices['history']),'fixed':r[fixed],'swapped':scored(choices['opposite-history']),
          'cap-ramp':r['cap-ramp'],'wtap-from-fork':r['wtap']}
    from recognition import fixtures
    from opponent_id import oracle_worker
    w=oracle_worker()
    try:
        rows=[['wtap',*row[1:]] for _,pair in fixtures(split,clocks) for row in pair]
        wt=[]
        for j in range(0,len(rows),32):wt.extend(k[0] for k in w.run(rows[j:j+32],False))
    finally:w.close()
    arms['wtap-from-spawn']=np.array(wt).reshape(len(x),2)
    block={k:np.array([v[donor==i].mean() for i in np.unique(donor)]) for k,v in arms.items()}
    def bounds(v,level):
        radius=stats.t.ppf((1+level)/2,len(v)-1)*stats.sem(v)
        return [float(v.mean()-radius),float(v.mean()+radius)]
    def summary(v):
        return dict(mean=float(v.mean()),cluster_95=bounds(v,.95),scores=v.tolist())
    result=dict(timing_recordings=len(np.unique(donor)),total_duels=2*len(x),
        description='Student t intervals across timing-donor means; paired tests use within-donor differences, two-sided',
        absolute={k:summary(v) for k,v in block.items()},paired={})
    for k in ['fixed','swapped','cap-ramp']:
        d=block['history']-block[k];nonzero=d[d!=0]
        result['paired'][k]=dict(**summary(d),cluster_97_5=bounds(d,.975),
              t_p=float(stats.ttest_1samp(d,0).pvalue),higher=int((d>0).sum()),tied=int((d==0).sum()),
              sign_test_p=float(stats.binomtest(int((nonzero>0).sum()),len(nonzero)).pvalue) if len(nonzero) else 1)
    write_json(FOLDER/('intervals-'+split+'.json'),result,fixed=True)
    for k,v in result['absolute'].items():print(k,round(v['mean']*100,2),[round(z*100,2) for z in v['cluster_95']])
    for k,v in result['paired'].items():print(k,v)
if __name__=='__main__':
    import sys
    run(sys.argv[1] if len(sys.argv)>1 else 'dev')
