"""Separate uncertainty from fresh action seeds on fixed clocks from replication across wall-clock sessions."""
from pathlib import Path
from scipy import stats
from learner import ROOT,CLOCKS,np,json,write_json
from recognition_payoff import FOLDER,SEED,responders,outcomes,dataset,decisions

def run(split, seed=SEED):
    clocks=CLOCKS['dev'] if split=='dev' else json.loads((FOLDER/'prospective/calendars/manifest.json').read_text())['selected']
    r=outcomes(split,responders(),None if split=='dev' else clocks)
    x,_,donor=dataset(split,None if split=='dev' else clocks)
    act=decisions(x,seed);table=json.loads((FOLDER/('routed-'+split+'.json')).read_text())
    def scored(route):return np.where(route==0,r['routed-0'],r['routed-1'])
    arms={'history':scored(act['history']),'fixed':r[table['fixed']],'swapped':scored(act['opposite-history'])}
    periods=[];groups=[]
    for i,f in enumerate(clocks):
        match=Path(f).name.split('.')[0];record=json.loads((ROOT/'runtime/results'/(match+'.json')).read_text())
        periods.append((record['start'],record['finish'],i,match))
    for start,end,i,name in sorted(periods):
        if groups and start<=groups[-1]['end']:
            group=groups[-1];group['end']=max(end,group['end'])
        else:
            group={'start':start,'end':end,'indices':[],'matches':[]};groups.append(group)
        group['indices'].append(i);group['matches'].append(name)
    results={}
    for name,v in {**arms,**{'history minus '+k:arms['history']-arms[k] for k in ('fixed','swapped')}}.items():
        # Two foes share clock and physical seeds within a fixture. Keep them together.
        values=v.mean(axis=1);donor_means=np.array([values[donor==i].mean() for i in range(len(clocks))])
        by_group=[float(donor_means[g['indices']].mean()) for g in groups]
        result=dict(mean=float(values.mean()),by_disjoint_match_intervals=by_group)
        if split=='dev':
            # Eight donors x 4 independent replications x two seats x four ping pairs.
            strata=values.reshape(len(clocks),4,8).transpose(0,2,1).reshape(-1,4)
            n=len(strata);var=strata.var(axis=1,ddof=1)/(4*n*n)
            se=float(np.sqrt(var.sum()));df=float(var.sum()**2/((var**2).sum()/3))
            m=values.mean()
            def ci(coverage):q=stats.t.ppf((1+coverage)/2,df)*se;return [float(m-q),float(m+q)]
            result.update(fixed_clock_95=ci(.95),fixed_clock_97_5=ci(.975),df=df,
                fixed_clock_t_p=float(2*stats.t.sf(abs(m/se),df)))
            # Stratified paired bootstrap with equal weighting of all fixed donor/seat/RTT cells.
            rng=np.random.default_rng(987623);boot=[]
            for _ in range(64):
                ix=rng.integers(0,4,(512,n,4));boot.extend(strata[np.arange(n)[None,:,None],ix].mean(axis=(1,2)))
            result['fixed_clock_bootstrap_95']=np.quantile(boot,[.025,.975]).tolist()
        elif split=='sealed':
            # One independent simulator seed pair per clock/seat/ping cell. Pool across the 32 fixed
            # clocks within each seat and ping cell. Between-clock differences also enter this SE.
            strata=values.reshape(len(clocks),8).T
            n,reps=strata.shape;var=strata.var(axis=1,ddof=1)/(reps*n*n)
            se=float(np.sqrt(var.sum()));df=float(var.sum()**2/((var**2).sum()/(reps-1)))
            m=float(values.mean())
            def ci(q):
                radius=stats.t.ppf((1+q)/2,df)*se
                return [m-radius,m+radius]
            result.update(independent_seed_95=ci(.95),independent_seed_97_5=ci(.975),
                df=df,independent_seed_t_p=float(2*stats.t.sf(abs(m/se),df)))
        results[name]=result
    report=dict(groups=groups,results=results,
        interpretation='whole donor match interval overlap is a conservative dependence sensitivity; even disjoint times on one machine may share conditions')
    if seed==SEED:
        file=FOLDER/('timing-uncertainty-'+split+'.json')
    else:
        file=FOLDER/'classifier-seeds'/f'{split}-{seed}.json'
        report.update(classifier_seed=seed,matched_plain_wtap=table['cells']['wtap-from-spawn'],
                      setup='secondary sensitivity; shared match fixtures and same frozen response policies across all classifier seeds')
    write_json(file,report,fixed=True)
    print([(g['matches']) for g in groups]);print(results)
if __name__=='__main__':
    import sys
    run(sys.argv[1] if len(sys.argv)>1 else 'dev',int(sys.argv[2]) if len(sys.argv)>2 else SEED)
