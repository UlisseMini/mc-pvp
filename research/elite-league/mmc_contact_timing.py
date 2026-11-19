"""Verify same-state alternatives at the first extra native-style click chosen by the fitted gate."""
import gzip
import json
from pathlib import Path
import subprocess
import tempfile

from adaptation import dump, freeze, sha
from mmc_contact_gate import SPLIT
from mmc_league import EVIDENCE, WORK
from mmc_trade_fit import ACTOR, pool
from run import calendars, compile_model, java
from tournament import roster


def run():
    selection=json.loads((SPLIT/'selection.json').read_text())
    arm=selection['contender']
    field=pool()
    foes={n:field[n] for n in dict.fromkeys(['wtap',ACTOR,selection['weakest_distinct']])}
    clocks=calendars('development_selection_clocks')
    dest=EVIDENCE/'contact-timing'
    dest.mkdir(exist_ok=True)
    dump(dest/'protocol.json',dict(selection=sha(SPLIT/'selection.json'),foes=list(foes),
        development_clocks=[p.name for p in clocks],focus='First decision at which the learned selector asks for an extra physical click gate. Pause immediately before that exact own head. Replay the same complete clock, queues, controls, and the two primed brains.',
        arms='Original selector, primed plain W-tap for the rest of the bout, suppress click for 1/3/6 heads, force click for 3/6, wait 1 or 3 then force 6. Outside those heads the selector resumes on its own actual inputs; during them all other controls still follow the selector except in the W-tap comparison.',
        local_view='Nearest distance, horizontal center gap, scaled own and target closing, center projection, local own and target hurt features, sprint, forward key, strafe, jump.',
        grading='Native confirmation is a separate registered whole-bout comparison. These model forks use exposed development calendars; future-informed best continuation is only a diagnostic.'))
    dest_file=dest/'forks.jsonl.gz'
    if dest_file.exists():return
    compile_model()
    with tempfile.TemporaryDirectory(dir=WORK) as d:
        d=Path(d)
        roster(d/'arms',{arm:SPLIT/(arm+'.net'),ACTOR:field[ACTOR]});roster(d/'foes',foes)
        with (d/'rows').open('w') as stream:subprocess.run(java('ContactTimingStudy',d/'arms',d/'foes',*clocks),stdout=stream,check=True)
        rows=list(map(json.loads,(d/'rows').read_text().splitlines()))
        def key(row):return tuple(row[k] for k in ('opponent','clock','rtt','side'))
        with gzip.open(SPLIT/'development.jsonl.gz','rt') as f:
            previous={(r['arm'],key(r)):r for r in map(json.loads,f) if r['arm'] in (arm,'wtap') and r['opponent'] in foes}
        if len(previous)!=2*len(rows) or len({key(r) for r in rows})!=len(foes)*len(clocks)*4:
            raise ValueError('Different development schedule')
        for r in rows:
            old=previous[arm,key(r)]
            if [old[k] for k in ('score','end_ms','fresh','incoming')]!=[r['original'][k] for k in ('score','end','fresh','incoming')]:
                raise ValueError('Original selector fight changed')
            if previous['wtap',key(r)]['score']!=r['plain_wtap']:raise ValueError('Plain W-tap changed')
            if r.get('branches') and r['branches']['unchanged']!=r['original']:
                raise ValueError('No-op branch changed future hit trace')
        freeze(dest_file,gzip.compress((d/'rows').read_bytes(),mtime=0))


if __name__=='__main__':
    run()
