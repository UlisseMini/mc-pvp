"""A failed ordinary search alone cannot establish a fixed point."""
from generation import initialize
from train import configure
from run_training import train
from league_tournament import *
from assay import CP,lower_contrast
import sys

def search(g):
    folder=EVIDENCE/f'g{g:03d}'/'plateau';cfg=initialize(g);field=cfg['field'].copy()
    inc=cfg['incumbent'];source=EVIDENCE/f'g{g:03d}'/'main/trainer.pt'
    spec=folder/'assay.json'
    if not spec.exists():
        last=max(int(p.stem[1:]) for p in (ROOT/'runtime/results').glob('m[0-9]*.json') if p.stem[1:].isdigit())
        write_json(spec,dict(first_future_id=last+4,calendar_count=32,opponent=inc,
            control='PPO from W-tap imitation must improve on that frozen initialization against the original incumbent',
            budget=32768),fixed=True)
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
    stop=bool(report['ppo']['bounds'][1]<.55 and report['evolution']['bounds'][1]<.55
        and report['positive']['bounds'][0]>.55 and report['positive_gain_bounds'][0]>0)
    report['simulation_plateau']=stop
    if stop:report['native']=native_check(g,folder,field,inc)
    report['budgeted_plateau']=bool(stop and report['native']['passed'])
    write_json(folder/'verdict.json',report,fixed=True);return report

def native_check(g,folder,field,inc):
    from native_league import WT,fixtures,cohort
    from promotion import actual_name
    from forge_window import window
    roles=['ppo','evolution','positive','positive-init','wtap']
    names={}
    for t in roles:
        if t=='wtap':names[t]=WT;continue
        n=f'sg{g:03d}-extra-{t}'
        names[t]=actual_name(n,{n:field[t]})
    foes=list(dict.fromkeys([inc,INCUMBENT,'wtap']))
    plan=fixtures(f'sg{g:03d}-plateau',[names[t] for t in roles],[actual_name(n,field) for n in foes],64,7)
    with window() as bridge:results=cohort(folder/'native',plan,bridge)
    records={};rows={}
    for t in roles:
        rows[t]={}
        for n in foes:
            subset=sorted((q for q,v in plan.items() if v['arm']==names[t] and v['opponent']==actual_name(n,field)),key=lambda q:plan[q]['block'])
            vals=[results[q]['score'] for q in subset];rows[t][n]=vals
            records[t+' / '+n]=dict(wins=vals.count(1),draws=vals.count(.5),losses=vals.count(0),bounds=interval(vals))
    gain=lower_contrast(np.array(rows['positive'][INCUMBENT])-rows['positive-init'][INCUMBENT])
    passed=(all(records[t+' / '+inc]['bounds'][1]<.55 for t in ('ppo','evolution'))
        and records['positive / '+INCUMBENT]['bounds'][0]>.55 and gain[0]>0)
    return dict(cells=records,positive_gain_bounds=gain,passed=bool(passed))
