"""Measure the portable historical field and replay its exact recorded Minecraft input heads."""
import argparse
from collections import Counter
import gzip
import json
from pathlib import Path
import subprocess
import tempfile
from adaptation import dump, freeze, sha
from audit import captures
from history_cup import CUP, FIELD, counts
from ladder_transfer import WT, HOLD, REFERENCE
from run import ROOT, HERE, calendars, compile_model, java
from tournament import grid

INDEX=FIELD/'legacy-census.json'
COVERAGE=FIELD/'legacy-model.json'
EXPANDED=FIELD/'legacy-model-expanded.json'

def inventory(out):
    registry=json.loads(INDEX.read_text())
    names=[WT,HOLD,*registry['order']]
    compile_model()
    with tempfile.NamedTemporaryFile(mode='w') as f:
        f.write('\n'.join(names)+'\n');f.flush()
        result=subprocess.run(java('HistoricalMovement','list',f.name),cwd=ROOT,text=True,capture_output=True,check=True)
    supported=result.stdout.splitlines()
    if len(supported)!=len(set(supported)) or set(supported)-set(names) or not {WT,HOLD}<=set(supported):
        raise ValueError('Invalid portable field')
    if out==COVERAGE and out.exists():
        earlier=json.loads(out.read_text())
        if earlier['index_sha256']!=sha(INDEX) or set(earlier['configs'])-set(supported):
            raise ValueError('The original portable roster lost support')
        if any(sha(ROOT/'policies'/(n+'.properties'))!=h for n,h in earlier['configs'].items()):
            raise ValueError('The original portable roster changed')
        return
    dump(out,dict(index_sha256=sha(INDEX),controls=[WT,HOLD],
                  legacy=[n for n in supported if n not in (WT,HOLD)],
                  unsupported=[n for n in names if n not in supported],
                  configs={n:sha(ROOT/'policies'/(n+'.properties')) for n in supported}))

def cohort(kind,work,expanded=False):
    index=EXPANDED if expanded else COVERAGE
    cover=json.loads(index.read_text());opponents=cover['legacy']
    if expanded:opponents=[n for n in opponents if n not in json.loads(COVERAGE.read_text())['legacy']]
    dest=CUP/('legacy-model-'+('expanded-' if expanded else '')+kind);dest.mkdir(parents=True,exist_ok=True)
    if kind=='development':
        arms={'wtap':None,REFERENCE:HERE/'robust-frozen/population.net','adapt-04-pair':HERE/'adaptive-frozen/challenger.net'}
        if expanded:
            d=json.loads((HERE/'continuation-frozen/selection.json').read_text());name=d['challenger']
            arms[name]=HERE/'continuation-frozen'/name.removesuffix('-fit')/'contender.net'
        donors=calendars('development_selection_clocks')
    else:
        winner=json.loads((CUP/'title.json').read_text())['recommended'];cfg=ROOT/'policies'/(winner+'.properties')
        p=dict(line.split('=',1) for line in cfg.read_text().splitlines() if '=' in line)
        if 'duel_network' in p:
            net=dest/'champion.net';freeze(net,(p['duel_network']+'\n').encode());arms={'wtap':None,winner:net}
        elif winner==HOLD:arms={'wtap':None,'hold':None}
        else:raise ValueError('Expected distinct playable title holder against W-tap: '+winner)
        donors=sorted((work/'history-final-clocks').glob('*.txt.gz'))
    if len(donors)!=8:raise ValueError('Expected eight full independent match calendars')
    for n,h in cover['configs'].items():
        if sha(ROOT/'policies'/(n+'.properties'))!=h:raise ValueError('Historical opponent changed: '+n)
    manifest=dict(field_sha256=sha(index),arms={n:sha(p) if p else n for n,p in arms.items()},donors={p.name:sha(p) for p in donors})
    dump(dest/'field.json',manifest)
    out=dest/'matches.jsonl.gz'
    if not out.exists():
        compile_model()
        with tempfile.TemporaryDirectory(dir=work) as temp:
            result=grid(Path(temp),'legacy',arms,{'legacy-'+n:None for n in opponents},donors,mode='mouse')
            freeze(out,result.read_bytes())
    with gzip.open(out,'rt') as f:rows=list(map(json.loads,f))
    expected=len(arms)*len(opponents)*32
    if len(rows)!=expected or any('unknown' in r for r in rows):raise ValueError('Incomplete historical model cells')
    for row in rows:row['opponent']=row['opponent'].removeprefix('legacy-')
    result=counts(rows,True);dump(dest/'results.json',result)
    print(kind,len(opponents),'opponents,',len(rows),'games,', {n:(r['mean'],r['tail'],r['worst']) for n,r in result.items()},flush=True)

def encode(e):
    forward,ctrl,side,click=e['keys'];x,y,z,vx,_,vz,ground,_=e['own']
    return ' '.join(map(str,[e['time'],e['epoch'],e['observed'],*e['cues'],e['last_click'],x,y,z,*e['target'],vx,vz,
                            int(ground),*e['speeds'],(2 if ctrl else 1) if forward else 0,side,int(click)]))+'\n'

def head_replay(manifest,out):
    index=EXPANDED if EXPANDED.is_file() else COVERAGE
    cover=json.loads(index.read_text());table=json.loads(manifest.read_text());keys=[]
    compile_model()
    if table['matches'].keys()!=table['plan'].keys():raise ValueError('Incomplete historical native slice')
    with tempfile.TemporaryFile(mode='w+') as stream:
        for req,mid in table['matches'].items():
            g=json.loads((ROOT/'runtime/results'/(mid+'.json')).read_text())
            if g['request']!=req:raise ValueError('Incorrect native match')
            for s,t in [('a','b'),('b','a')]:
                n=g['policy_'+s]
                if n not in cover['configs']:continue
                if sha(ROOT/'policies'/(n+'.properties'))!=cover['configs'][n]:raise ValueError('Policy changed: '+n)
                rtts=[g['initial_ping_'+k] for k in (s,t)]
                if any(r not in (0,80) for r in rtts):raise ValueError('Unexpected historical regulation latency')
                stream.write(f"@ {n} {int(rtts[0])} {int(rtts[1])}\n")
                log,=(ROOT/'runtime/pov'/mid).glob(s+'-client-*-events.jsonl.gz')
                last=-1;total=0;footer=None
                for e in captures.events(log):
                    if e['type']=='capture_end':footer=e
                    if e['type']!='legacy_head' or e['match']!=mid:continue
                    if e['tick']<=last:raise ValueError('Reordered historical head '+mid)
                    last=e['tick'];total+=1;stream.write(encode(e))
                if not total or not footer or not footer['writer_complete'] or any(footer[k] for k in ('size_capped','dropped','continues')):
                    raise ValueError('Missing captured input heads '+mid+'/'+s)
                keys.append((mid,s,n,total))
        stream.seek(0)
        proc=subprocess.run(java('HistoricalMovement','replay'),stdin=stream,cwd=ROOT,text=True,stdout=subprocess.PIPE,check=True)
    records=[];summed=Counter()
    for (mid,s,n,total),line in zip(keys,proc.stdout.splitlines(),strict=True):
        k,forward,lateral,click=map(int,line.split())
        if k!=total:raise ValueError('Lost historical replay heads')
        stats=dict(heads=k,movement_errors=forward,lateral_errors=lateral,click_errors=click)
        summed.update(stats);records.append(dict(mid=mid,side=s,policy=n,**stats))
    dump(out,dict(manifest_sha256=sha(manifest),coverage_sha256=sha(index),povs=records,total=dict(summed)))
    print('Replayed',len(records),'POVs',dict(summed),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);sub=p.add_subparsers(dest='cmd',required=True)
    sub.add_parser('inventory').add_argument('--expanded',action='store_true')
    for cmd in ('development','confirmation'):
        subp=sub.add_parser(cmd);subp.add_argument('work',type=Path);subp.add_argument('--expanded',action='store_true')
    r=sub.add_parser('replay');r.add_argument('manifest',type=Path);r.add_argument('out',type=Path)
    opt=p.parse_args()
    if opt.cmd=='inventory':inventory(EXPANDED if opt.expanded else COVERAGE)
    elif opt.cmd=='replay':head_replay(opt.manifest,opt.out)
    else:cohort(opt.cmd,opt.work,opt.expanded)
