"""Select opposing responses from continuation payoffs after contact, then confirm on separate clocks."""
from itertools import combinations,permutations
from assay import inputs,java_rows,lower_contrast,development,evaluate
from style_drill import pair,student,governed
from learner import *
import sys

DIR=EVIDENCE/'fork-drill'
SEED=2473
POST=[INCUMBENT,'mmc13-actor-2-fit','v4-reply-g0312','v4-sprint-link-jitter-15',
      'sumo-own-ping','v3-memory-27','v3-link-fixed-63','population-01']
RESP=['wtap',BASE_COUNTER,'v3-memory-27','sumo-seven-ppo',INCUMBENT,'mmc13-trade-2-fit','shift-train-07']

def discovery():
    out=DIR/'selection.json'
    if out.exists():return json.loads(out.read_text())
    spec=dict(openers=['wtap','sumo-seven-first'],post=POST,responses=RESP,
        discovery='sixteen training clocks, four balanced seat and RTT cells per donor; after three visible hurt cues',
        rank='largest smaller of both opposing advantages; require positive gains, at least 50% eligible coverage per style and both winning responses score above 0.5',
        confirmation='only the selected ordered pair, eight development clocks and all seats and RTTs, new switch delay')
    write_json(DIR/'discovery-protocol.json',spec,fixed=True)
    styles=pair(DIR/'discovery-styles',INCUMBENT,1,{n:n for n in POST})
    candidates=[];tables={}
    for starter in spec['openers']:
        ref=str((EVIDENCE/('bootstrap-'+starter)/'initial.net').relative_to(ROOT))
        keys,rows=inputs(ref,styles,CLOCKS['train'],0,SEED)
        joined=[(k,r) for k,r in zip(keys,rows) if (RTTS.index((k[4],k[5]))+k[1]+int(k[3]=='a'))%2==0]
        table={}
        for name in POST:
            p=DIR/'discovery'/starter/(name+'.json')
            selected=[(k,r) for k,r in joined if k[0]==name]
            if not p.is_file():
                res=java_rows('ResponseReversal',[r for _,r in selected],[','.join(RESP)])
                write_json(p,[[k,v] for (k,_),v in zip(selected,res)],fixed=True)
            res=[v for _,v in json.loads(p.read_text()) if v['cut'] is not None]
            if len(res)>=len(selected)/2:table[name]={t:float(np.mean([v[t] for v in res])) for t in RESP}
        tables[starter]=table
        for f,h in combinations(table,2):
            for a,b in permutations(RESP,2):
                x=table[f][a]-table[f][b];y=table[h][b]-table[h][a]
                if min(x,y)>0 and min(table[f][a],table[h][b])>.5:
                    candidates.append((min(x,y),x+y,starter,f,h,a,b))
    outdata=dict(scores=tables,chosen=max(candidates) if candidates else None)
    write_json(out,outdata,fixed=True);return outdata

def qualify(choice):
    _,_,starter,f,h,a,b=choice
    specfile=DIR/'assay.json';roles={'early':f,'late':h}
    if not specfile.exists():
        latest=max(int(p.stem[1:]) for p in (ROOT/'runtime/results').glob('m[0-9]*.json') if p.stem[1:].isdigit())
        write_json(specfile,dict(train=pair(DIR/'train-styles',INCUMBENT,1,roles),
            dev=pair(DIR/'development-styles',INCUMBENT,3,roles),
            test=pair(DIR/'unopened-styles','v4-reply-g0312',5,roles),calendar_count=32,first_future_id=latest+8,
            matches_per_test_style=256,max_scenarios_per_test_style=8192,response_candidates=list(dict.fromkeys(['wtap',a,b])),
            labels_at_inference=False,scope='known post-switch identities, unseen opening and delay, fresh timing donors'),fixed=True)
    spec=json.loads(specfile.read_text());p=DIR/'pilot/adaptation-development.json'
    if p.exists():return json.loads(p.read_text())
    ref=str((EVIDENCE/('bootstrap-'+starter)/'initial.net').relative_to(ROOT));by={};records=[]
    for rep in range(4):
        keys,rows=inputs(ref,spec['dev'],CLOCKS['dev'],rep,SEED)
        res=java_rows('ResponseReversal',rows,[','.join(spec['response_candidates'])])
        records.extend(zip(keys,res))
    for style,first,second in [('early',a,b),('late',b,a)]:
        clocks=[]
        for i in range(8):
            v=[r[first]-r[second] for k,r in records if k[:2]==[style,i] and r['cut'] is not None]
            if v:clocks.append(float(np.mean(v)))
        by[style]=[float(np.mean(clocks)),*lower_contrast(clocks,.025)] if len(clocks)>=6 else None
    ok=all(v and v[1]>0 for v in by.values())
    result=dict(eligible_for_sealed_test=bool(ok),effects=by,verified_reversals=[dict(early=a,late=b)] if ok else [])
    write_json(DIR/'pilot/confirmation-games.json',records,fixed=True);write_json(p,result,fixed=True);return result

def worker(op):
    subprocess.run([sys.executable,HERE.parent/'cpu_limit.py','nice','-n','8',sys.executable,__file__,op],check=True)

if __name__=='__main__':
    op=sys.argv[1] if len(sys.argv)>1 else 'run'
    if op=='discovery':discovery()
    elif op=='confirm':qualify(discovery()['chosen'])
    elif op=='student-check':development(SEED,DIR,json.loads((DIR/'assay.json').read_text()))
    elif op=='memory':evaluate(SEED,DIR)
    elif op=='run':
        worker('discovery');choice=json.loads((DIR/'selection.json').read_text())['chosen']
        if not choice:sys.exit(0)
        worker('confirm')
        if not json.loads((DIR/'pilot/adaptation-development.json').read_text())['eligible_for_sealed_test']:sys.exit(0)
        for kind in ('gru','mlp'):student(kind,root=DIR)
        worker('student-check')
        if json.loads((DIR/'adaptation-development.json').read_text())['eligible_for_sealed_test']:
            subprocess.run([sys.executable,HERE/'calendars_holdout.py',DIR],check=True);worker('memory')
