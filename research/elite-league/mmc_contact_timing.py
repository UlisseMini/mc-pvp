"""Verify same-state alternatives at the first extra native-style click chosen by the fitted gate."""
import argparse
from collections import defaultdict
import gzip
import json
from pathlib import Path
import subprocess
import tempfile

from adaptation import dump, freeze, sha
from mmc_contact_gate import SPLIT
from mmc_league import EVIDENCE, WORK
from mmc_scorecard import record
from mmc_trade_fit import ACTOR, pool
from run import calendars, compile_model, java
from tournament import roster


def run(footwork=False):
    selection=json.loads((SPLIT/'selection.json').read_text())
    arm=selection['contender']
    field=pool()
    foes={n:field[n] for n in dict.fromkeys(['wtap',ACTOR,selection['weakest_distinct']])}
    clocks=calendars('development_selection_clocks')
    dest=EVIDENCE/('contact-footwork' if footwork else 'contact-timing')
    dest.mkdir(exist_ok=True)
    if footwork:
        original=EVIDENCE/'contact-timing/forks.jsonl.gz'
        dump(dest/'protocol.json',dict(source=sha(original),model_clocks=[p.name for p in clocks],foes=list(foes),
            motivation='The fitted gate first clicked at a horizontal center distance of 0.124 to 0.918 blocks in all 32 passive-PPO bouts, with W and sprint held on every first extra press. The next hit was always ours but 15 fights were lost and four drew. Test whether forward pressure during this close approach gives the opponent an immediate counter.',
            controls='At the same first extra click, keep the actual policy click, strafe and jump, but use coast, W, W+Ctrl or S for 1, 3 or 6 input heads. Include the full unchanged policy and the already-primed plain W-tap continuation. Replay every original ending and early hit timeline. The opponent keeps reacting in each branch. Preserve untriggered games, with matched plain W-tap from spawn as well.',
            measurements='For the ensuing 350 ms record all own contacts, their local impulses and movement. For 1.5 s keep both accepted-hit timelines and upgrades. Grade regulation ring-outs, both seats and RTT, and separately count how often the next fresh hit is ours and a second own fresh precedes any accepted return. This is development counterfactual evidence.'))
    else:dump(dest/'protocol.json',dict(selection=sha(SPLIT/'selection.json'),foes=list(foes),
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
        with (d/'rows').open('w') as stream:subprocess.run(java('ContactTimingStudy',d/'arms',d/'foes',*(['--footwork'] if footwork else []),*clocks),stdout=stream,check=True)
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
            if r.get('branches') and any(r['branches']['unchanged'][k]!=v for k,v in r['original'].items()):
                raise ValueError('No-op branch changed future hit trace')
        if footwork:
            with gzip.open(original,'rt') as f:stock={key(r):r for r in map(json.loads,f)}
            if set(stock)!={key(r) for r in rows}:raise ValueError('Different original state population')
            for r in rows:
                source=stock[key(r)]
                for k in ('at','local_view','prior_accepted','original','plain_wtap','first_extra_head'):
                    if r.get(k)!=source.get(k):raise ValueError('Different original timing or actions at '+k)
            groups=defaultdict(list)
            for r in rows:
                for cut in ('all',f"{r['rtt']}/{r['side']}",r['clock']):groups[r['opponent'],cut].append(r)
            result={}
            names=['unchanged','wtap']+[f'{key}-{n}' for key in ('coast','walk','sprint','back') for n in (1,3,6)]
            for (foe,cut),subset in groups.items():
                branches=[r for r in subset if 'branches' in r]
                arms={}
                for name in names:
                    values=[r['branches'][name] for r in branches];own=double=0
                    for v in values:
                        hits=v['next_1500ms'];fresh=next((h for h in hits if not h[2]),None)
                        if fresh is None or not fresh[1]:continue
                        own+=1
                        # Same-drain accepted returns also end the streak.
                        replies=[h[0] for h in hits if not h[1] and h[0]>=fresh[0]]
                        double+=any(h[1] and not h[2] and fresh[0]<h[0]<min(replies,default=float('inf')) for h in hits)
                    arms[name]=dict(from_trigger=record([v['score'] for v in values]),from_spawn=record([
                        r['branches'][name]['score'] if 'branches' in r else r['original']['score'] for r in subset]),
                        next_fresh_ours=own,second_fresh_before_return=double)
                result[f'{foe}/{cut}']=dict(bouts=len(subset),triggered=len(branches),plain_wtap_spawn=record([r['plain_wtap'] for r in subset]),arms=arms)
            dump(dest/'summary.json',result)
        freeze(dest_file,gzip.compress((d/'rows').read_bytes(),mtime=0))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--footwork',action='store_true')
    run(parser.parse_args().footwork)
