"""Matched model and native promotion with independent timing blocks and simultaneous one-sided bounds."""
from collections import defaultdict
from native_league import *
from forge_window import window
from styles import build as make_styles

def freeze_promotion_styles(g):
    folder=EVIDENCE/f'g{g:03d}'
    backbone=['sumo-own-ping','v4-reply-g0312','v4-sprint-link-jitter-15','mmc13-actor-2-fit'][(g-1)%4]
    spec=make_styles(folder/'promotion-styles',backbone,20+g%15,195+g%37,4+g%3)
    write_json(folder/'promotion-styles.json',spec,fixed=True)
    return spec
def nomination(g):
    folder=EVIDENCE/f'g{g:03d}';d=read(folder/'tournament.json');c=read(folder/'generation.json')
    field=d['field'];inc=c['incumbent'];rank=d['ranking'];w=rank[0]['name'];challenger=next(x['name'] for x in rank if x['name']!=inc)
    # Compare the actual top eligible challenger even if the incumbent won the development tournament.
    def distinct(n):
        return min((f for f in FIELD if f not in ('wtap',n) and field[f]['sha256']!=field[n]['sha256']),
                   key=lambda f:(d['matrix'][n][f]['score'],f))
    def weak(n):
        r=next(x for x in rank if x['name']==n)
        return min(d['families'][r['worst_family']],key=lambda f:(d['matrix'][n][f]['score'],f))
    ctr=read(folder/'counter.json')
    newest=ctr['challenger'] if ctr['confirmed'] else c['counter']
    candidates=['wtap',INCUMBENT,BASE_COUNTER,newest,weak(challenger),weak(inc),inc]
    core=list(dict.fromkeys(candidates))
    ps=freeze_promotion_styles(g)
    for style,ref in ps.items():
        n=f'sg{g:03d}-seal-{style}';field[n]=ref;core.append(n)
    membership={n:next((f for f,ns in d['families'].items() if n in ns),'withheld '+n) for n in core}
    return dict(winner=w,challenger=challenger,incumbent=inc,core=core,families=membership,field=field,
                main_weakest=distinct(f'sg{g:03d}-main'),challenger_weakest=distinct(challenger),
                weakest_distinct={n:distinct(n) for n in [inc,*[f'sg{g:03d}-{tag}' for tag in ('main','mlp','counter')]]})
def summary(values,foes,family,challenger,inc):
    # shape is blocks x three arms x opponents, all seat and RTT cells already equally balanced.
    families=list(dict.fromkeys(family[f] for f in foes));index=[[foes.index(f) for f in foes if family[f]==s] for s in families]
    def measures(x):
        cell=x.mean(axis=0);fs=np.stack([cell[:,j].mean(axis=1) for j in index],axis=1)
        means=fs.mean(axis=1);mins=fs.min(axis=1)
        return np.array([means[0]-means[1],mins[0]-mins[1],cell[0,foes.index('wtap')],means[0]-means[2],cell[0,foes.index(BASE_COUNTER)]])
    est=measures(values);rng=np.random.default_rng(623871);n=len(values)
    draws=np.array([measures(values[rng.integers(n,size=n)]) for _ in range(8192)])
    # Single simultaneous lower band, with a bounded conservative exception when a contrast lacks support.
    radius=max(0.,float(np.quantile((est-draws).max(axis=1),.95)))
    lo=est-radius
    for j in range(len(est)):
        if np.count_nonzero(abs(draws[:,j]-est[j])>1e-12)<24 or len(np.unique(draws[:,j]))<4:
            width=1 if j in (2,4) else 2
            lo[j]=min(lo[j],est[j]-width*math.sqrt(math.log(len(est)/.05)/(2*n)))
    metrics=['field_delta','worst_delta','vs_wtap','field_delta_over_wtap','vs_first_counter']
    passed=(lo[0]>0 and lo[1]>=-.05 or lo[1]>0 and lo[0]>=-.05) and lo[2]>.75 and lo[3]>0 and lo[4]>.5
    return dict(arms=[challenger,inc,'wtap'],opponents=foes,field_cells=values.mean(axis=0).tolist(),metrics=dict(zip(metrics,est.tolist())),
                simultaneous_lower=dict(zip(metrics,lo.tolist())),passed=bool(passed),blocks=n)
def model_panel(g,nom,clocks):
    folder=EVIDENCE/f'g{g:03d}';dest=folder/'promotion-model.json'
    if dest.exists():return read(dest)
    arms=[nom['challenger'],nom['incumbent'],'wtap'];field=nom['field'];foes=nom['core']
    raw=[];v=np.empty((len(clocks),3,len(foes)))
    worker=Worker()
    try:
        for j,f in enumerate(foes):
            for k,arm in enumerate(arms):
                rows=[];keys=[]
                for i,tape in enumerate(clocks):
                    for seat in 'ab':
                        for a,b in RTTS:
                            rng=random.Random(36571+g*9973+i*61583+a*101+b*13+int(seat=='a'))
                            rows.append([field[arm]['reference'],field[f]['reference'],tape,seat,a,b,rng.getrandbits(60),rng.getrandbits(60)])
                            keys.append([i,0,seat,a,b])
                results=[]
                for start in range(0,len(rows),32):results.extend(worker.run(rows[start:start+32],False))
                records=[key+[r[0],r[1]] for key,r in zip(keys,results)]
                raw.append(dict(arm=arm,foe=f,games=records))
                for i in range(len(clocks)):v[i,k,j]=np.mean([r[5] for r in records if r[0]==i])
    finally:worker.close()
    report=summary(v,foes,nom['families'],*arms[:2])
    write_json(folder/'promotion-model-games.json',raw,fixed=True);write_json(dest,report,fixed=True);return report

def actual_name(n,field):
    if field[n]['reference'].endswith('.net'):return publish(n,field[n],f'Frozen 7 CPS sumo league policy {n}')
    return alias(n)
def native_panel(g,nom,eligible):
    if not eligible:return native_diagnostics(g,nom)
    folder=EVIDENCE/f'g{g:03d}';field=nom['field']
    arms=[nom['challenger'],nom['incumbent'],'wtap'] if eligible else [f'sg{g:03d}-main',nom['incumbent'],'wtap']
    foes=nom['core'] if eligible else list(dict.fromkeys(['wtap',nom['main_weakest']]))
    blocks=64 if eligible else 8
    with window() as bridge:
        aa=[actual_name(n,field) for n in arms];ff=[actual_name(n,field) for n in foes]
        plan=fixtures(f'sg{g:03d}-'+('confirm' if eligible else 'diag'),aa,ff,blocks,7)
        records=cohort(folder/('native-confirm' if eligible else 'native-diagnostic'),plan,bridge,block_barrier=eligible)
    v=np.empty((blocks,3,len(foes)));details=[]
    for q,t in plan.items():
        # Same actor name can occur in several roles; the frozen request IDs carry their role indices.
        arm_i=int(q.rsplit('-a',1)[1]);foe_i=int(q.split('-f')[-1].split('-')[0])
        r=records[q];v[t['block'],arm_i,foe_i]=r['score'];details.append(dict(arm=arms[arm_i],foe=foes[foe_i],
            seat=t['side'],own=t['rtt'],other=t['opponent_rtt'],block=t['block'],mid=r['mid'],score=r['score']))
    report=summary(v,foes,nom['families'],*arms[:2]) if eligible else dict(passed=False,arms=arms,opponents=foes,
                field_cells=v.mean(axis=0).tolist(),blocks=blocks,scope='matched native diagnostic')
    report['games']=details;write_json(folder/'promotion-native.json',report,fixed=True);return report

def publish_winner(g,nom):
    win=nom['winner'];field=nom['field']
    return publish(f'sumo-league-{g:03d}',field[win],f'7 CPS league generation {g} tournament winner, {win}')

def winner_controls(g,nom):
    folder=EVIDENCE/f'g{g:03d}'/'published-winner';done=folder/'result.json'
    if done.exists():return read(done)
    name=publish_winner(g,nom);plan=fixtures(f'sg{g:03d}-published',[name,WT],[WT],8,7)
    with window() as bridge:records=cohort(folder,plan,bridge)
    score={}
    for n in (name,WT):
        outcomes=[r['score'] for q,r in records.items() if plan[q]['arm']==n]
        score[n]=dict(wins=outcomes.count(1),draws=outcomes.count(.5),losses=outcomes.count(0))
    result=dict(winner=nom['winner'],duel_alias=name,sha256=nom['field'][nom['winner']]['sha256'],
        against=WT,native_scores=score,input_heads=sum(sum(r['local_timing_heads'].values()) for r in records.values()))
    write_json(done,result,fixed=True);return result
def transfer20(g,nom):
    folder=EVIDENCE/f'g{g:03d}';field=nom['field'];a=nom['challenger'];i=nom['incumbent']
    with window() as bridge:
        arms=[actual_name(n,field) for n in [a,i,'wtap']]
        plan=fixtures(f'sg{g:03d}-x20',arms,[WT],16,20)
        return cohort(folder/'native-20-transfer',plan,bridge)

def model20(g,nom,clocks):
    folder=EVIDENCE/f'g{g:03d}';field=nom['field'];w=Worker(cps=20);report={}
    try:
        for k in [nom['challenger'],nom['incumbent'],'wtap']:
            rows=[];keys=[]
            for i,tape in enumerate(clocks):
                for seat in 'ab':
                    for a,b in RTTS:
                        rng=random.Random(41729+g*853+i*4783+a*151+b*17+int(seat=='a'))
                        rows.append([field[k]['reference'],'wtap',tape,seat,a,b,rng.getrandbits(60),rng.getrandbits(60)])
                        keys.append([i,seat,a,b])
            results=[]
            for j in range(0,len(rows),32):results.extend(w.run(rows[j:j+32],False))
            report[k]=[key+[r[0],r[1]] for key,r in zip(keys,results)]
        write_json(folder/'model-20-transfer.json',report,fixed=True)
    finally:w.close()

def promote_title(g,nom):
    folder=EVIDENCE/f'g{g:03d}';new=nom['challenger'];name=actual_name(new,nom['field'])
    current=ROOT/'policies/best-sumo.properties';prior=(ROOT/'policies'/(alias(nom['incumbent'])+'.properties'))
    old=props('best-sumo');incoming=props(name)
    if old.get('duel_network')==incoming.get('duel_network') and incoming.get('duel_network'):return
    if old.get('duel_network')!=props(alias(nom['incumbent'])).get('duel_network'):
        raise RuntimeError('Live title changed independently; retain both immutable contenders')
    freeze(folder/'previous-best.properties',current.read_bytes())
    data=(ROOT/'policies'/(name+'.properties')).read_bytes()
    freeze(folder/'promoted-best.properties',data);atomic(current,data)

def native_diagnostics(g,nom):
    folder=EVIDENCE/f'g{g:03d}';field=nom['field'].copy();inc=nom['incumbent']
    pairs={n:list(dict.fromkeys(['wtap',foe,inc])) for n,foe in nom['weakest_distinct'].items() if n!=inc}
    if g==1:
        name='sg001-seven-clone';p=EVIDENCE/'bootstrap-sumo-seven-first/initial.net'
        field[name]=dict(reference=str(p.relative_to(ROOT)),sha256=hashlib.sha256(p.read_bytes()).hexdigest())
        # This is a known teacher's imitation. Include the teacher in the native check.
        panels=[r for r in (folder/'initialization-panel').glob('*.json') if r.stem not in ('wtap',)]
        worst=min(panels,key=lambda r:read(r)['sg001-init-sumo-seven-first']['score']).stem if panels else BASE_COUNTER
        pairs[name]=list(dict.fromkeys(['wtap',inc,BASE_COUNTER,worst]))
    foes=sorted({f for fs in pairs.values() for f in fs}|{'wtap',nom['weakest_distinct'][inc],BASE_COUNTER})
    pairs[inc]=foes;pairs['wtap']=foes
    arms=list(pairs);plan={};lookup={}
    with window() as bridge:
        names={n:actual_name(n,field) for n in set(arms)|set(foes)}
        for block in range(8):
            part=[]
            for j,foe in enumerate(foes):
                cell=(block+3*j)%8;side='ab'[cell%2];a,b=RTTS[cell//2]
                for k,arm in enumerate(arms):
                    if foe not in pairs[arm]:continue
                    q=f'sg{g:03d}-nd-b{block:02}-f{j:02}-a{k}'
                    v=fixture.trial(q,names[arm],names[foe],side,a,b)
                    v.update(cps_cap=7,block=block,command=v['command'].replace(' cps=20 ',' cps=7 '))
                    lookup[q]=(arm,foe);part.append((q,v))
            random.Random(37811+block).shuffle(part);plan.update(part)
        records=cohort(folder/'native-diagnostic',plan,bridge)
    details=[];summary_cells={}
    for q,r in records.items():
        t=plan[q];arm,foe=lookup[q]
        details.append(dict(arm=arm,foe=foe,seat=t['side'],own=t['rtt'],other=t['opponent_rtt'],block=t['block'],mid=r['mid'],score=r['score']))
    for n,fs in pairs.items():
        summary_cells[n]={}
        for foe in fs:
            def cell(who):
                a=[r['score'] for r in details if r['arm']==who and r['foe']==foe]
                return dict(wins=a.count(1),draws=a.count(.5),losses=a.count(0),score=float(np.mean(a)))
            summary_cells[n][foe]=dict(actor=cell(n),wtap=cell('wtap'))
    report=dict(passed=False,scope='native diagnostics, eight independent blocks',pairs=pairs,cells=summary_cells,games=details)
    write_json(folder/'promotion-native.json',report,fixed=True);return report
