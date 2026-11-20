"""Render completed matched map comparisons from the frozen ledgers."""
import json
import os
import sys
import tempfile
from pathlib import Path

from platforms import OUT

DISPLAY={'incumbent':'frozen best-sumo','g1':'league winner 001','seven':'Sumo Seven'}

def read(path):
    return json.loads(path.read_bytes())

def tally(r):
    return f"{r['wins']}/{r['draws']}/{r['losses']}"

def report(stage):
    folder=OUT/'tournaments'/stage
    protocol=read(folder/'protocol.json')
    lines=['# '+{'checkpoint32k':'32,768-fight specialists','baseline16k':'16,384-fight specialists','sealed-final':'Frozen specialists on sealed maps'}[stage],
           '', 'Entries are wins/draws/losses. Every duel uses the MMC preset and a seven-CPS cap for both players. '
           'Each specialist faces its own frozen training opponent. Plain W-tap faces that same opponent with the same seat, reach and two RTTs.']
    for mode in ('model','native'):
        title='Simulator' if mode=='model' else 'Main Minecraft server'
        lines.extend(['','## '+title,''])
        columns=['Platform','Frozen best-sumo','W-tap vs best-sumo','League winner 001','W-tap vs winner 001','Sumo Seven','W-tap vs Seven']
        lines.extend(['| '+' | '.join(columns)+' |','| '+' | '.join(['---']*len(columns))+' |'])
        for name in protocol['maps']:
            base=folder/mode/name
            completed=base/'summary.json'
            if not completed.exists():
                partial=sorted(base.glob('after-*-blocks.json'))
                completed=partial[-1] if partial else completed
            cells=[]
            if completed.exists():
                r=read(completed)
                by_arm={(v.get('actor',v.get('arm')),v['foe']):v for v in r.values()}
                for arm in DISPLAY:
                    opponent=protocol['contestants'][arm]['target']
                    cells.extend(tally(by_arm[a,opponent]) for a in (arm,'wtap'))
            else:cells=['pending']*6
            lines.append('| '+' | '.join([name,*cells])+' |')
        if mode=='model':
            lines.extend(['','Each cell contains 128 bouts from eight timing recordings, mirrored seats and four RTT pairs. '
                'Matching timing recordings and action seeds across maps lets the platform change while the fixture stays fixed. '
                'The same timing recordings appear in several cells.'])
        else:
            lines.extend(['','The live comparison has eight independently played seat and RTT blocks per matchup. '
                'Each published block has passed movement, physical click-spacing, map-state and countdown spawn checks.'])
            check=folder/'native/release-check'
            if (check/'verified.json').is_file() and (check/'spawn-checks.json').is_file():
                scores=read(check/'verified.json')['matches'];plan=read(check/'protocol.json')['plan']
                lines.extend(['','Four release checks already played on the main server. These same games count in the first full block. '
                    'Both players had 31 ms RTT. The challenger occupied seat A in each game.',
                    '','| Platform | 16k specialist vs frozen best-sumo | Plain W-tap vs frozen best-sumo |',
                    '| --- | --- | --- |'])
                for name in ('mmc-129','mmc-221'):
                    outcomes={plan[q]['arm']=='research-wtap20':{1:'win',.5:'draw',0:'loss'}[v['score']]
                              for q,v in scores.items() if plan[q]['map']==name}
                    lines.append(f'| {name} | {outcomes[False]} | {outcomes[True]} |')
    lines.extend(['','[Match protocol](protocol.json) · [Per-seat and per-RTT simulator results](model)'])
    if (folder/'native/design.json').exists():lines[-1]+=' · [Native schedule and audits](native)'
    raw=('\n'.join(lines)+'\n').encode();destination=folder/'results.md'
    if destination.is_file() and destination.read_bytes()==raw:return
    with tempfile.NamedTemporaryFile(dir=folder,delete=False) as tmp:tmp.write(raw)
    os.replace(tmp.name,destination)

if __name__=='__main__':
    for stage in sys.argv[1:]:report(stage)
