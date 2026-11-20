"""Use the first 24 own observations to choose one continuation for the rest of the duel."""
import gzip
from itertools import product
from recognition import DIR, OUT, FOES, Identifier, dataset, fixtures
from dedicated import budgets
from assay import java_rows, lower_contrast
from learner import ROOT, BUILD, CLOCKS, RTTS, freeze, write_json, json, np, torch
from pathlib import Path

FOLDER = OUT/'payoff'
CUTOFF, SEED = 24, 621

def responders():
    refs = {'wtap':'wtap', 'seven':FOES[1], 'cap-ramp':'sumo-cap-ramp'}
    for i, foe in enumerate(FOES):
        for role in ('routed','informed'):
            refs[f'{role}-{i}'] = str((DIR/role/'u064'/(foe+'.net')).relative_to(ROOT))
        n = budgets(foe)[0]
        refs[f'dedicated-{i}'] = str((DIR/('dedicated-'+foe)/f'n{n:05d}.net').relative_to(ROOT))
    return refs

def outcomes(split, refs, clocks=None):
    pairs = fixtures(split, clocks)
    rows = [r for _, pair in pairs for r in pair]
    catalog = FOLDER/(split+'-continuations.json.gz')
    if not catalog.exists():
        raw = java_rows('ResponseReversal',rows,[','.join(refs.values())],java_opts=['-Dleague.commitHeads=24'])
        values = [{k:v[ref] if v['cut'] is not None else v['full'] for k,ref in refs.items()}
                  | {'full':v['full'],'cut':v['cut']} for v in raw]
        freeze(catalog,gzip.compress(json.dumps(values,separators=(',',':')).encode(),mtime=0))
    values=json.loads(gzip.decompress(catalog.read_bytes()))
    assert len(values)==len(rows)
    return {k:np.array([v[k] for v in values]).reshape(-1,2) for k in [*refs,'full','cut']}

def choose():
    refs = responders()
    write_json(FOLDER/'protocol.json',dict(head=CUTOFF,seed=SEED,foes=FOES,choices=refs,
        discovery='select pair maximizing the worst of the two opponent-specific scores, then their average, then smaller reciprocal advantage',
        control='global fixed continuation selected on training clocks, sham forks and plain W-tap from spawn and from fork',
        success='positive development payoff against strongest fixed comparator and wrong history, plus opposite strictly better responses on both opponents; recheck on future donors before claiming'),fixed=True)
    scores = outcomes('train',refs)
    score = {n:scores[n].mean(axis=0) for n in refs}
    a,b = max(product(refs,repeat=2),key=lambda pair:(
        min(score[pair[0]][0],score[pair[1]][1]),score[pair[0]][0]+score[pair[1]][1],
        min(score[pair[0]][0]-score[pair[1]][0],score[pair[1]][1]-score[pair[0]][1])))
    fixed = max(refs,key=lambda n:score[n].mean())
    result=dict(selected=[a,b],fixed=fixed,training={n:v.tolist() for n,v in score.items()})
    write_json(FOLDER/'selection.json',result,fixed=True)
    return a,b,fixed

@torch.no_grad()
def decisions(x):
    n=len(x); t=torch.tensor(x[:,:,:CUTOFF]).flatten(0,1)
    r=Identifier(True);m=Identifier(False)
    for name,model in [('history',r),('one-head',m)]:
        model.load_state_dict(torch.load(OUT/f'{name}-{SEED}.pt',weights_only=True))
    _,h=r.encoder(t[:,:-6])
    def tail(h):return r.readout(r.encoder(t[:,-6:],h)[0][:,-1]).argmax(-1).reshape(n,2).numpy()
    return {'history':tail(h),'opposite-history':tail(h.reshape(1,n,2,-1).flip(2).reshape_as(h).contiguous()),
            'reset-history':tail(torch.zeros_like(h)),'one-head':m(t[:,-1:])[:,-1].argmax(-1).reshape(n,2).numpy()}

def measure(split,clocks=None):
    a,b,fixed=choose();refs=responders(); payoff=outcomes(split,refs,clocks)
    x,length,donor=dataset(split,clocks); routing=decisions(x)
    truth=np.broadcast_to([0,1],length.shape); eligible=length>=CUTOFF
    assert np.array_equal(eligible,payoff['cut']!=None)
    # Verify the complete undisturbed opening and measure plain W-tap from spawn on the exact same fixtures.
    from opponent_id import oracle_worker
    worker=oracle_worker();pairs=fixtures(split,clocks);direct={}
    try:
        for name in ('wtap','observer'):
            plan=[([name,*r[1:]] if name=='wtap' else r) for _,pair in pairs for r in pair]
            values=[]
            for j in range(0,len(plan),32):values.extend(k[0] for k in worker.run(plan[j:j+32],False))
            direct[name]=np.array(values).reshape(-1,2)
    finally:worker.close()
    assert np.array_equal(direct['observer'],payoff['full'])
    routing.update({'true-ID':truth,'wrong-ID':1-truth})
    arms={k:np.where(choice==0,payoff[a],payoff[b]) for k,choice in routing.items()}
    arms.update({'fixed':payoff[fixed],'branch-A':payoff[a],'branch-B':payoff[b],
                 'wtap-from-fork':payoff['wtap'],'wtap-from-spawn':direct['wtap'],'observer':direct['observer']})
    def counts(v):
        return dict(wins=int((v==1).sum()),draws=int((v==.5).sum()),losses=int((v==0).sum()),score=float(v.mean()))
    def contrast(d):
        means=[d[donor==c].mean() for c in np.unique(donor)]
        return [float(np.mean(means)),*lower_contrast(means,.0125)]
    rows=[pair[0] for _,pair in pairs];rtt=np.array([str(r[4])+'/'+str(r[5]) for r in rows]);seat=np.array([r[3] for r in rows])
    cells={k:{foe:dict(**counts(v[:,j]),seat={s:counts(v[seat==s,j]) for s in 'ab'},
                 rtt={p:counts(v[rtt==p,j]) for p in np.unique(rtt)}) for j,foe in enumerate(FOES)} for k,v in arms.items()}
    gains={k:contrast((arms['history']-arms[k]).mean(axis=1)) for k in ('fixed','opposite-history','one-head','reset-history')}
    opposing=[contrast((payoff[a]-payoff[b])[:,0]),contrast((payoff[b]-payoff[a])[:,1])]
    result=dict(cells=cells,pairs=len(x),completed_before_decision=int((~eligible).sum()),switch_after_heads=CUTOFF,
        correct={k:float((v[eligible]==truth[eligible]).mean()) for k,v in routing.items()},
        history_gain=gains,response_advantages=opposing,
        qualifies=bool(min(gains['fixed'][1],gains['opposite-history'][1],*(c[1] for c in opposing))>0))
    write_json(FOLDER/(split+'.json'),result,fixed=True)
    return result
if __name__=='__main__':measure('dev')
