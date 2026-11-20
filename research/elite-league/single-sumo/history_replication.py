"""Replicate the frozen history comparison using fresh action seeds and separately spaced timing donors."""
import gzip,hashlib,subprocess,sys,time
from pathlib import Path
from scipy import stats
from learner import ROOT,HERE,BUILD,CLOCKS,RTTS,Worker,np,torch,json,random,freeze,write_json
from recognition_payoff import FOLDER,FOES,CUTOFF,decisions
from adaptive_router import OUT as CLIENT
from recognition import DIR

def protocol():
    p=FOLDER/'replication';file=p/'protocol.json'
    if file.exists():return json.loads(file.read_text())
    latest=max(int(f.stem[1:]) for f in (ROOT/'runtime/results').glob('m[0-9]*.json') if f.stem[1:].isdigit())
    d=dict(old_clocks=CLOCKS['dev'],fixed_clock_reps=64,spaced_clock_reps=8,first_future_id=latest+8,spaced_count=32,
        spacing_ms=60000,rule='first valid completed bot-bot sumo timing donor at least 60 seconds after previous accepted match finished, evaluated in increasing match ID order',
        seed=971770322,fixed='branch-B',classifier_seed=621,arm_hashes={k:v['sha256'] for k,v in json.loads((CLIENT/'parity.json').read_text())['checks'].items()},
        primary='actual minus fixed and actual minus opposite history, frozen classifiers and fixed expert; include draws as half and both opponents equally',
        intervals='conditional 95% and Bonferroni-adjusted 97.5% t intervals from independent replications within donor/seat/RTT; for spaced clocks also interval across the 32 nonoverlapping clock means',
        fixed_clock_eval='one analysis after all eight fixed clocks; never select checkpoints or seeds by these outcomes')
    write_json(file,d,fixed=True);return d

def capture():
    from calendars_holdout import tape,supply
    spec=protocol();folder=FOLDER/'replication/spaced';folder.mkdir(parents=True,exist_ok=True)
    dest=folder/'manifest.json';selected=[];skipped={};after=0;num=spec['first_future_id'];idle=time.monotonic()
    while len(selected)<spec['spaced_count'] and not dest.exists():
        mid=f'm{num}';game=ROOT/'runtime/results'/(mid+'.json');out=folder/(mid+'.txt.gz')
        if not game.exists():
            if (ROOT/'runtime/results'/f'm{num+12}.json').exists() and mid not in {m['id'] for m in json.loads((ROOT/'runtime/status.json').read_text())['matches']}:
                skipped[mid]='no completed fixture';num+=1;continue
            if time.monotonic()-idle>150:
                supply({'first_future_id':spec['first_future_id']},num);idle=time.monotonic()
            time.sleep(3);continue
        d=json.loads(game.read_text())
        if d['mode']!='sumo' or any(not d[s].startswith('LabSlot') for s in 'ab'):skipped[mid]='outside bot-only sumo'
        elif d['start']<after:skipped[mid]='within exclusion period'
        else:
            if not out.exists():
                bad=folder/(mid+'.invalid.json')
                if not bad.exists():
                    op=subprocess.run([sys.executable,HERE.parent/'cpu_limit.py','--policy','nice','-n','8',sys.executable,HERE/'calendars_holdout.py','one',mid],capture_output=True)
                    if not op.returncode:freeze(out,gzip.compress(op.stdout,mtime=0))
                    elif time.time()*1000-d['finish']<90000:time.sleep(3);continue
                    else:
                        msg=op.stderr.decode().strip().splitlines()[-1]
                        if not msg.startswith(('ValueError:','FileNotFoundError:','StopIteration','EOFError','KeyError:')):raise RuntimeError(msg)
                        write_json(bad,{'reason':msg},fixed=True)
                if bad.exists():skipped[mid]=json.loads(bad.read_text())['reason']
            if out.exists():
                selected.append(str(out.relative_to(ROOT)));after=d['finish']+spec['spacing_ms']
        num+=1;idle=time.monotonic()
    if not dest.exists():write_json(dest,dict(selected=selected,skipped=skipped,minimum_match_gap_ms=spec['spacing_ms']),fixed=True)

def clock_games(scope,i):
    p=FOLDER/'replication';spec=protocol();out=p/scope/f'games-{i:02d}.json.gz'
    if out.exists():return
    clocks=spec['old_clocks'] if scope=='fresh-seeds' else json.loads((p/'spaced/manifest.json').read_text())['selected']
    num=spec['fixed_clock_reps' if scope=='fresh-seeds' else 'spaced_clock_reps']
    pin=json.loads((CLIENT/'runtime.json').read_text());w=Worker(runtime_directory=BUILD/pin['sim']);raw=[]
    try:
        for rep in range(num):
            cases=[];keys=[]
            for side in 'ab':
                for a,b in RTTS:
                    seed=f"{spec['seed']}/{scope}/{i}/{rep}/{side}/{a}/{b}"
                    rng=random.Random(int(hashlib.sha256(seed.encode()).hexdigest(),16));x,y=rng.getrandbits(60),rng.getrandbits(60)
                    cases.extend([[f,clocks[i],side,a,b,x,y] for f in FOES]);keys.append([rep,side,a,b])
            prefix=w.run([[DIR/'shared-mlp/initial.net',*r] for r in cases],True)
            obs=np.zeros((len(keys),2,CUTOFF,25),np.float32)
            for j,(_,_,features) in enumerate(prefix):
                n=min(CUTOFF,len(features));obs[j//2,j%2,:n]=features[:n,:25]
            pred=decisions(obs);results={}
            for name,ref in [('A',CLIENT/'branch-A.net'),('B',CLIENT/'branch-B.net'),('wtap','wtap')]:
                results[name]=np.array([q[0] for q in w.run([[ref,*r] for r in cases],False)]).reshape(-1,2)
            if rep==0:
                direct=[q[0] for q in w.run([[CLIENT/'history.net',*r] for r in cases],False)]
                assert np.array_equal(np.where(pred['history']==0,results['A'],results['B']).flatten(),direct)
            actual=np.where(pred['history']==0,results['A'],results['B'])
            swap=np.where(pred['opposite-history']==0,results['A'],results['B'])
            for j,key in enumerate(keys):raw.append(key+[v[j].tolist() for v in [actual,results['B'],swap,results['wtap']]])
    finally:w.close()
    freeze(out,gzip.compress(json.dumps(raw,separators=(',',':')).encode(),mtime=0))

def summarize(scope,count):
    p=FOLDER/'replication'/scope;names=['actual','fixed','swapped','wtap'];values={k:[] for k in names}
    for i in range(count):
        data=json.loads(gzip.decompress((p/f'games-{i:02d}.json.gz').read_bytes()))
        for j,k in enumerate(names):values[k].append(np.array([row[4+j] for row in data]).reshape(-1,8,2))
    values={k:np.stack(v) for k,v in values.items()};out={}
    for k,v in {**values,**{'actual minus '+s:values['actual']-values[s] for s in ['fixed','swapped']}}.items():
        paired=v.mean(axis=3);d,r,c=paired.shape;means=paired.mean(axis=(1,2));vv=paired.var(axis=1,ddof=1)/(r*(d*c)**2)
        se=np.sqrt(vv.sum());m=float(means.mean())
        def ci(q,s,df):
            radius=stats.t.ppf((1+q)/2,df)*s;return [float(m-radius),float(m+radius)]
        result=dict(mean=m,by_clock=means.tolist(),by_opponent=v.mean(axis=(0,1,2)).tolist())
        if se>0:
            df=vv.sum()**2/((vv**2).sum()/(r-1))
            result.update(conditional_95=ci(.95,se,df),conditional_97_5=ci(.975,se,df))
            if k not in names:result['paired_t_p']=float(2*stats.t.sf(abs(m/se),df))
        else:
            # The deterministic W-tap arm can repeat perfectly inside every fixed cell.
            # Keep a finite-sample bound even when no sampling variance was observed.
            low,high=(0,1) if k in names else (-1,1)
            def bounded(q):
                radius=(high-low)*np.sqrt(np.log(2/(1-q))/(2*paired.size))
                return [max(low,m-radius),min(high,m+radius)]
            result.update(conditional_95=bounded(.95),conditional_97_5=bounded(.975),
                          interval_method='Hoeffding bound for independent paired fixtures; zero observed within-cell variance')
        if scope=='spaced':result.update(clock_95=ci(.95,stats.sem(means),d-1),clock_97_5=ci(.975,stats.sem(means),d-1))
        if k in names:result.update(wins=int((v==1).sum()),draws=int((v==.5).sum()),losses=int((v==0).sum()))
        out[k]=result
    write_json(p/'summary.json',out,fixed=True)
def run(scope):
    spec=protocol()
    if scope=='spaced':capture()
    count=8 if scope=='fresh-seeds' else spec['spaced_count']
    for i in range(count):
        subprocess.run([sys.executable,HERE.parent/'cpu_limit.py','--policy','nice','-n','8',sys.executable,__file__,'one',scope,str(i)],check=True)
    summarize(scope,count)
if __name__=='__main__':
    if sys.argv[1]=='one':clock_games(sys.argv[2],int(sys.argv[3]))
    elif sys.argv[1]=='summarize':
        scope=sys.argv[2];summarize(scope,8 if scope=='fresh-seeds' else protocol()['spaced_count'])
    else:run(sys.argv[1])
