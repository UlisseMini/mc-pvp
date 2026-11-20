"""Separate the change in the two starting positions from the change in the recorded arena."""
import gzip
import json
import os
from pathlib import Path
from statistics import mean, median, stdev
import struct
import subprocess
import sys
import tempfile

from build_maps import BUILD, HERE, ROOT, compile_java
from platforms import OUT, record
from validation import CLOCKS, make_panel, read, write_json, student
from learner import atomic

DEST = OUT/'opening-contrast'
MAPS = {'mmc-137': 8, 'mmc-221': 10}
STAGES = {'16k': 'baseline16k', '32k': 'checkpoint32k'}
OPPONENT = 'sumo-seven-first'


def recorded(stage, arena, arm):
    directory = OUT/'tournaments'/stage/'model'/arena
    for source in sorted(directory.glob('p*.json')):
        entry = read(source)
        if entry['actor'] == arm and entry['foe'] == OPPONENT:
            return entry['games']
    raise ValueError(f'Missing recorded comparison for {stage}, {arena}, {arm}')


def summarize(games):
    wins, draws, losses = (sum(row[0] == outcome for row in games) for outcome in (1, .5, 0))
    timed_out = [row for row in games if row[0] == .5 and row[1] == 120000]
    return dict(wins=wins, draws=draws, losses=losses, timeouts=len(timed_out),
                timeouts_with_no_fresh_hit=sum(row[2] == 0 for row in timed_out),
                median_fresh_hits_in_timeout=median(row[2] for row in timed_out) if timed_out else None,
                median_last_fresh_ms_in_timeout=median(times) if (times:=[r[4] for r in timed_out if r[2]]) else None)


def paired_effect(arm, cells, change_floor):
    def wins(arena,gap):
        games=json.loads(gzip.decompress((DEST/f'{arena}/{gap}/{arm}.json.gz').read_bytes()))
        return [int(g[0]==1) for g in games]
    small8,small10,large8,large10=(wins(arena,gap) for arena in MAPS for gap in (8,10))
    # Marginal differences average over the other factor within the exact same fixture.
    delta=[((c+d-a-b) if change_floor else (b+d-a-c))/2 for a,b,c,d in zip(small8,small10,large8,large10,strict=True)]
    donors=[mean(v for v,r in zip(delta,cells,strict=True) if r[0]==i) for i in sorted({r[0] for r in cells})]
    average=mean(donors)
    half=float(student.ppf(.975,len(donors)-1))*stdev(donors)/len(donors)**.5
    return dict(mean=average,individual_donors=donors,approximate_95_percent_interval=[average-half,average+half])


def main():
    protocol = read(OUT/'tournaments/checkpoint32k/protocol.json')
    refs = {label: read(OUT/'tournaments'/stage/'protocol.json')['contestants']['seven'] for label,stage in STAGES.items()}
    assert OPPONENT == protocol['contestants']['seven']['target']
    write_json(DEST/'protocol.json',dict(training_floor='Only the default 137-block floor is used for training',
               maps=MAPS, opening_distances=[8,10], target=OPPONENT, checkpoints=refs, physical_cap_both=7,
               test='Cross the two recorded floor volumes with an eight- or ten-block distance on the recorded approach axis. Move both players symmetrically about the midpoint. Preserve terrain, yaw, seeds and clocks.',
               anchors='Reproduce every original recorded-map outcome and terminal timestamp for both checkpoints and W-tap. Synthetic openings are simulator interventions.',
               fixtures='128 previously exposed paired fixtures per cell, from eight donors with mirrored seats and four RTT pairs. Diagnostic only. No sealed geometries.',
               metrics='Outright wins, draws and losses, seat, RTT, draw timeouts, accepted fresh hits and last fresh-hit time in timeouts.'),fixed=True)
    runtime = Path(read(BUILD/'model-latest.json')['directory'])
    parity = OUT/'tournaments/baseline16k'/('parity-'+runtime.name+'.json')
    if not read(parity)['outcome_and_terminal_time_identical']:
        raise ValueError('The collision runtime has no matching recorded-map outcomes')
    cells = make_panel(CLOCKS['dev'],protocol['model_repetitions'])
    results = {}
    arms = {**{label: ref['actor'] for label,ref in refs.items()}, 'wtap':'wtap'}
    with tempfile.TemporaryDirectory(dir=BUILD) as tmp:
        path = Path(tmp)
        compile_java(runtime,path,[HERE/'OpeningContrast.java'])
        plan = path/'fixtures.tsv'
        plan.write_text(''.join('\t'.join(map(str,[clock,s,a,b,x,y]))+'\n' for _,_,s,a,b,clock,x,y in cells))
        for arena,original_gap in MAPS.items():
            for gap in (8,10):
                for label,actor in arms.items():
                    output = subprocess.check_output(['java','-XX:ActiveProcessorCount=1','-XX:+UseSerialGC','-Xmx650m',
                        '-cp',os.pathsep.join(map(str,[path,runtime])),'OpeningContrast',arena,str(gap),actor,OPPONENT,plan],cwd=ROOT,text=True)
                    games = []
                    for line in output.splitlines():
                        score,end,n,first,last = line.split('\t')
                        # Java prints the shortest decimal that round-trips to its stored float.
                        end = struct.unpack('>f',struct.pack('>f',float(end)))[0]
                        games.append([float(score),end,int(n),float(first),float(last)])
                    if len(games)!=len(cells): raise ValueError('Lost a fixture')
                    if gap==original_gap:
                        anchor=recorded(STAGES.get(label,'checkpoint32k'),arena,'wtap' if label=='wtap' else 'seven')
                        if any(g[:2]!=r[5:] or list(c[:5])!=r[:5] for g,c,r in zip(games,cells,anchor,strict=True)):
                            raise ValueError(f'Recorded opening changed for {arena}, {label}')
                    key=f'{arena}/{gap}/{label}'
                    results[key]=dict(**summarize(games),
                        seat={s:summarize([g for g,c in zip(games,cells) if c[2]==s]) for s in 'ab'},
                        rtt={f'{a}/{b}':summarize([g for g,c in zip(games,cells) if c[3:5]==(a,b)]) for a,b in sorted({c[3:5] for c in cells})})
                    record(DEST/(key+'.json.gz'),gzip.compress(json.dumps(games,separators=(',',':')).encode(),mtime=0))
    write_json(DEST/'results.json',results,fixed=True)
    report()


def report():
    results=read(DEST/'results.json')
    arms=(*STAGES,'wtap')
    repetitions=read(OUT/'tournaments/checkpoint32k/protocol.json')['model_repetitions']
    cells=make_panel(CLOCKS['dev'],repetitions)
    effects={arm:{k:paired_effect(arm,cells,k=='floor') for k in ('floor','gap')} for arm in arms}
    write_json(DEST/'paired-effects.json',effects,fixed=True)
    lines=['# How much of the change comes from the opening distance?','',
        'Both policies use seven CPS in the simulator. Each row compares two frozen specialists and plain W-tap against Sumo Seven on the same 128 fixtures. The two synthetic openings keep the original facing, floor, midpoint and clocks. Entries are wins/draws/losses.','',
        '| Floor | Start gap | Opening | 16k specialist | 32k specialist | Plain W-tap |',
        '| --- | --- | --- | --- | --- | --- |']
    for arena,original_gap in MAPS.items():
        for gap in (8,10):
            cellscores=[]
            for arm in arms:
                v=results[f'{arena}/{gap}/{arm}']
                cellscores.append('/'.join(str(v[k]) for k in ('wins','draws','losses')))
            lines.append('| '+' | '.join([arena,str(gap),'recorded' if gap==original_gap else 'synthetic',*cellscores])+' |')
    def describe(change):
        value=effects['32k'][change]
        lo,hi=value['approximate_95_percent_interval']
        return f"{100*value['mean']:+.1f} percentage points (approximate 95% interval {100*lo:+.1f} to {100*hi:+.1f})"
    timeouts=results['mmc-221/10/32k']
    prior_wins=[]
    for gap in (8,10):
        matches=[json.loads(gzip.decompress((DEST/f'{arena}/{gap}/32k.json.gz').read_bytes())) for arena in MAPS]
        prior_wins.extend(large[0] for small,large in zip(*matches,strict=True) if small[0]==1)
    lines.extend(['',f"For the 32k specialist, changing floors averaged {describe('floor')} in outright wins across both start distances. Moving the spawns from eight to ten blocks averaged {describe('gap')} across both floors. These are paired estimates across eight timing donors.",
         '',f"Of the {len(prior_wins)} fixtures it won on the smaller floor, {prior_wins.count(1)} remained wins on the larger floor, {prior_wins.count(.5)} became draws and {prior_wins.count(0)} became losses.",
         '',f"All {timeouts['timeouts']} draws in the recorded 221-block opening lasted the full 120 seconds. The two players dealt a median of {timeouts['median_fresh_hits_in_timeout']} fresh hits between them per draw. The median last fresh hit landed {(120000-timeouts['median_last_fresh_ms_in_timeout'])/1000:.2f} seconds before timeout.",
         '','The six recorded-opening arms reproduced all 768 previously measured outcomes and terminal times. Each synthetic comparison uses the same seat, two RTTs, event calendar and action seeds as its recorded counterpart. The larger floor also has its recorded block materials and edges.',
         '', '[Paired estimates](paired-effects.json) · [Seat, RTT and timeout breakdown](results.json) · [Matched native tests on the recorded openings](../tournaments/checkpoint32k/results.md)'])
    atomic(DEST/'README.md',('\n'.join(lines)+'\n').encode())


if __name__=='__main__':
    if sys.argv[1:]==['--report']:report()
    elif not sys.argv[1:]:main()
    else:raise SystemExit('Usage: opening_contrast.py [--report]')
