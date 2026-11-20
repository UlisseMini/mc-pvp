"""Does removing the training reward for timeouts encourage wins against the 7-CPS specialist?"""
import sys
import tempfile
from dedicated import BASE_COUNTER,DIR,BUILD,ROOT,write_json,np,json,subprocess,oracle_worker
from id_runner import progress,launch
from id_evaluate import make_panel,CLOCKS
from league_tournament import stats
from build import sim_paths
from id_native import native_name
from native_league import alias,fixtures,cohort,WT,RTTS
from forge_window import window

FOE=BASE_COUNTER
OUT=DIR/('ringout-only-'+FOE)
def diagnostic():
    name=OUT/'draw-diagnosis.json'
    if name.exists():return
    cells=make_panel(CLOCKS['dev'],1)
    model=DIR/('dedicated-'+FOE)/'n16384.net'
    with tempfile.TemporaryDirectory(dir=BUILD) as temp:
        from pathlib import Path
        p=Path(temp)/'cases.tsv'
        p.write_text(''.join('\t'.join(map(str,[clock,s,a,b,x,y]))+'\n' for _,_,s,a,b,clock,x,y in cells))
        raw=subprocess.check_output(['java','-XX:ActiveProcessorCount=1','-XX:+UseSerialGC','-Xmx650m','-cp',sim_paths()[0],
             'OpeningDiagnosis',model,FOE,p],cwd=ROOT,text=True)
    rows=[json.loads(l) for l in raw.splitlines()];summary={}
    for name_ in set(r['arm'] for r in rows):
        v=[r for r in rows if r['arm']==name_];s=[r['score'] for r in v]
        summary[name_]=dict(wins=s.count(1),draws=s.count(.5),losses=s.count(0),
            no_fresh_hits=sum(r['fresh']==0 for r in v),mean_fresh=float(np.mean([r['fresh'] for r in v])),
            mean_given=float(np.mean([r['given'] for r in v])),median_min_distance=float(np.median([r['min_distance'] for r in v])))
    write_json(OUT/'diagnosis-games.json',rows,fixed=True);write_json(name,summary,fixed=True)

def evaluate():
    w=oracle_worker();cells=make_panel(CLOCKS['dev'],4);result={};raw={}
    try:
        for foe in [FOE,'wtap']:
            result[foe]={};raw[foe]={}
            for arm,model in [('wins-only',OUT/'n16384.net'),('original',DIR/('dedicated-'+FOE)/'n16384.net'),('wtap','wtap')]:
                plan=[[model,foe,clock,s,a,b,x,y] for _,_,s,a,b,clock,x,y in cells];values=[]
                for j in range(0,len(plan),32):values.extend(w.run(plan[j:j+32],False))
                records=[[i,rep,s,a,b,r[0],r[1]] for (i,rep,s,a,b,_,_,_),r in zip(cells,values)]
                result[foe][arm]=stats(records);raw[foe][arm]=records
    finally:w.close()
    write_json(OUT/'games.json',raw,fixed=True);write_json(OUT/'development.json',result,fixed=True)

def native():
    models=[native_name(OUT/'n16384.net'),native_name(DIR/('dedicated-'+FOE)/'n16384.net'),WT]
    plan=fixtures('sg-draw-reward',models,[alias(FOE),WT],8,7)
    with window() as bridge:results=cohort(OUT/'native',plan,bridge)
    cells={}
    for foe in (alias(FOE),WT):
        cells[foe]={}
        for arm in models:
            qs=[q for q,t in plan.items() if t['arm']==arm and t['opponent']==foe]
            def tally(keys):
                v=[results[q]['score'] for q in keys]
                return dict(wins=v.count(1),draws=v.count(.5),losses=v.count(0))
            cells[foe][arm]=dict(**tally(qs),seat={s:tally([q for q in qs if plan[q]['side']==s]) for s in 'ab'},
                rtt={f'{a}/{b}':tally([q for q in qs if (plan[q]['rtt'],plan[q]['opponent_rtt'])==(a,b)]) for a,b in RTTS})
    write_json(OUT/'native/summary.json',cells,fixed=True)

if __name__=='__main__':
    if sys.argv[1:]==['diagnose']:diagnostic()
    elif sys.argv[1:]==['score']:evaluate()
    else:
        write_json(OUT/'protocol.json',dict(target=FOE,opponent_fixture_order='identical to the original single-opponent specialist, including checkpoint boundaries',
            initialization='same shared 180-unit MLP; identical optimizer, training seed and physical button sampling',
            change='PPO terminal target +1 win, -1 draw or loss; original value for a draw was zero',
            evaluation='final checkpoint after 16384 duels; report regulation win/draw/loss and the official half-draw score separately',
            native='matched final vs target and W-tap with original specialist and plain W-tap; eight native seat and RTT blocks'),fixed=True)
        launch('ringout_response.py','diagnose')
        while progress(OUT,'duels')<16384:launch('dedicated.py',FOE,16,'wins-only')
        launch('ringout_response.py','score');native()
