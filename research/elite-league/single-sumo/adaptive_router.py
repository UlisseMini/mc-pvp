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
    sources=[DIR/'shared-mlp/initial.net',*(DIR/'routed/u064'/(n+'.net') for n in FOES)]
    parts=[]
    for path in sources:
        family,encoded=path.read_bytes().strip().split(b'.')
        assert family==b'LEAGUE_MLP180'
        parts.append(base64.b64decode(encoded,validate=True))
    state=torch.load(DIR/'recognition'/f'history-{SEED}.pt',map_location='cpu',weights_only=True)
    weights=lambda k:state[k].detach().numpy()
    readout=np.zeros((6,32),np.float32);readout[:2]=weights('readout.weight')
    bias=np.zeros(6,np.float32);bias[:2]=weights('readout.bias')
    classifier=[weights('encoder.weight_ih_l0'),weights('encoder.weight_hh_l0'),weights('encoder.bias_ih_l0'),
                readout,bias,weights('encoder.bias_hh_l0')]
    parts.extend(a.astype('>f4').tobytes() for a in classifier)
    models={}
    for role,mode in MODES.items():
        data=b''.join(parts)+np.asarray([mode],dtype='>f4').tobytes()
        path=OUT/(role+'.net');freeze(path,b'LEAGUE_ADAPT24.'+base64.b64encode(data)+b'\n');models[role]=path
    return models

def runtime():
    file=OUT/'runtime.json'
    if not file.exists():
        sim=sim_paths()[0]
        bridge=read(BUILD/'forge-current.json')
        write_json(file,dict(sim=sim.name,forge=bridge,sim_sources=sha(sim/'source-pins.json')),fixed=True)
    return read(file)

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
            fixture=BUILD/'native-trace.tsv';digests=[]
            for j,cp in [(JAVA8,BUILD/pin['forge']['jar']),('java',BUILD/pin['sim'])]:
                digests.append(subprocess.check_output([j,'-XX:+UseSerialGC','-cp',
                    os.pathsep.join(map(str,[BUILD/'trace-classes',cp])),'LeagueTrace',path,fixture],text=True).strip())
            if digests[0]!=digests[1]:raise ValueError('Forge Java 8 and training runtime produced different buttons')
            details[role]=dict(matching_full_matches=len(worlds),native_trace=digests[0],sha256=sha(path))
        write_json(OUT/'parity.json',dict(checks=details,model_runtime=pin,trace=sha(BUILD/'native-trace.tsv')),fixed=True)
    finally:w.close()

def native():
    from native_league import fixtures,cohort,publish,alias,WT
    from forge_window import cohort_window
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
    with cohort_window(folder,bridge) as digest:records=cohort(folder,plan,digest)
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
