"""Frozen development round robin. Save individual games so both seats and all RTT cells are inspectable."""
from itertools import combinations_with_replacement
from scipy.stats import t as student
from learner import *

def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()

def historical():
    fighters={}
    for line in FIELD_PATH.read_text().splitlines():
        s=line.split('\t')
        fighters[s[0]]=dict(reference=s[0],sha256=s[2] if len(s)>1 else digest('script-v1/'+s[0]))
    return fighters

def pin_model(path):
    raw=(path/'current.net').read_bytes();freeze(path/'final.net',raw)
    return dict(reference=str((path/'final.net').relative_to(ROOT)),sha256=hashlib.sha256(raw).hexdigest())

def schedule(left,right,references):
    result=[];scenarios=[]
    # Same environmental cells for every matchup. Seed each immutable fighter independently per seat.
    for rep in range(4):
        for index,tape in enumerate(CLOCKS['dev']):
            for seat in 'ab':
                for a,b in RTTS:
                    def seed(name,s):
                        return int(digest(f'league-dev-1/{name}/{rep}/{index}/{s}/{a if seat=="a" else b}/{b if seat=="a" else a}')[:15],16)
                    result.append((references[left]['reference'],references[right]['reference'],tape,seat,a,b,seed(left,seat),seed(right,'b' if seat=='a' else 'a')))
                    scenarios.append([index,rep,seat,a,b])
    return result,scenarios

def games(worker,field,left,right):
    a,b=sorted([left,right])
    aid,bid=field[a]['sha256'],field[b]['sha256']
    cache=EVIDENCE/'dev-games'/f'{a}_{aid[:10]}--{b}_{bid[:10]}.json'
    if not cache.exists():
        result,keys=schedule(a,b,field)
        rows=[]
        for start in range(0,len(result),32):
            rows.extend(worker.run(result[start:start+32],False))
        write_json(cache,list(k+[r[0],r[1]] for k,r in zip(keys,rows)),fixed=True)
    rows=json.loads(cache.read_text())
    if len(rows)!=256:raise ValueError(cache)
    if a!=left:return [[i,j,'b' if s=='a' else 'a',y,x,1-v,end] for i,j,s,x,y,v,end in rows]
    return rows

def stats(rows):
    v=np.array([r[5] for r in rows])
    def summary(vals):
        return dict(wins=int(sum(s==1 for s in vals)),draws=int(sum(s==.5 for s in vals)),losses=int(sum(s==0 for s in vals)),score=float(np.mean(vals)))
    out=summary(v);out['seat']={s:summary([r[5] for r in rows if r[2]==s]) for s in 'ab'}
    out['rtt']={f'{a}/{b}':summary([r[5] for r in rows if r[3:5]==[a,b]]) for a,b in RTTS}
    out['clock']=[float(np.mean([r[5] for r in rows if r[0]==i])) for i in range(len(CLOCKS['dev']))]
    out['seconds_quantiles']=(np.quantile([r[6] for r in rows],[0,.25,.5,.75,1])/1000).tolist()
    return out

def interval(scores,alpha=.05):
    v=np.array(scores);n=len(v);m=v.mean()
    # Across donor means. Degenerate observations cannot justify a zero-width bound.
    if n<2:raise ValueError('Need independent donors')
    if v.min()==v.max() or np.count_nonzero(v!=v[0])<2:
        d=math.sqrt(math.log(1/alpha)/(2*n))
    else:d=student.ppf(1-alpha,n-1)*v.std(ddof=1)/math.sqrt(n)
    return [max(0.,float(m-d)),min(1.,float(m+d))]

def counter_gate(worker,field,name,incumbent):
    fit=stats(games(worker,field,name,incumbent)); baseline=stats(games(worker,field,'wtap',incumbent))
    limits=interval(fit['clock'])
    return dict(incumbent=incumbent,challenger=name,challenger_result=fit,wtap=baseline,
                donor_bounds=limits,confirmed=limits[0]>.55,scope='development simulation')

def tournament(worker,field,families,incumbent,path):
    matrix={a:{} for a in field}
    for a,b in combinations_with_replacement(sorted(field),2):
        rows=games(worker,field,a,b)
        matrix[a][b]=stats(rows)
        if a!=b:matrix[b][a]=stats([[i,j,'b' if s=='a' else 'a',y,x,1-v,end] for i,j,s,x,y,v,end in rows])
    ranks=[]
    for name,row in matrix.items():
        family={f:float(np.mean([row[n]['score'] for n in ns])) for f,ns in families.items()}
        weakest=min(family,key=family.get);avg=float(np.mean(list(family.values())))
        ranks.append(dict(name=name,field_mean=avg,worst_family=weakest,worst=family[weakest],merit=(avg+family[weakest])/2,
                          family=family,vs_wtap=row['wtap'],wtap_vs_weakest=float(np.mean([matrix['wtap'][n]['score'] for n in families[weakest]]))))
    ranks.sort(key=lambda r:(-r['merit'],-r['field_mean'],r['name']!=incumbent,r['name']))
    answer=dict(ranking=ranks,winner=ranks[0]['name'],incumbent=incumbent,field=field,families=families,matrix=matrix)
    write_json(path,answer,fixed=True);return answer
