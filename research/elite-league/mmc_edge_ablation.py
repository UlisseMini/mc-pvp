"""Locate the fitted strafe regression with the two weight-identical movement experts in shadow."""
from collections import defaultdict
import gzip
import json
from pathlib import Path
import subprocess
import tempfile

from adaptation import dump, freeze, sha
from mmc_edge_fit import BASE, CONTACT, SPLIT
from mmc_league import EVIDENCE, WORK
from mmc_scorecard import record
from mmc_trade_fit import ACTOR, PREVIOUS, pool
from run import calendars, compile_model, java
from tournament import roster

DEST=EVIDENCE/'edge-ablation'


def key(r):return tuple(r[k] for k in ('opponent','clock','rtt','side'))


def run():
    DEST.mkdir(exist_ok=True)
    selection=json.loads((SPLIT/'selection.json').read_text());fit=selection['contender']
    arms={BASE:CONTACT/(BASE+'.net'),fit:SPLIT/(fit+'.net')}
    foes={n:pool()[n] for n in ('wtap',PREVIOUS,ACTOR,'continuation-02-fit')}
    clocks=calendars('development_selection_clocks')
    dump(DEST/'protocol.json',dict(fit=sha(arms[fit]),parent=sha(arms[BASE]),development=[p.name for p in clocks],
        foes=list(foes),outcome='Ring-outs or half a point on draws. Compare plain W-tap and both original policies from the identical complete spawn. '
        'Mix the fitted and original strafe output only on the first six inputs from the original 3.5-block trigger, or only outside them. For each branch the landmark comes from its own new trajectory. '
        'Both networks and W-tap see every chosen physical action. Compare the literal six-input centerward rule and report disagreements before, during and after its window on the unchanged parent history. '
        'This diagnosis uses only the exposed development clocks and does not select a new tournament winner. Native comparison of the unmodified parent, full fit and W-tap remains in edge-confirm.'))
    compile_model()
    with tempfile.TemporaryDirectory(dir=WORK) as t:
        t=Path(t);roster(t/'arms',arms);roster(t/'foes',foes)
        with (t/'rows').open('w') as f:subprocess.run(java('EdgeFitAblation',t/'arms',t/'foes',*clocks),stdout=f,check=True)
        rows=list(map(json.loads,(t/'rows').read_text().splitlines()))
        with gzip.open(SPLIT/'development.jsonl.gz','rt') as f:
            old={(r['arm'],key(r)):r for r in map(json.loads,f) if r['arm'] in ('wtap',BASE,fit) and r['opponent'] in foes}
        with gzip.open(EVIDENCE/'edge-recovery/radius-3.5.jsonl.gz','rt') as f:
            edge={key(r):r for r in map(json.loads,f) if r['arm']==BASE}
        if len(rows)!=128 or len({key(r) for r in rows})!=128 or len(old)!=384 or len(edge)!=128:raise ValueError('Unbalanced fixtures')
        grouped=defaultdict(list)
        for r in rows:
            k=key(r)
            for strategy,name in [('fit',fit),('parent',BASE),('wtap','wtap')]:
                m=r['branches'][strategy];ref=old[name,k]
                if [m['score'],m['end'],*m['fresh']]!=[ref[n] for n in ('score','end_ms','fresh','incoming')]:
                    raise ValueError('Different native-bindable controller playback')
            e=edge[k];v=e['branches']['center-strafe-6'][:2] if e.get('branches') else [e['base'],e['end']]
            b=r['branches']['center-in-window']
            if [b['score'],b['end']]!=v:raise ValueError('Legal center rule differs from full-state fork')
            for cut in ('all',f"{r['rtt']}/{r['side']}",r['clock']):grouped[r['opponent'],cut].append(r)
        freeze(DEST/'comparisons.jsonl.gz',gzip.compress((t/'rows').read_bytes(),mtime=0))
    summary={}
    for pair,rs in grouped.items():
        modes=list(rs[0]['branches']);parent=[r['branches']['parent'] for r in rs]
        by_kind={m:record([r['branches'][m]['score'] for r in rs]) for m in modes}
        first=[r['first_difference'] for r in parent if r['first_difference']]
        summary['/'.join(pair)]=dict(outcomes=by_kind,first_strafe_differences_by_region=[sum(f[2]==i for f in first) for i in range(3)],
            no_disagreement=len(rs)-len(first),all_strafe_differences=[sum(p['different_strafe'][i] for p in parent) for i in range(3)],
            window_total_nonzero_parent_center_fit_center=[sum(p['window'][i] for p in parent) for i in range(4)])
    dump(DEST/'summary.json',dict(comparisons=sha(DEST/'comparisons.jsonl.gz'),cells=summary))


if __name__=='__main__':run()
