"""Measure the frozen native finalist in every archived matchup, sharing the original W-tap fixtures."""
import argparse
import gzip
import json
from pathlib import Path
import time
import champions
import focus
from adaptation import dump, freeze, sha
from history_cup import counts, mirrors
from legacy_census import summarize
from mmc_breadth import paired
from mmc_final import common_field
from mmc_league import EVIDENCE, FROZEN, WORK, WT, groups, native_name, read_results, regulation_batch, setup
from run import ROOT


def model():
    arms, opponents=common_field()
    known={k:p for group in groups().values() for k,p in group.items()}
    known.update(opponents)
    # These archive outcomes do not select entrants. The common cup has already frozen all weights.
    clocks=mirrors('mmc13-final',WORK)
    d=EVIDENCE/'archive-final'
    d.mkdir(exist_ok=True)
    freeze(d/'model-entrants.json',(FROZEN/'final-entrants.json').read_bytes())
    out=d/'model.jsonl.gz'
    focus.play_model(out,arms,known,clocks)
    with gzip.open(out,'rt') as stream:rows=[json.loads(line) for line in stream]
    dump(d/'model-summary.json',counts(rows,True))


def native():
    title=EVIDENCE/'final/title.json'
    winner=json.loads(title.read_text())['winner']
    roster=EVIDENCE/'archive/roster.json'
    r=json.loads(roster.read_text())
    d=EVIDENCE/'archive-final'
    d.mkdir(exist_ok=True)
    dump(d/'native-entrant.json',dict(winner=winner,source_sha256=sha(title),roster_sha256=sha(roster),
                                     control='Reuse the identical seat/RTT opponent cells for the plain W-tap arm of each recorded-map archive pass.'))
    field=r['opponents'];opponents=r['order']
    rows=[]
    for stage in (0,1):
        for i in range(0,len(opponents),8):
            current=opponents[i:i+8]
            base=f'mmc13-slice-{stage+1}-{i//8+1:02d}'
            completed=EVIDENCE/'archive'/(base+'.json.gz')
            while not completed.exists():time.sleep(10)
            with gzip.open(completed,'rt') as stream:reference=json.load(stream)
            chosen=[a for a in reference if a['arm']==WT]
            if winner not in r['arms']:
                name=f'mmc13-archive-final-{stage+1}-{i//8+1:02d}'
                out=d/(name+'.json.gz')
                if not out.exists():
                    trials=paired(stage,name,[winner],field,current,i)
                    manifest=regulation_batch(trials,WORK/(name+'.json'),d)
                    read_results(manifest)
                    actual=summarize(json.loads(manifest.read_text())['plan'],manifest,field)
                    freeze(out,gzip.compress(json.dumps(actual,separators=(',',':')).encode(),mtime=0))
                with gzip.open(out,'rt') as stream:chosen+=json.load(stream)
            elif winner!=WT:chosen += [a for a in reference if a['arm']==winner]
            def settings(a):return (a['opponent'],a['side'],a['rtt'])
            if {settings(a) for a in chosen if a['arm']==WT}!={settings(a) for a in chosen if a['arm']==winner}:
                raise ValueError('Different native cells for the finalist and W-tap')
            rows+=chosen
        active=counts([a for a in rows if field[a['opponent']]['regulation_family']=='active fighter'])
        ranked=min(active,key=lambda n:(-active[n]['merit'],-active[n]['mean'],n!=WT,n))
        alias=f'mmc13-final-archive-{stage+1}'
        champions.install(alias,ROOT/'policies'/(ranked+'.properties'),'Recorded-map finalist archive tournament winner ('+ranked+')',alias)
        dump(d/f'pass-{stage+1}.json',dict(winner=ranked,complete=len(rows),all_opponents=counts(rows),active_opponents=active))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('stage',choices=('model','native'))
    a=p.parse_args()
    setup()
    (model if a.stage=='model' else native)()
