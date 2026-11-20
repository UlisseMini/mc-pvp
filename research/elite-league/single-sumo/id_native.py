"""Small, audited 7-CPS native transfer panel for the frozen capacity diagnostic."""
from id_evaluate import arm_paths
from opponent_id import DIR,runtime
from native_league import *
from forge_window import window
from assay import lower_contrast

def protocol():
    pin=runtime()
    spec=dict(target=INCUMBENT,blocks_target=16,blocks_other=8,cps=7,
        additional=['plain W-tap','weakest distinct development opponent of the informed model'],
        roles=['informed-correct','informed-wrong','wide-correct','routed-correct','blind-trained',
               'shared-initializer','dedicated-equal-exposure','dedicated-full-budget','wtap'],
        width_checkpoint=256,wrong_id='cycle through non-opponent labels by block number',
        primary='dedicated equal exposure versus informed correct, then full budget versus equal exposure',
        fixture='same seat and two RTTs in each independent block; paired by block, separate native repeats',
        forge=pin['forge'],oracle_research_only=True)
    write_json(DIR/'native-protocol.json',spec,fixed=True)
    return spec

def native_name(path):
    if path=='wtap':return WT
    data=path.read_bytes();sha=hashlib.sha256(data).hexdigest()
    ref={'reference':str(path.relative_to(ROOT)),'sha256':sha}
    return publish('idlab-'+sha[:12],ref,'Frozen 7 CPS research comparator '+sha[:12])

def early_native():
    from dedicated import budgets
    out=DIR/'native-u064';plan={};roles={}
    for j,foe in enumerate([INCUMBENT,BASE_COUNTER]):
        exposure=budgets(foe)[0]
        arms=[native_name(DIR/'informed/u064'/(foe+'.net')),
              native_name(DIR/('dedicated-'+foe)/f'n{exposure:05d}.net'),WT]
        block=fixtures(f'sg-id64-f{j}',arms,[alias(foe)],8,7)
        plan.update(block)
        for q,t in block.items():roles[q]=[foe,['informed','dedicated','wtap'][arms.index(t['arm'])]]
    keys=sorted(plan);random.Random(398713).shuffle(keys);plan={q:plan[q] for q in keys}
    write_json(out/'design.json',dict(sources=roles,blocks=8,duels=len(plan),scope='interim exposure-matched native diagnostic'),fixed=True)
    with window() as bridge:game=cohort(out,plan,bridge)
    cells={}
    for foe in [INCUMBENT,BASE_COUNTER]:
        cells[foe]={}
        for arm in ('informed','dedicated','wtap'):
            matches=[q for q,v in roles.items() if v==[foe,arm]]
            def score(keys):
                vals=[game[q]['score'] for q in keys]
                return [vals.count(x) for x in (1,.5,0)]
            cells[foe][arm]=dict(wins_draws_losses=score(matches),seat={s:score([q for q in matches if plan[q]['side']==s]) for s in 'ab'},
                rtt={f'{a}/{b}':score([q for q in matches if (plan[q]['rtt'],plan[q]['opponent_rtt'])==(a,b)]) for a,b in RTTS})
    write_json(out/'summary.json',cells,fixed=True)

def play_native(target,secondary=False):
    spec=protocol()
    out=(DIR/'capacity-secondary' if secondary else DIR)/'native-final'
    done=out/'summary.json'
    if done.exists():return read(done)
    dev=read((DIR/'eval-u256-development')/'summary-full.json')['cells']
    weakest=min((n for n in FIELD if n not in (target,'wtap')),
                key=lambda n:(dev[n]['informed-correct']['score'],n))
    foes=list(dict.fromkeys([target,'wtap',weakest]))
    out.mkdir(parents=True,exist_ok=True)
    allocation={};plan={};labels={}
    prefix='sg-id2' if secondary else 'sg-id1'
    for j,foe in enumerate(foes):
        lookups=arm_paths(256,target,foe)
        for b in range(spec['blocks_target'] if j==0 else spec['blocks_other']):
            cell=(b+3*j)%8;side='ab'[cell%2];a,c=RTTS[cell//2]
            for k,role in enumerate(spec['roles']):
                path=lookups[role](b)
                p=native_name(path)
                q=f'{prefix}-f{j:02}-b{b:02}-a{k}'
                t=fixture.trial(q,p,alias(foe),side,a,c)
                t.update(cps_cap=7,block=b,command=t['command'].replace(' cps=20 ',' cps=7 '))
                plan[q]=t;labels[q]=(foe,role)
                if path!='wtap':allocation[p]=str(path.relative_to(ROOT))
    # All arms/foes occur throughout the panel, including the first half of the longer target series.
    keys=sorted(plan);random.Random(798152).shuffle(keys);plan={q:plan[q] for q in keys}
    write_json(out/'assignments.json',dict(target=target,weakest=weakest,labels=labels,sources=allocation),fixed=True)
    bridge=BUILD/spec['forge']['jar']
    if hashlib.sha256(bridge.read_bytes()).hexdigest()!=spec['forge']['sha256']:raise ValueError('Native binary changed')
    with window(bridge) as running:
        scores=cohort(out/'games',plan,running)
    def counts(g):
        return dict(wins=g.count(1),draws=g.count(.5),losses=g.count(0),score=float(np.mean(g)))
    summary={};paired={}
    for foe in foes:
        summary[foe]={};series={}
        for role in spec['roles']:
            picks=sorted((q for q in plan if labels[q]==(foe,role)),key=lambda q:plan[q]['block'])
            series[role]=np.array([scores[q]['score'] for q in picks]);tally=lambda qs:counts([scores[q]['score'] for q in qs])
            summary[foe][role]=dict(**tally(picks),seat={s:tally([q for q in picks if plan[q]['side']==s]) for s in 'ab'},
                rtt={f'{a}/{b}':tally([q for q in picks if (plan[q]['rtt'],plan[q]['opponent_rtt'])==(a,b)]) for a,b in RTTS})
        paired[foe]={}
        for a,b in [('dedicated-equal-exposure','informed-correct'),('dedicated-full-budget','dedicated-equal-exposure'),
                    ('wide-correct','informed-correct'),('informed-correct','informed-wrong'),('routed-correct','informed-correct')]:
            dif=series[a]-series[b]
            paired[foe][a+' minus '+b]=dict(delta=float(dif.mean()),bounds=lower_contrast(dif,.05/10))
    report=dict(target=target,weakest=weakest,cells=summary,paired=paired,verified=len(scores),elite_human_duels=0)
    write_json(done,report,fixed=True);return report

if __name__=='__main__':
    protocol()
    if sys.argv[1:2]==['early']:early_native()
    elif len(sys.argv)>1 and sys.argv[1]!='plan':
        play_native(sys.argv[1],secondary=sys.argv[1]!=INCUMBENT)
