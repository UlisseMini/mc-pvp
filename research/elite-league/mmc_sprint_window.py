"""Measure outgoing sprint resets after the bot sees its opponent take a fresh hit."""
import argparse
from collections import defaultdict
import gzip
import json
from pathlib import Path
from statistics import median
import subprocess
import tempfile

import focus
from adaptation import dump,sha
from mmc_click_bias import PARENT
from mmc_league import EVIDENCE,FROZEN,WORK,native_name
from mmc_recoil_clicks import native_bout,write_rows
from mmc_scorecard import record
from mmc_trade_fit import ACTOR,pool
from run import calendars,compile_model,java
from tournament import roster

DEST=EVIDENCE/'outgoing-sprint'
OPENING=EVIDENCE/'opening'
WORDS=('original','wtap','release-1','release-2','walk-1','release-then-sprint','sprint-2')


def register():
    DEST.mkdir(exist_ok=True)
    dump(DEST/'protocol.json',dict(
        motivation=sha(EVIDENCE/'nomophobia-development/server-knockback.json'),
        observation='In sixteen five-CPS development bouts the trade learner received the MMC server sprint bonus on 57 of 236 fresh hits. Nomophobia received it on 200 of 227. Separate this outgoing bonus from local contact braking. Does a brief W release improve the next fresh impulse or the eventual ring-out at the standard 20 CPS?',
        setting='Measured ring, 137 blocks, 8-block gold opening, three-block native reach, 20-CPS ceiling, physical mouse, both seats at 0 and 80 ms added RTT. Use only the original eight exposed development clocks.',
        landmark='First own input where the locally observed target fresh-hurt cue is between zero inclusive and 100 ms exclusive. Include games without a cue. Preserve the first complete pre-input clock and both primed policies.',
        changes='Unchanged, switch to primed W-tap, release W for one or two inputs, walk for one input, release then explicitly sprint forward for one input, or sprint forward for two inputs. Every short intervention preserves the underlying click gate, strafe and jump at that input. Resume the original bot on its actually applied key history.',
        measurements='Store both sides’ accepted attacks and ordered server sprint commands for the first 1.5 s, with the pre-attack server flag and actual horizontal velocity on each fresh hit. Reconstruct each C02 from the queued packets and verify the end sprint state of every drain. Also log actual own moving steps and local sprint brakes for 350 ms. At a shared wall-clock millisecond exclude zero-offset hits from next-hit summaries. Count censored bouts, all raw traces, W/D/L from the matched cut and spawn, with seat and RTT spread.',
        opening_native=sha(OPENING/'outcomes.json'),opening_model=sha(OPENING/'model.jsonl.gz'),
        hard_model=sha(FROZEN/'mmc13-trades/development.jsonl.gz'),
        caveat='Native opening uses all audited natural inputs. Native matches do not replay the counterfactual branches. The hard model includes ordinary W-tap, the passive actor and the trade learner’s original weakest distinct contender. No winner is awarded.'))


def metrics(bouts,mode):
    scores=[];bonus=[];speed=[];brakes=commands=0;at_risk=censored=0
    for r in bouts:
        if 'branches' not in r:continue
        b=r['branches'][mode];scores.append(b['score']);at_risk+=1
        censored+=int(b['end']-r['at']<1500)
        brakes+=sum(c[2] for c in b['contacts'])
        hits=[e for e in b['server_events'] if e[1] and e[2]=='fresh' and e[0]>0]
        commands+=sum(e[2]=='sprint' and e[1] and e[3] for e in b['server_events'])
        if hits:bonus.append(hits[0][3]);speed.append(hits[0][5])
    return dict(from_spawn=record([r['branches'][mode]['score'] if 'branches' in r else r['base'] for r in bouts]),
                from_shared_cut=record(scores),triggered=at_risk,next_fresh_observed=len(bonus),next_fresh_bonus=sum(bonus),
                no_next_fresh_in_observed_window=at_risk-len(bonus),censored_1500=censored,
                median_next_horizontal=median(speed) if speed else None,local_brakes_350=brakes,start_sprint_packets=commands)


def summary(rows,modes):
    grouped=defaultdict(list)
    for r in rows:
        for cut in ('all',f"{r['rtt']}/{r['side']}",r['clock'] if 'clock' in r else f"repeat-{r['rep']}"):
            grouped[r['arm'],r['opponent'],cut].append(r)
    output={}
    for k,rr in grouped.items():
        wt=grouped['research-wtap20',k[1],k[2]]
        if len(wt)!=len(rr):raise ValueError('Missing matched W-tap cohort')
        output['/'.join(k)]=dict(games=len(rr),plain_wtap=metrics(wt,'original'),
                                 alternatives={name:metrics(rr,name) for name in modes})
    return output


def model(panel):
    register();compile_model();field=pool()|focus.BASE
    if panel=='opening':
        spec=json.loads((OPENING/'schedule.json').read_text());arms={k:field[k] for k in spec['arms']}
        foes={k:field[k] for k in spec['opponents']};stock=OPENING/'model.jsonl.gz'
    else:
        arms={'wtap':None,PARENT:FROZEN/'mmc13-trades'/(PARENT+'.net')}
        foes={n:field[n] for n in ('wtap',ACTOR,'continuation-02-fit')};stock=FROZEN/'mmc13-trades/development.jsonl.gz'
    with tempfile.TemporaryDirectory(dir=WORK) as directory:
        d=Path(directory);roster(d/'arms',arms);roster(d/'foes',foes)
        with (d/'rows').open('w') as f:subprocess.run(java('RecoilClickStudy',d/'arms',d/'foes','--sprint',
            *calendars('development_selection_clocks')),stdout=f,check=True)
        rows=[json.loads(l) for l in (d/'rows').read_text().splitlines()]
    def key(r):return tuple(r[k] for k in ('arm','opponent','rtt','side','clock'))
    with gzip.open(stock,'rt') as f:old={key(r):r for r in map(json.loads,f) if r['arm'] in arms and r['opponent'] in foes}
    if len(rows)!=len(arms)*len(foes)*32 or len(old)!=len(rows) or set(old)!=set(map(key,rows)):
        raise ValueError('Different frozen panel')
    for r in rows:
        prior=old[key(r)]
        if [r['base'],r['end'],*r['fresh']]!=[prior[v] for v in ('score','end_ms','fresh','incoming')]:
            raise ValueError('New instrumentation changes original fight')
        r.update(arm=native_name(r['arm']),opponent=native_name(r['opponent']))
    write_rows(DEST/(panel+'-model.jsonl.gz'),rows);dump(DEST/(panel+'-model-summary.json'),summary(rows,WORDS))


def native():
    register()
    spec=json.loads((OPENING/'outcomes.json').read_text())
    with gzip.open(OPENING/'actual-movement.json.gz','rt') as f:movement=json.load(f)
    with gzip.open(OPENING/'exchanges.json.gz','rt') as f:exchanges={r['mid']:r for r in json.load(f)['bouts']}
    eligible={r['mid'] for r in movement['fixtures'].values() if r['valid']}
    if len(eligible)!=96 or {r['mid'] for r in spec['records']}!=set(exchanges) or set(exchanges)!=eligible:
        raise ValueError('Native fixtures differ from the validated opening')
    rows=[]
    for game in spec['records']:
        r=native_bout(game,sprint=True);prior=exchanges[r['mid']]
        if (r['arm'],r['opponent'],r['side'],r['rtt'])!=tuple(prior[k] for k in ('arm','opponent','side','rtt')):
            raise ValueError('Changed native fixture')
        r['base']=r['score']
        if 'at' in r:
            at=r['at']
            events=[[h['t']-at,h['own'],'upgrade' if h['upgrade'] else 'fresh',h.get('bonus'),h['tick'],h.get('horizontal')]
                for h in prior['sequence'] if at<=h['t']<=at+1500]
            r['branches']={'original':dict(score=r['score'],end=prior['duration_ms'],server_events=events,contacts=r['contacts'])}
        rows.append(r)
    if len(rows)!=96:raise ValueError('Incomplete native opening audit')
    write_rows(DEST/'opening-native.jsonl.gz',rows)
    result=summary(rows,('original',))
    # The archived native hit timeline contains fresh-impulse flags, but no sprint command stream.
    for cell in result.values():
        for b in [cell['plain_wtap'],*cell['alternatives'].values()]:b['start_sprint_packets']=None
    dump(DEST/'opening-native-summary.json',result)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('mode',choices=('opening','hard','native','register'))
    mode=p.parse_args().mode
    if mode=='native':native()
    elif mode=='register':register()
    else:model(mode)
