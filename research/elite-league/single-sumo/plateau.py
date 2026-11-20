"""A failed ordinary search alone cannot establish a fixed point."""
from generation import initialize
from train import configure
from run_training import train
from league_tournament import *
from assay import CP,lower_contrast
import sys

def fixed_budget_verdict(g):
    """Review independent completed searches. A failed search never gets another automatic budget."""
    from adversary import STORE, native_check
    folder=EVIDENCE/f'g{g:03d}'
    result=folder/'plateau/verdict.json'
    if result.is_file():return json.loads(result.read_text())
    def read(p):return json.loads(p.read_text())
    rounds=read(folder/'tournament.json')
    decision=read(folder/'decision.json')
    recent=[]
    for n in range(max(3,g-2),g+1):
        p=EVIDENCE/f'g{n:03d}'
        counter=read(p/'counter.json')
        recent.append(dict(generation=n,target=counter['target_sha256'],passed=counter['confirmed'],
                           promoted=read(p/'decision.json')['promoted'],novel=counter['novel']))
    titles=list(dict.fromkeys([rounds['winner'],decision['live_incumbent']]))
    positives={}
    for title in titles:
        foes=[n for n,v in rounds['field'].items() if v['sha256']!=rounds['field'][title]['sha256']]
        strongest=max(foes,key=lambda n:rounds['matrix'][n][title]['wins'])
        row=rounds['matrix'][strongest][title]
        positives[title]=dict(opponent=strongest,performance=row,wtap=rounds['matrix']['wtap'][title],
                              passes=5*row['wins']>3*(row['wins']+row['draws']+row['losses']))
    target_rows={n:STORE/rounds['field'][n]['sha256']/'result.json' for n in titles}
    resolved=all(p.is_file() and not read(p)['confirmed'] for p in target_rows.values())
    independent=len({r['target'] for r in recent})==3
    stalled=independent and all(not any(r[k] for k in ('passed','novel','promoted')) for r in recent)
    check=bool(stalled and resolved and not any(r['passes'] for r in positives.values()))
    native={}
    if check:
        for n,p in target_rows.items():
            outcome=read(p);native_check(outcome)
            native[n]=read(p.parent/'native/summary.json')
        # With only eight independent seat/RTT blocks this is a transfer check.
        # A promising native response keeps the league running.
        from id_native import native_name
        from native_league import alias
        for n,p in target_rows.items():
            outcome=read(p);actor=native_name(ROOT/outcome['policy']['reference'])
            target=outcome['target']['reference']
            foe=native_name(ROOT/target) if target.endswith('.net') else alias(target)
            row=native[n][actor][foe]
            if 5*row['wins']>3*sum(row[k] for k in ('wins','draws','losses')):check=False
    reason=('Three distinct 65536-fight searches missed the strict 60% win gate, neither leader had a qualifying counter, and native checks agreed.' if check else
            'Continue the league. The fixed-budget searches, archive comparison and native evidence have not jointly established a plateau.')
    report=dict(budgeted_plateau=check,reason=reason,
                recent=recent,current_leaders=titles,each_has_completed_failed_search=resolved,
                strongest_archive_responses=positives,native=native,training_fights_added_by_review=0)
    write_json(result,report,fixed=True)
    return report

def search(g):
    if g>=3:return fixed_budget_verdict(g)
    folder=EVIDENCE/f'g{g:03d}'/'plateau';cfg=initialize(g);field=cfg['field'].copy()
    inc=cfg['incumbent'];source=EVIDENCE/f'g{g:03d}'/'main/trainer.pt'
    tournament=json.loads((folder.parent/'tournament.json').read_text())
    spec=folder/'assay.json'
    if not spec.exists():
        last=max(int(p.stem[1:]) for p in (ROOT/'runtime/results').glob('m[0-9]*.json') if p.stem[1:].isdigit())
        candidates=[n for n,v in tournament['field'].items() if v['sha256']!=field[inc]['sha256']]
        known=max(candidates,key=lambda n:(tournament['matrix'][n][inc]['score'],n))
        write_json(spec,dict(first_future_id=last+4,calendar_count=32,minimum_gap_ms=60000,opponent=inc,
            control='PPO from W-tap imitation must improve on that frozen initialization against the original incumbent',
            known_response=known,known_hash=tournament['field'][known]['sha256'],budget=32768),fixed=True)
    registered=json.loads(spec.read_text());searches=['ppo','evolution']
    if 'known_response' in registered:
        known=registered['known_response'];field['known']=tournament['field'][known]
        if field['known']['sha256']!=registered['known_hash']:raise ValueError('Frozen archive response changed')
        searches.append('known')
    # Independently frozen searches; original known weakness is a positive control for learning.
    jobs=[('ppo',inc,source),('positive',INCUMBENT,EVIDENCE/'bootstrap-wtap/trainer.pt')]
    for i,(tag,opp,warm) in enumerate(jobs):
        d=folder/tag;configure(d,'gru','exploiter',300017+g*409+i,823441+g*1009+i,field[opp]['reference'],
                           field[BASE_COUNTER]['reference'],FAMILIES,warm=warm)
        train(d,512);field[tag]=pin_model(d)
    es=folder/'evolution';es.mkdir(exist_ok=True,parents=True)
    subprocess.run([sys.executable,HERE.parent/'cpu_limit.py','nice','-n','8','java','-XX:ActiveProcessorCount=2',
                     '-XX:+UseSerialGC','-Xmx1000m','-cp',CP,'LeagueEvolution',es,field[inc]['reference'],str(788147+g)],check=True)
    field['evolution']=dict(reference=str((es/'final.net').relative_to(ROOT)),sha256=hashlib.sha256((es/'final.net').read_bytes()).hexdigest())
    if not (folder/'calendars/manifest.json').is_file():
        subprocess.run([sys.executable,HERE/'calendars_holdout.py',folder],check=True)
    clocks=json.loads((folder/'calendars/manifest.json').read_text())['selected']
    init=EVIDENCE/'bootstrap-wtap/initial.net'
    field['positive-init']=dict(reference=str(init.relative_to(ROOT)),sha256=hashlib.sha256(init.read_bytes()).hexdigest())
    opponents=[('ppo',inc),('evolution',inc),('positive',INCUMBENT),('positive-init',INCUMBENT),('wtap',inc),('original-wtap',INCUMBENT)]
    if 'known' in searches:opponents.append(('known',inc))
    w=Worker();report={}
    try:
        for tag,opp in opponents:
            actor=field['wtap' if tag=='original-wtap' else tag]['reference'];rows=[];ids=[]
            for i,tape in enumerate(clocks):
                for seat in 'ab':
                    for a,b in RTTS:
                        rng=random.Random(943793+g*9037+i*65011+a*127+b*31+(seat=='a'))
                        rows.append([actor,field[opp]['reference'],tape,seat,a,b,rng.getrandbits(60),rng.getrandbits(60)]);ids.append([i,seat,a,b])
            res=[]
            for j in range(0,len(rows),32):res.extend(w.run(rows[j:j+32],False))
            values=np.array([r[0] for r in res]).reshape(len(clocks),8).mean(axis=1)
            report[tag]=dict(bounds=interval(values),score=float(values.mean()),games=[k+[r[0]] for k,r in zip(ids,res)])
    finally:w.close()
    def difference(a,b):
        return np.array([r[-1]-s[-1] for r,s in zip(report[a]['games'],report[b]['games'])]).reshape(len(clocks),8).mean(axis=1)
    report['positive_gain_bounds']=lower_contrast(difference('positive','positive-init'))
    report['responses']={f'sg{g:03d}-extra-{t}':dict(**field[t],opponent=inc,score=report[t]['score'])
        for t in ('ppo','evolution') if report[t]['bounds'][0]>.55}
    if 'known' in searches and report['known']['bounds'][0]>.55:
        report['confirmed_archive_weakness']=dict(name=known,opponent=inc,score=report['known']['score'])
    stop=bool(all(report[t]['bounds'][1]<.55 for t in searches)
        and report['positive']['bounds'][0]>.55 and report['positive_gain_bounds'][0]>0)
    report['simulation_plateau']=stop
    if stop:report['native']=native_check(g,folder,field,inc,searches)
    report['budgeted_plateau']=bool(stop and report['native']['passed'])
    write_json(folder/'verdict.json',report,fixed=True);return report

def native_check(g,folder,field,inc,searches):
    from native_league import WT,fixtures,cohort,publish
    from promotion import actual_name
    from forge_window import cohort_window
    roles=[*searches,'positive','positive-init','wtap']
    names={}
    for t in roles:
        if t=='wtap':names[t]=WT;continue
        n=f'sg{g:03d}-extra-{t}'
        names[t]=publish(n,field[t],f'Frozen plateau check at 7 CPS: {t}')
    foes=list(dict.fromkeys([inc,INCUMBENT,'wtap']))
    plan=fixtures(f'sg{g:03d}-plateau',[names[t] for t in roles],[actual_name(n,field) for n in foes],64,7)
    with cohort_window(folder/'native') as bridge:results=cohort(folder/'native',plan,bridge,block_barrier=True)
    records={};rows={}
    for t in roles:
        rows[t]={}
        for n in foes:
            subset=sorted((q for q,v in plan.items() if v['arm']==names[t] and v['opponent']==actual_name(n,field)),key=lambda q:plan[q]['block'])
            vals=[results[q]['score'] for q in subset];rows[t][n]=vals
            records[t+' / '+n]=dict(wins=vals.count(1),draws=vals.count(.5),losses=vals.count(0),bounds=interval(vals))
    gain=lower_contrast(np.array(rows['positive'][INCUMBENT])-rows['positive-init'][INCUMBENT])
    passed=(all(records[t+' / '+inc]['bounds'][1]<.55 for t in searches)
        and records['positive / '+INCUMBENT]['bounds'][0]>.55 and gain[0]>0)
    return dict(cells=records,positive_gain_bounds=gain,passed=bool(passed))
