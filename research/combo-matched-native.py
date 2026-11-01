#!/usr/bin/env python3
"""Matched repeated native baselines/candidates; no controller mutation/search."""
import asyncio
import json
from pathlib import Path
import sys
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
import experiments
import parity
from lab_runtime import send
from science import server_events
from combo_survival import analyze


def metrics(game):
    events=[e for e in server_events(game['id']) if e['type']=='hit' and not e.get('rod')]
    first=None;count=0
    for e in events:
        if e['attacker']==game['b']:
            first=e['time'];break
        if e['attacker']==game['a'] and not e.get('damage_upgrade',False):count+=1
    detail=analyze(game)['sides']['a']
    return {'fresh_hits_before_first_return':count,'zero_return_horizon':first is None,
            'first_return_ms':None if first is None else first-game['start'],
            'longest_unanswered_fresh_run':detail['longest_fresh_run'],
            'after_opponent_wake':detail['after_opponent_wake']}


async def main():
    out=ROOT/'research/matched-native-20261101.json'
    if out.exists():raise RuntimeError('Refuse overwrite; preserve prior results')
    policies=['research-hold20','combo-wtap20','research-wtap20','cycle-word-r30','cycle-strafe-r30','cycle-cue-r30']
    report={'scope':'Matched native30s boxing, own/target3.0reach,20CPS cap,RTT0/120,vanillaKB/hit10,perfectaim,target2s opening no-click handicap. Historical combo-wtap20 reaction150ms explicitly retained; all other candidates/references zero delay. Three sequential repeats, rotated order. Not a superiority claim until complete spread and quality checked.',
            'primary_metric':'Fresh hits before first accepted return within the fixed30s horizon; whether the whole horizon had zero returns. Counts include the identically declared opening handicap; post-wake metrics separate.',
            'wtap_baseline':'research-wtap20','historical_wtap_baseline':'combo-wtap20','rows':[]}
    for repeat in range(3):
        for policy in policies[repeat:]+policies[:repeat]:
            send('lab combat vanilla')
            game=await experiments.match(policy,'research-wake-hold',0,120,duration_ms=30000)
            parity.archive(game)
            row={'repeat':repeat+1,'policy':policy,'match':game['id'],'score':[game['hits_a'],game['hits_b']],
                 'validation':game.get('validation'),**metrics(game)}
            report['rows'].append(row);out.write_text(json.dumps(report,indent=2)+'\n')
            print(json.dumps(row),flush=True)
    report['complete']=True;out.write_text(json.dumps(report,indent=2)+'\n')


if __name__=='__main__':asyncio.run(main())
