"""Survey the frozen bookmark list by replay, giving each recorded session an early representative."""
import argparse
from collections import Counter,defaultdict
import json
import gzip
from pathlib import Path
from measure import measure,props,REMOTE

def survey(base,out,retry=False):
    out.mkdir(exist_ok=True,parents=True);index=out/'bookmarks.json'
    if not index.exists():
        groups=defaultdict(list)
        for f in (base/'sumo-replays').glob('*.properties'):
            r=props(f);groups[r['capture']].append((int(r['start']),f.stem))
        for f in groups.values():f.sort()
        # Sample midway through each connection, then bisect the remaining intervals until every clip appears.
        def order(rows):
            intervals=[(0,len(rows))];seq=[]
            while intervals:
                a,b=intervals.pop(0)
                if a>=b:continue
                i=(a+b)//2;seq.append(rows[i][1]);intervals.extend([(a,i),(i+1,b)])
            return seq
        interleaved=[order(v) for _,v in sorted(groups.items())]
        clips=[row[i] for i in range(max(map(len,interleaved))) for row in interleaved if i<len(row)]
        index.write_text(json.dumps(dict(sessions=len(groups),clips=clips),indent=2)+'\n')
    clips=json.loads(index.read_text())['clips'];log=out/'measurements.jsonl'
    known={d['clip']:d for d in map(json.loads,log.read_text().splitlines())} if log.exists() else {}
    with log.open('a') as f:
        for n in clips:
            if n in known and (not retry or 'unmeasured' not in known[n]):continue
            try:row=measure(base,base/'sumo-replays'/(n+'.properties'))
            except (ValueError,OSError,EOFError,KeyError,IndexError) as e:row=dict(clip=n,unmeasured=str(e))
            f.write(json.dumps(row,separators=(',',':'))+'\n');f.flush();known[n]=row
            if len(known)%10==0:print(len(known),Counter((len(d['mask']),d['cells'],d['gap']) for d in known.values() if 'mask' in d),flush=True)
    print('Finished',len(known),flush=True)

def publish(base,work,out,exemplar):
    out.mkdir(exist_ok=True,parents=True)
    index=json.loads((work/'bookmarks.json').read_text())
    records={d['clip']:d for d in map(json.loads,(work/'measurements.jsonl').read_text().splitlines())}
    if set(index['clips'])!=records.keys():raise ValueError('Missing registered bookmarks')
    rows=[records[k] for k in index['clips']]
    groups=Counter((tuple(d['mask']),d['gap']) for d in rows if 'mask' in d)
    shapes=[dict(rows=mask,gap=gap,blocks=sum(row.count('#') for row in mask),clips=n,
                 sessions=len({d['session'] for d in rows if tuple(d.get('mask',()))==mask and d['gap']==gap}))
            for (mask,gap),n in groups.most_common()]
    report=dict(sample=len(rows),measured=sum(groups.values()),sessions=index['sessions'],shapes=shapes,
                missing=dict(Counter(d['unmeasured'] for d in rows if 'unmeasured' in d)))
    floor=measure(base,base/'sumo-replays'/(exemplar+'.properties'),True)
    if shapes[0]['rows']!=tuple(floor['mask']) or shapes[0]['gap']!=floor['gap']:raise ValueError('Exemplar differs from most frequent layout')
    for name,data in [('bookmarks',index),('survey',report),('platform',floor)]:
        (out/(name+'.json')).write_text(json.dumps(data,indent=2)+'\n')
    lines=''.join(json.dumps(row,separators=(',',':'))+'\n' for row in rows)
    (out/'measurements.jsonl.gz').write_bytes(gzip.compress(lines.encode(),mtime=0))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('out',type=Path);p.add_argument('--base',type=Path,default=REMOTE)
    p.add_argument('--retry-errors',action='store_true');p.add_argument('--publish',type=Path)
    p.add_argument('--exemplar',default='fb302faee0f03c27bcaac437a64752fa')
    a=p.parse_args();survey(a.base,a.out,a.retry_errors)
    if a.publish:publish(a.base,a.out,a.publish,a.exemplar)
