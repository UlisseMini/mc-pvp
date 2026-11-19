"""Check the frozen measured-map contenders across every archived policy and the portable model field."""
import argparse
from collections import defaultdict
import gzip
import json
import random
import sys
from pathlib import Path
from adaptation import dump,freeze,sha
import champions
import focus
from history_cup import FIELD,counts
from legacy_census import schedule,signature,summarize
from ladder_transfer import native_install
from mmc_league import EVIDENCE,FROZEN,WORK,WT,finalists,groups,native_name,read_results,record_setup,regulation_batch,setup
from run import ROOT,calendars
from reigning import publish

def roster():
    n,new,entries,hard=finalists();name=native_name(n)
    if name==WT:name=native_name(new)
    arms={WT:None,name:entries.get(n) if n!='wtap' else entries[new]}
    if len(arms)!=2:raise ValueError('Expected a separate W-tap comparison')
    modern=groups()['modern']
    native_install({native_name(n):p for n,p in modern.items() if p})
    archived=json.loads((FIELD/'legacy-census.json').read_text())
    # Preserve every historical alias; newer identical references share one opponent cell.
    field=dict(archived['field']);keys={signature(ROOT/'policies'/(k+'.properties')):k for k in field}
    for k in [native_name(v) for v in modern]+list(arms):
        f=ROOT/'policies'/(k+'.properties');key=signature(f)
        if key in keys:
            rep=keys[key];field[rep]['aliases']=sorted(set(field[rep]['aliases'])|{k});continue
        keys[key]=k;field[k]=dict(aliases=[k],sha256=sha(f),regulation_family='active fighter',kind='published opponent')
    order=sorted(field);random.Random(771994).shuffle(order)
    # Include the two key controls and the repair target in the earliest batch.
    first=[keys[signature(ROOT/'policies'/(k+'.properties'))] for k in [WT,native_name('hold'),*(native_name(q) for q in hard),name]]
    sequence=list(dict.fromkeys(first+order));out=EVIDENCE/'archive';out.mkdir(exist_ok=True,parents=True)
    dump(out/'roster.json',dict(arms=list(arms),source_aliases=archived['names'],distinct_prior=archived['distinct'],
        order=sequence,opponents=field,design='Each opponent and arm play the same two opposite seat/RTT cells in pass 1 and the complementary two in pass 2.'))
    return name,arms,field,sequence,out

def paired(passno,tag,arms,field,order,i):
    full=schedule(tag,list(arms),{n:field[n] for n in order});rows={}
    for q,t in full.items():
        offset=order.index(t['opponent'])+i
        if (t['side']=='b') != bool((offset+(t['rtt']==80)+passno)%2):continue
        rows[q]=t
    return record_setup(rows)

def model():
    n,new,arms,hard=finalists();allgroups=groups()
    d=EVIDENCE/'archive';d.mkdir(exist_ok=True,parents=True)
    opponents={k:v for g in allgroups.values() for k,v in g.items()}
    focus.play_model(d/'model-development.jsonl.gz',arms,opponents,calendars('development_selection_clocks'))

def archive():
    name,arms,field,order,d=roster();allrows=[]
    for stage in (0,1):
        for i in range(0,len(order),8):
            current=order[i:i+8];tag=f'mmc13-slice-{stage+1}-{i//8+1:02d}';dest=d/(tag+'.json.gz')
            trials=paired(stage,tag,arms,field,current,i)
            if not dest.exists():
                manifest=regulation_batch(trials,WORK/(tag+'.json'),d)
                rows=read_results(manifest)
                # Full POV validation of both sides (including historical configuration/dependencies).
                observed=summarize(json.loads(manifest.read_text())['plan'],manifest,field)
                if [r['mid'] for r in rows]!=[r['mid'] for r in observed]:raise ValueError('Different verifier IDs')
                freeze(dest,gzip.compress(json.dumps(observed,separators=(',',':')).encode(),mtime=0))
            with gzip.open(dest,'rt') as f:allrows+=json.load(f)
        scored=counts(allrows)
        eligible={o for o,r in field.items() if r['regulation_family']=='active fighter'}
        active=counts([r for r in allrows if r['opponent'] in eligible])
        winner=min(active,key=lambda a:(-active[a]['merit'],-active[a]['mean'],a!=WT,a))
        tag=f'mmc13-archive-{stage+1}'
        champions.install(tag,ROOT/'policies'/(winner+'.properties'),f'Full recorded-map archive tournament {stage+1} winner ({winner})',tag)
        dump(d/f'pass-{stage+1}.json',dict(winner=winner,complete=len(allrows),all_opponents=scored,active_opponents=active))
        print(tag,winner,len(allrows),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('stage',choices=('model','archive'));a=p.parse_args();setup()
    (model if a.stage=='model' else archive)()
