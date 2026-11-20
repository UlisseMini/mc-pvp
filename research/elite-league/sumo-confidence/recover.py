"""Retry only failed physical audits, retaining original request IDs and failure reasons."""
from study import D,ROOT,read,freeze,play
from check_games import check
from time import sleep,time

def certified(mid,trial):
    for _ in range(20):
        try:return check(mid,trial)
        except (EOFError,FileNotFoundError):
            if time()-read(ROOT/'runtime/results'/(mid+'.json'))['finish']/1000>120:raise
            sleep(2)
    return check(mid,trial)

def pending():
    folder=D/'verified'
    for invalid_file in sorted(folder.glob('[gsoecklqht][0-9][0-9]-invalid.json')):
        tag=invalid_file.name.split('-')[0];dest=folder/(tag+'.json')
        if dest.exists():continue
        data=read(D/'native'/(tag+'.json'));invalid=read(invalid_file)
        if data['plan'].keys()!=data['matches'].keys():continue
        rows={q:certified(m,data['plan'][q]) for q,m in data['matches'].items()}
        assert {q for q,r in rows.items() if not r['valid']}==set(invalid)
        history=[]
        for attempt in range(1,5):
            retry={};sources={}
            for q,r in rows.items():
                if r['valid']:continue
                new=f'{q}-r{attempt}';history.append(dict(original=q,**r))
                t=data['plan'][q];words=t['command'].split();words[words.index(q)]=new
                retry[new]=dict(t,command=' '.join(words));sources[new]=q
            if not retry:break
            name=tag+'-retry'+str(attempt);play(name,retry)
            stored=read(D/'native'/(name+'.json'))
            for q,trial in retry.items():
                mid=stored['matches'][q];assert read(ROOT/'runtime/results'/(mid+'.json'))['request']==q
                rows[sources[q]]=certified(mid,trial)
        if not all(r['valid'] for r in rows.values()):raise RuntimeError('Repeated native control failures: '+tag)
        freeze(dest,dict(original=data['matches'],rows=rows,invalid=history))

if __name__=='__main__':pending()
