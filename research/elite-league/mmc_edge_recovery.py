"""Compare outward-edge recovery at two own-observation landmarks on exposed clocks."""
import gzip
import json
from pathlib import Path
import subprocess
import tempfile

from adaptation import dump, freeze, sha
from mmc_click_bias import PARENT
from mmc_contact_gate import SPLIT
from mmc_league import EVIDENCE, FROZEN, WORK
from mmc_trade_fit import ACTOR, PREVIOUS, pool
from run import calendars, compile_model, java
from tournament import roster


def run():
    selection=json.loads((SPLIT/'selection.json').read_text())
    candidate=selection['contender']
    field=pool()
    arms={'wtap':None,PARENT:FROZEN/'mmc13-trades'/(PARENT+'.net'),candidate:SPLIT/(candidate+'.net')}
    foes={n:field[n] for n in ('wtap',ACTOR,PREVIOUS,'continuation-02-fit')}
    clocks=calendars('development_selection_clocks')
    dest=EVIDENCE/'edge-recovery';dest.mkdir(exist_ok=True)
    dump(dest/'protocol.json',dict(evidence=sha(EVIDENCE/'contact-timing/forks.jsonl.gz'),selection=sha(SPLIT/'selection.json'),
         radius_blocks=[3.5,5.0],calendars=[p.name for p in clocks],arms=list(arms),opponents=list(foes),
         state='First input after either local hurt cue with own radius at least the threshold and outward own velocity at least 0.02 block per tick. Own center comes from the observed spawn midpoint. Fork before this input. Compare absolute bouts separately from continuations sharing exactly the same primed start.',
         actions='Unchanged, primed plain W-tap, three heads of centerward/opposite/zero strafe, six of centerward strafe, three heads of centerward/opposite eight-way movement, or a single grounded jump. Except in the W-tap continuation all keep the controller’s actual click gate each tick.',
         local_values='Radius, velocity projected outward, centerward projection along forward and left, nearest observed distance, own Y and vertical velocity, grounded flag, forward/sprint key, lateral key, click gate, jump key.',
         result='Ring-out score, end time, server radius at first server end >=500ms or fall, actual elapsed time, accepted hits in first 500 ms. Preserve every ineligible match and both terminal hashes.'))
    compile_model()
    for rad in (3.5,5.0):
        target=dest/('radius-'+str(rad)+'.jsonl.gz')
        if target.exists():continue
        with tempfile.TemporaryDirectory(dir=WORK) as tmp:
            tmp=Path(tmp);roster(tmp/'arms',arms);roster(tmp/'foes',foes)
            with (tmp/'rows').open('w') as f:subprocess.run(java('EdgeRecoveryStudy',tmp/'arms',tmp/'foes',rad,*clocks),stdout=f,check=True)
            rows=list(map(json.loads,(tmp/'rows').read_text().splitlines()))
            def key(r):return tuple(r[k] for k in ('arm','opponent','clock','rtt','side'))
            with gzip.open(SPLIT/'development.jsonl.gz','rt') as f:
                old={key(r):r for r in map(json.loads,f) if r['arm'] in arms and r['opponent'] in foes}
            if len(rows)!=384 or len({key(r) for r in rows})!=384 or len(old)!=384:raise ValueError('Missing regulation fixtures')
            for r in rows:
                archive=old[key(r)]
                fresh=r['fresh'] if r['side']=='a' else r['fresh'][::-1]
                if [r['base'],r['end'],*fresh] != [archive[k] for k in ('score','end_ms','fresh','incoming')]:
                    raise ValueError('Instrumented original differs from the regulation schedule')
            freeze(target,gzip.compress((tmp/'rows').read_bytes(),mtime=0))


if __name__=='__main__':
    run()
