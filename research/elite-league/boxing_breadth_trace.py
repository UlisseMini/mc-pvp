"""Attribute the paired active-cycle changes to inputs and contacts against the hardest defender."""
import gzip
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
from adaptation import dump,freeze
from boxing_breadth import RESULT,arms,rivals
from results import model_game
from run import calendars,compile_model,java
from tournament import roster

def through_return(hits,side):
    out=[]
    for h in hits:
        out.append(h)
        if h[1]!=(side=='a'):break
    return out

def actions(events):
    return tuple(tuple(e[k] for k in ('key','lateral','click','jump')) for e in events if e['event']=='head' and e['subject'])

def run():
    rank=json.loads((RESULT/'development.json').read_text())['ranking']
    best=next(r for r in rank if r['name']!='wtap')
    lo=min(best['opponent_merit'],key=best['opponent_merit'].get)
    hi=max(best['opponent_merit'],key=best['opponent_merit'].get)
    with gzip.open(RESULT/'development.jsonl.gz','rt') as f:
        cases={(r['clock'],r['rtt'],r['side'],r['opponent']):r for r in map(json.loads,f)
               if r['arm']==best['name'] and r['opponent'] in (lo,hi) and '3' in r['forks']}
    dest=RESULT/'suffix-ledger.jsonl.gz'
    if not dest.exists():
        compile_model()
        with tempfile.TemporaryDirectory() as temp:
            t=Path(temp);roster(t/'foes',{n:rivals()[n][1] for n in (lo,hi)})
            with subprocess.Popen(java('BoxingSuffixTrace',arms()[best['name']],'--mouse-defenders',t/'foes',*calendars('development_selection_clocks')),stdout=subprocess.PIPE) as p:
                with (t/'rows.gz').open('wb') as r,gzip.GzipFile(fileobj=r,mode='wb',mtime=0) as f:shutil.copyfileobj(p.stdout,f)
                if p.wait():raise RuntimeError('Contact or movement audit failed')
            freeze(dest,(t/'rows.gz').read_bytes())
    evidence=[]
    with gzip.open(dest,'rt') as f:
        for item in map(json.loads,f):
            key=tuple(item[n] for n in ('clock','rtt','side','opponent'));case=cases.pop(key);cut=case['forks']['3']
            if item['landmark']!=cut['landmark']:raise ValueError('Different establishment time')
            obs={}
            results=model_game(case)['forks']['3']
            for a,n in [('wtap','wtap'),('specialist','rl')]:
                frm=item[a];hs=through_return(cut['committed']+cut[n]['hits'],item['side'])
                if hs!=through_return(frm['accepted'],item['side']):raise ValueError('Different accepted events: '+str(key))
                events=frm['events'];heads=[e for e in events if e['event']=='head' and e['subject']]
                contacts=[e for e in events if e['event']=='contact' and e['subject']]
                accepted=[e for e in events if e['event']=='hit']
                obs[a]=dict(cycles=results[n]['active']['completed_cycles'],terminal=results[n]['active']['terminals'],
                            first_heads=[{k:h[k] for k in ('t','key','lateral','jump','click','sprint','gap')} for h in heads[:4]],
                            contact_count=len(contacts),sprint_contacts=sum(c['sprint_before'] for c in contacts),accepted=accepted)
            a=actions(item['wtap']['events']);b=actions(item['specialist']['events']);change=next((i for i,(p,q) in enumerate(zip(a,b)) if p!=q),None)
            evidence.append(dict(clock=key[0],rtt=key[1],side=key[2],opponent=key[3],start=item['landmark'],first_input_difference=change,**obs))
    if cases:raise ValueError('Missing original paired starts '+str(list(cases)))
    dump(RESULT/'suffix-attribution.json',dict(arm=best['name'],weakest_defender=lo,strongest_defender=hi,cases=evidence))
    print(len(evidence),'matched starts',lo,hi,flush=True)
if __name__=='__main__':run()
