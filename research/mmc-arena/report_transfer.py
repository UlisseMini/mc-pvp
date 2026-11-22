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
    if 'published_winner' in protocol or 'published_policy' in protocol:
        arm='winner' if 'published_winner' in protocol else 'generalist'
        title=(protocol.get('published_winner') or protocol['published_policy'])+' on the MMC validation floors'
        intro=f"The published {arm} faces {protocol['opponent_name']}, its weakest distinct development opponent, and plain W-tap. Every matchup uses seven CPS on both sides."
        columns=['Platform',arm.title()+' vs '+protocol['opponent_name'],'Plain W-tap vs '+protocol['opponent_name'],arm.title()+' vs W-tap','Plain W-tap mirror']
        weak=protocol['contestants'][arm]['target']
        matchups=[(arm,weak),('wtap',weak),(arm,'wtap'),('wtap','wtap')]
    else:
        title={'checkpoint32k':'32,768-fight specialists','checkpoint64k':'65,536-fight specialists','baseline16k':'16,384-fight specialists','sealed-final':'Frozen specialists on sealed maps'}[stage]
        intro=None
        columns=['Platform','Frozen best-sumo','W-tap vs best-sumo','League winner 001','W-tap vs winner 001','Sumo Seven','W-tap vs Seven']
        matchups=[(a,protocol['contestants'][arm]['target']) for arm in DISPLAY for a in (arm,'wtap')]
    lines=['# '+title,
           '', 'Entries are wins/draws/losses. Every duel uses the MMC preset and a seven-CPS cap for both players. '
           'Each specialist faces its own frozen training opponent. Plain W-tap faces that same opponent with the same seat, reach and two RTTs.']
    if intro:lines[-1]='Entries are wins/draws/losses. '+intro
    for mode in ('model','native'):
        title='Simulator' if mode=='model' else 'Main Minecraft server'
        lines.extend(['','## '+title,''])
        lines.extend(['| '+' | '.join(columns)+' |','| '+' | '.join(['---']*len(columns))+' |'])
        references=[]
        native_blocks=[]
        for name in (protocol.get('native_maps',protocol['maps']) if mode=='native' else protocol['maps']):
            base=folder/mode/name
            completed=base/'summary.json'
            if not completed.exists():
                partial=sorted(base.glob('after-*-blocks.json'))
                completed=partial[-1] if partial else completed
            cells=[]
            if completed.exists():
                r=read(completed)
                by_arm={(v.get('actor',v.get('arm')),v['foe']):v for v in r.values()}
                cells=[tally(by_arm[pair]) for pair in matchups]
                if mode=='native':
                    counts=[v['wins']+v['draws']+v['losses'] for pair in matchups for v in [by_arm[pair]]]
                    native_blocks.append((name,min(counts),max(counts)))
                if 'original_16k' in protocol:
                    for arm in DISPLAY:
                        opponent=protocol['contestants'][arm]['target']
                        old=by_arm.get((arm+'-16k',opponent),by_arm[arm,opponent])
                        references.append([name,DISPLAY[arm],tally(by_arm[arm,opponent]),tally(old),tally(by_arm['wtap',opponent])])
            else:
                cells=['pending']*len(matchups)
                if mode=='native':
                    native_blocks.append((name,0,0))
            lines.append('| '+' | '.join([name,*cells])+' |')
        if references:
            lines.extend(['','### Matched final and original checkpoints','','| Platform | Frozen opponent | Selected specialist | Original 16k specialist | Plain W-tap |',
                          '| --- | --- | --- | --- | --- |'])
            lines.extend('| '+' | '.join(row)+' |' for row in references)
        if mode=='model':
            lines.extend(['','Each cell contains 128 bouts from eight timing recordings, mirrored seats and four RTT pairs. '
                'Matching timing recordings and action seeds across maps lets the platform change while the fixture stays fixed. '
                'The same timing recordings appear in several cells.'])
        else:
            planned=8
            design_path=folder/'native/design.json'
            if design_path.is_file():
                design=read(design_path)
                if isinstance(design,dict) and isinstance(design.get('blocks'),list):
                    planned=len(design['blocks'])
            completed_min=min((lo for _,lo,_ in native_blocks),default=0)
            completed_max=max((hi for _,_,hi in native_blocks),default=0)
            if completed_min==completed_max==planned:
                sample_note=f'All {planned} of {planned} planned seat-and-RTT blocks per matchup are completed.'
            elif completed_min==completed_max:
                sample_note=f'Partial native sample: {completed_min} of {planned} planned seat-and-RTT blocks completed per matchup.'
            else:
                sample_note=f'Partial native sample: {completed_min}–{completed_max} of {planned} planned seat-and-RTT blocks completed per matchup.'
            lines.extend(['',sample_note+' Each published block has passed movement, physical click-spacing, map-state and countdown spawn checks.'])
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
