"""Test whether earlier opponent history changes appropriate actions and subsequent ring-outs."""
import gzip
from itertools import combinations
from collections import defaultdict
from learner import *
from league_tournament import stats,interval

CP=os.pathsep.join(map(str,(BUILD/'classes',ROOT/'build/elite-league/sumo-confidence/classes',ROOT/'build/elite-league/sumo-inputs/classes')))
def java_rows(program,rows,args=(),mode=None):
    with tempfile.TemporaryDirectory(dir=BUILD) as tmp:
        plan=Path(tmp)/'in';out=Path(tmp)/'out'
        plan.write_text(''.join('\t'.join(map(str,r))+'\n' for r in rows))
        subprocess.run(['java','-XX:ActiveProcessorCount=1','-XX:+UseSerialGC','-Xms64m','-Xmx700m','-cp',CP,program,
                        *([mode] if mode else []),plan,out,*args],check=True,cwd=ROOT)
        result=[json.loads(s) for s in out.read_text().splitlines()]
        assert len(result)==len(rows)
        return result
def inputs(actor,styles,clocks,rep,g):
    rows=[];keys=[]
    for i,clock in enumerate(clocks):
        for seat in 'ab':
            for own,other in RTTS:
                rng=random.Random(208901101+g*10849+rep*9301+i*100003+own*113+other*19+(seat=='a'))
                a=rng.getrandbits(60);b=rng.getrandbits(60)
                for style,ref in styles.items():
                    rows.append([actor,ref['reference'],clock,seat,own,other,a,b])
                    keys.append([style,i,rep,seat,own,other])
    return keys,rows
def lower_contrast(values,alpha=.05):
    # Outcomes are paired at the world/clock level. A difference lies in [-1,1].
    lo,hi=interval((np.array(values)+1)/2,alpha)
    return [2*lo-1,2*hi-1]
def development(g,folder,spec):
    out=folder/'adaptation-development.json'
    if out.exists():return json.loads(out.read_text())
    actor=str((folder/'main/final.net').relative_to(ROOT));keys=[];rows=[]
    for rep in range(4):
        k,r=inputs(actor,spec['dev'],CLOCKS['dev'],rep,g);keys.extend(k);rows.extend(r)
    responses=spec['response_candidates'];evaluated=java_rows('ResponseReversal',rows,[','.join(responses)])
    buckets=defaultdict(list)
    for k,v in zip(keys,evaluated):buckets[k[0],k[1]].append(v)
    summary={};means={}
    for style in spec['dev']:
        raw=[v for k,v in zip(keys,evaluated) if k[0]==style]
        valid=[v for v in raw if v['cut'] is not None];allheads=len(raw)
        summary[style]=dict(ordinary=len(raw),eligible=len(valid),score_from_spawn=float(np.mean([v['full'] for v in raw])),
            response_scores={r:float(np.mean([v[r] for v in valid])) if valid else None for r in responses})
        for name in responses:means[style,name]=[np.mean([v[name] for v in buckets[style,i] if v['cut'] is not None])
                        if any(v['cut'] is not None for v in buckets[style,i]) else None for i in range(len(CLOCKS['dev']))]
    reversals=[];tests=len(responses)*(len(responses)-1)
    for early,late in combinations(responses,2):
        for a,b in [(early,late),(late,early)]:
            delta=[]
            for style,x,y in [('early',a,b),('late',b,a)]:
                pairs=[s-t for s,t in zip(means[style,x],means[style,y]) if s is not None and t is not None]
                if len(pairs)<6:break
                delta.append([float(np.mean(pairs)),*lower_contrast(pairs,.05/(tests*2))])
            if len(delta)==2 and all(d[1]>0 for d in delta):
                reversals.append(dict(early=a,late=b,effects=delta))
    result=dict(coverage=summary,verified_reversals=reversals,eligible_for_sealed_test=bool(reversals),
        wtap_column='response_scores.wtap is W-tap from the identical fork in each style')
    write_json(out,result,fixed=True);return result

def gzdata(path,producer):
    if not path.exists():
        rows=producer()
        freeze(path,gzip.compress(('\n'.join(json.dumps(r,separators=(',',':')) for r in rows)+'\n').encode(),mtime=0))
    return [json.loads(line) for line in gzip.decompress(path.read_bytes()).splitlines()]

def donors(records):
    bins=defaultdict(list)
    for i,(key,r,_) in enumerate(records):
        c=r.get('context')
        if c:bins[key[0],*key[3:6],*c[6:]].append(i)
    order=list(range(len(records)));random.Random(92756).shuffle(order)
    choices={'early':[],'late':[]};limits=np.array([1000,100,100,.25,.1,.1])
    # Index arrays in each cell once to keep the caliper search bounded.
    index={k:(np.array(v),np.array([records[i][1]['context'][:6] for i in v]),np.array([records[i][0][1] for i in v])) for k,v in bins.items()}
    for i in order:
        k,r,row=records[i];style=k[0];c=r.get('context')
        if not c or len(choices[style])>=256:continue
        selected=[];rng=np.random.default_rng(7812997+i)
        for s in [style,'late' if style=='early' else 'early']:
            idx=index.get((s,*k[3:6],*c[6:]))
            if idx is None:break
            ids,ctx,clocks=idx
            ok=(clocks!=k[1]) & np.all(abs(ctx-c[:6])<=limits,axis=1)
            if not ok.any():break
            selected.append(int(rng.choice(ids[ok])))
        if len(selected)==2:choices[style].append((i,*selected))
    return choices

def evaluate(g):
    folder=EVIDENCE/f'g{g:03d}';spec=json.loads((folder/'assay.json').read_text())
    dev=development(g,folder,spec)
    if not dev['eligible_for_sealed_test']:return
    seal=folder/'calendars/manifest.json'
    if not seal.exists():raise RuntimeError('Prospectively enrolled timing donors still pending')
    clocks=json.loads(seal.read_text())['selected'];actor=str((folder/'main/final.net').relative_to(ROOT))
    dataset=[];choices={}
    for rep in range(spec['max_scenarios_per_test_style']//(len(clocks)*8)):
        keys,rows=inputs(actor,spec['test'],clocks,rep,g)
        result=gzdata(folder/'memory'/f'catalog-{rep:02}.jsonl.gz',lambda:java_rows('MemoryAssay',rows,mode='catalog'))
        dataset.extend(zip(keys,result,rows))
        choices=donors(dataset)
        if min(map(len,choices.values()))>=spec['matches_per_test_style']:break
    # Both versions execute the same opening. At equal pre-cue states a different assigned future style must have zero effect.
    with tempfile.TemporaryDirectory(dir=BUILD) as temp:
        plan=Path(temp)/'input';out=Path(temp)/'pre-cue'
        plan.write_text(''.join('\t'.join(map(str,row))+'\n' for _,_,row in dataset[:128]))
        subprocess.run(['java','-XX:ActiveProcessorCount=1','-XX:+UseSerialGC','-Xmx600m','-cp',CP,'PreCueCheck',plan,out],check=True)
        negative=json.loads(out.read_text())
    ordinary={}
    worker=Worker()
    try:
        # Report from-spawn scores even for fights ending before the landmark. Compare both controls on identical fixture cells.
        for name,reference in [('wtap','wtap'),('matched_mlp',str((folder/'mlp/final.net').relative_to(ROOT)))]:
            cells={k:[] for k in spec['test']}
            selected=dataset[:len(clocks)*8*2]
            for j in range(0,len(selected),32):
                batch=selected[j:j+32];res=worker.run([[reference,*row[1:]] for _,_,row in batch],False)
                for (key,_,_),r in zip(batch,res):cells[key[0]].append([*key[1:],r[0],r[1]])
            ordinary[name]=cells
    finally:worker.close()
    write_json(folder/'memory/from-spawn-controls.json',ordinary,fixed=True)
    keys=[];runs=[]
    for style,group in choices.items():
        for i,same,other in group:
            key,rec,row=dataset[i];keys.append((key,i,same,other))
            runs.append([*row,rec['context'][0],','.join(map(str,dataset[same][1]['donor'])),','.join(map(str,dataset[other][1]['donor'])),int(len(runs)<3)])
    results=java_rows('MemoryAssay',runs,mode='fork') if runs else []
    intended=dev['verified_reversals'][0]
    responses=[intended['early'],intended['late']]
    targets=java_rows('ResponseReversal',[dataset[i][2] for _,i,_,_ in keys],[','.join(responses),'target']) if runs else []
    frozen=[]
    for (k,i,s,o),r,target in zip(keys,results,targets):
        if r['intact']['score']!=dataset[i][1]['outcome'] or r['intact']['end']!=dataset[i][1]['time']:raise AssertionError('Untouched fork diverged')
        frozen.append(dict(key=k,donors=[s,o],recipient=i,branches=r,response_buttons=target.get('response_buttons')))
    write_json(folder/'memory/forks.json',frozen,fixed=True)
    analysis={};both=True
    for style in choices:
        v=[r for r in frozen if r['key'][0]==style];pool=[b for k,b,_ in dataset if k[0]==style]
        h={'total':len(pool),'eligible':sum('context' in r for r in pool),'matched':len(v),
           'ordinary_wld':[sum(r['outcome']==q for r in pool) for q in (1,0,.5)],
           'seconds_quantiles':(np.quantile([r['time'] for r in pool],[.1,.5,.9])/1000).tolist(),
           'fresh_hits_quantiles':np.quantile([r['fresh'] for r in pool],[.1,.5,.9]).tolist()}
        for a,b in [('same','opposite'),('intact','zero'),('intact','opposite')]:
            by=defaultdict(list)
            for r in v:by[r['key'][1]].append(r['branches'][a]['score']-r['branches'][b]['score'])
            vals=[np.mean(z) for z in by.values()]
            h[a+'-'+b]=[float(np.mean(vals)),*lower_contrast(vals,.05/8)] if len(vals)>1 else None
        h['different_first_buttons']=sum(r['branches']['same']['first']!=r['branches']['opposite']['first'] for r in v)
        directed=defaultdict(list)
        for r in v:
            buttons=r['response_buttons']
            if not buttons or buttons[1]==buttons[2]:continue
            expected=buttons[1 if style=='early' else 2];other=buttons[2 if style=='early' else 1]
            def alignment(arm):
                a=r['branches'][arm]['first']
                if a<0:return 0
                # Compare physical controls independently, keeping W/sprint separate from horizontal strafe.
                def unpack(n):return (n%4,n//4%3,n//12%2,n>=24)
                return sum(int(x==y)-int(x==z) for x,y,z in zip(unpack(a),unpack(expected),unpack(other)))/4
            directed[r['key'][1]].append((alignment('same')-alignment('opposite'))/2)
        vals=[np.mean(a) for a in directed.values()]
        h['appropriate_button_shift']=[float(np.mean(vals)),*lower_contrast(vals,.05/8)] if len(vals)>1 else None
        both &= bool(h['appropriate_button_shift'] and h['appropriate_button_shift'][1]>0)
        both &= len(v)==256 and all(h[q] and h[q][1]>0 for q in ['same-opposite','intact-opposite']) and h['different_first_buttons']>0
        analysis[style]=h
    write_json(folder/'memory/evidence.json',dict(style=analysis,pre_cue=negative,causal_history_helped_both_styles=bool(both),
              scope='simulator; no independent human grade; opening probing not identified'),fixed=True)

if __name__=='__main__':
    import sys
    evaluate(int(sys.argv[1]))
