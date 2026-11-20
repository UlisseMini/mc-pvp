"""Package and verify the learned 24-head observer and its two trained responses as one legal client policy."""
import base64
import os
import subprocess
import sys
from recognition_payoff import FOLDER,FOES,SEED,decisions,dataset,fixtures as opening_fixtures,outcomes,responders
from opponent_id import DIR
from learner import ROOT,BUILD,RTTS,Worker,freeze,write_json,np,torch
from native_league import read
from build import sim_paths,sha
sys.path.insert(0,str(ROOT))
from forge_runtime import JAVA8

OUT=FOLDER/'client'
MODES={'history':0,'branch-A':1,'branch-B':2,'reset-history':3,'inverted-history':4}

def export():
    from router_export import export_router
    sources=[DIR/'shared-mlp/initial.net',*(DIR/'routed/u064'/(n+'.net') for n in FOES)]
    state=torch.load(DIR/'recognition'/f'history-{SEED}.pt',map_location='cpu',weights_only=True)
    return export_router(sources,state,MODES,OUT)

def runtime():
    file=OUT/'runtime.json'
    if not file.exists():
        sim=sim_paths()[0]
        bridge=read(BUILD/'forge-current.json')
        write_json(file,dict(sim=sim.name,forge=bridge,sim_sources=sha(sim/'source-pins.json')),fixed=True)
    return read(file)

def trace_parity(path,pin):
    fixture=BUILD/'native-trace.tsv'
    digests=[]
    for java,cp in [(JAVA8,BUILD/pin['forge']['jar']),('java',BUILD/pin['sim'])]:
        digests.append(subprocess.check_output([java,'-XX:+UseSerialGC','-cp',
            os.pathsep.join(map(str,[BUILD/'trace-classes',cp])),'LeagueTrace',path,fixture],text=True).strip())
    if digests[0]!=digests[1]:raise ValueError('Forge Java 8 and the model produced different buttons')
    return digests[0]

def verify():
    nets=export();pin=runtime();raw=outcomes('dev',responders());x,length,_=dataset('dev')
    predictions=decisions(x);pairs=opening_fixtures('dev');worlds=[r for _,p in pairs for r in p]
    w=Worker(runtime_directory=BUILD/pin['sim']);details={}
    try:
        choices={'history':predictions['history'],'branch-A':np.zeros_like(length),'branch-B':np.ones_like(length),
                 'reset-history':predictions['reset-history'],'inverted-history':1-predictions['history']}
        for role,path in nets.items():
            # Compare ordinary, unforked full games to the independent counterfactual implementation.
            expected=np.where(choices[role]==0,raw['routed-0'],raw['routed-1']).reshape(-1)
            rows=[[path,*r[1:]] for r in worlds];actual=[]
            for j in range(0,len(rows),32):actual.extend(k[0] for k in w.run(rows[j:j+32],False))
            if not np.array_equal(actual,expected):
                raise ValueError(f'{role}: {np.where(np.array(actual)!=expected)[0].tolist()} differs from matched fork')
            details[role]=dict(matching_full_matches=len(worlds),native_trace=trace_parity(path,pin),sha256=sha(path))
        write_json(OUT/'parity.json',dict(checks=details,model_runtime=pin,trace=sha(BUILD/'native-trace.tsv')),fixed=True)
    finally:w.close()

def native():
    from native_league import fixtures,fair_cohort,publish,alias,WT
    parity=read(OUT/'parity.json');pin=runtime()
    names={}
    for role,data in parity['checks'].items():
        path=OUT/(role+'.net')
        names[role]=publish('sg-router-'+role.lower(),{'reference':str(path.relative_to(ROOT)),'sha256':data['sha256']},
            '7 CPS observational research router '+role)
    names['wtap']=WT
    names['cap-ramp-from-spawn']='sumo-cap-ramp'
    # Balance all controllers on the exact same native seat and RTT settings.
    roles=['history','branch-A','branch-B','reset-history','inverted-history','cap-ramp-from-spawn','wtap']
    plan=fixtures('sg-router24',[names[k] for k in roles],[alias(f) for f in FOES],16,7)
    folder=OUT/'native';bridge=BUILD/pin['forge']['jar'];assert sha(bridge)==pin['forge']['sha256']
    records=fair_cohort(folder,plan,bridge)
    summary={}
    for f in FOES:
        summary[f]={}
        for role in roles:
            qs=[q for q,v in plan.items() if v['arm']==names[role] and v['opponent']==alias(f)]
            def tally(keys):
                v=[records[q]['score'] for q in keys]
                return dict(wins=v.count(1),draws=v.count(.5),losses=v.count(0))
            summary[f][role]=dict(**tally(qs),seat={s:tally([q for q in qs if plan[q]['side']==s]) for s in 'ab'},
                rtt={f'{a}/{b}':tally([q for q in qs if (plan[q]['rtt'],plan[q]['opponent_rtt'])==(a,b)]) for a,b in RTTS})
    write_json(folder/'summary.json',summary,fixed=True)

if __name__=='__main__':
    if sys.argv[1:]==['native']:native()
    else:verify()
