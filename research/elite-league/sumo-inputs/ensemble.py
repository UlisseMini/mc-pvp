"""Select a four-member vote on development, then evaluate the frozen compiled networks."""
import csv
import gzip
import shutil
import statistics
import subprocess
import sys
from collections import defaultdict
from live import D, ROOT, B, WT, ping, wait, read, sha, freeze, champions, fixtures, run_rows
from report import grouped, native, rank, publish, pct, wld, score
from train import java

F = D / 'ensemble'
METHODS = ['pooled','average','member']
OPPONENTS = [WT, ping.OLD, 'shift-check-v4-4']


def entrant_files():
    entries = {'wtap': None, ping.OLD:D.parent/'recorded-mmc-frozen/range-model'/(ping.OLD+'.net')}
    for method in METHODS:
        path = F/(method+'.net')
        entries['sumo-vote-'+method] = path
        champions.install('sumo-vote-'+method,path,'MMC four-member comparison '+method,'input-vote-'+method,'direct')
    text=''.join(n+('\t'+str(p.relative_to(ROOT)) if p else '')+'\n' for n,p in entries.items())
    for dest in ['entrants.tsv','archive-contenders.tsv']:(F/dest).write_text(text)
    freeze(F/'selection.json',dict(members=(F/'members.txt').read_text().splitlines(),
        best_member=(F/'best-member.txt').read_text().strip(),weights={n:sha(p) for n,p in entries.items() if p}))


def results():
    model = grouped(csv.DictReader((F/'transfer.csv').open()))
    with gzip.open(F/'archive.csv.gz','rt') as file: archive = grouped(csv.DictReader(file))
    rows = native('ensemble')
    winner = rank(rows)[0]
    publish('sumo-vote-final',winner,'input-pooled-final')
    sel = read(F/'selection.json')
    out=['# Do independently trained policies help one another?', '',
         'The original development score selected four networks from ten eligible candidates, with all 210 distinct groups tested. The pooling comparison averages their five action scores before deciding on movement, jump and attack. The parameter average uses the identical members with mean weights. Both use local observations and the same aim, reach and click controls as the incumbent.', '',
         'The chosen members were '+', '.join(sel['members'])+'. The strongest individual on development was '+sel['best_member']+'. All three were frozen before scoring the four new timing schedules.', '',
         '| Entrant | Ten-opponent model | Model vs W-tap | 257-opponent model | Archive weakest (W–L–D) | Native vs W-tap | Native vs incumbent | Native vs shift-check | W-tap model field / native overall |',
         '|---|---:|---:|---:|---|---:|---:|---:|---:|']
    summary={}
    for arm in rank(rows):
        id='wtap' if arm==WT else arm
        v=rows[arm]; m=model[id]; historical=archive[id]
        by=defaultdict(list)
        for k,y in historical.items():by[k[0]].append(y)
        weakest=min(by,key=lambda f:(score(by[f]),f))
        native_foes=[wld([y for k,y in v.items() if k[0]==f]) for f in OPPONENTS]
        fields=[arm,pct(score(list(m.values()))),wld([y for k,y in m.items() if k[0]=='wtap']),
                pct(score(list(historical.values()))),weakest+' ('+wld(by[weakest])+')',*native_foes,
                pct(score(list(model['wtap'].values())))+' / '+wld(list(rows[WT].values()))]
        out.append('| '+' | '.join(fields)+' |')
        summary[arm]=dict(model=score(list(m.values())),archive=score(list(historical.values())),native=native_foes,
            weak=weakest,weak_record=wld(by[weakest]))
    out+=['','| Entrant | Native A / B | Native 31/109 / 109/31 | Plain W-tap overall |','|---|---:|---:|---:|']
    for n in rank(rows):
        v=rows[n]
        def cell(i,x):return wld([y for k,y in v.items() if k[i]==x])
        out.append('| '+' | '.join([n,cell(1,'a')+' / '+cell(1,'b'),cell(2,31)+' / '+cell(2,109),wld(list(rows[WT].values()))])+' |')
    out+=['',f'The actual native bracket winner was {winner}. Its permanent playable alias is sumo-vote-final. Each matchup covered four independent native bouts. A strong score in the archive describes 257 historical bot opponents.', '']
    (F/'results.md').write_text('\n'.join(out))
    freeze(F/'summary.json',dict(winner=winner,results=summary))


def main():
    F.mkdir(exist_ok=True)
    if not (F/'development.csv').exists():subprocess.run(java('InputEnsemble'),cwd=ROOT,check=True)
    entrant_files()
    keys=[f'sinput-m-sumo-input-fixed-notiming-0-{i}-{side}' for i in range(2) for side in 'ab']
    docket=D/'masks.json'
    wait(lambda:docket.exists() and set(keys)<=read(docket)['matches'].keys())
    data=read(docket)
    manifest=F/'donors.json'
    freeze(manifest,dict(plan={q:data['plan'][q] for q in keys},matches={q:data['matches'][q] for q in keys}))
    subprocess.run([sys.executable,ROOT/'research/sumo-rl/calendars.py',B/'ensemble-clocks','--frozen-manifest',manifest],check=True)
    clocks=[]
    for p in sorted((B/'ensemble-clocks').glob('m*.txt.gz')):
        shutil.copyfile(p,F/p.name);clocks.append(str((F/p.name).relative_to(ROOT)))
    assert len(clocks)==4
    (F/'clocks.txt').write_text('\n'.join(clocks)+'\n')
    if not (F/'transfer.csv').exists():subprocess.run(java('InputProbe',F),cwd=ROOT,check=True)
    for line in (D/'archive-field.tsv').read_text().splitlines():
        cols=line.split('\t')
        if len(cols)>1 and sha(ROOT/cols[1])!=cols[2]:raise ValueError('Changed archive entry '+cols[0])
    if not (F/'archive.csv.gz').exists():subprocess.run(java('InputArchive',F),cwd=ROOT,check=True)
    wait(lambda:(D/'axes-audit.json').exists())
    players=['sumo-vote-'+m for m in METHODS]+[ping.OLD,WT]
    run_rows('ensemble',fixtures('p',players,OPPONENTS))
    subprocess.run([sys.executable,D.parent/'cpu_limit.py','--audit',sys.executable,'-c',
                    "import sys;sys.path.insert(0,sys.argv[1]);from audit_run import certify;certify('ensemble')",str(D)],check=True)
    results()

if __name__=='__main__':main()
