#!/usr/bin/env python3
"""Post-hoc native explanation of optional critical attempts; never policy input."""
import argparse
import collections
import gzip
import hashlib
import json
from pathlib import Path

from trade_return_windows import link_clicks


def audit(path):
    plan=json.loads(path.read_text())
    if plan['stage']!='complete':raise ValueError('Finish the frozen cohort first')
    totals=collections.Counter();games=[]
    for result in plan['results']:
        game=result['game'];fixture=result['fixture'];side='b' if fixture['reverse'] else 'a'
        def read(label):
            with gzip.open(path.parent/'traces'/f"{game['id']}-{label}.jsonl.gz",'rt') as f:
                return [json.loads(line) for line in f]
        raw=read('server');native=read(side)
        rows,_,links=link_clicks(raw,native,game,side)
        if links['errors']:raise ValueError(links)
        injection_by_sequence={c['sequence']:i for i,c in zip(
            [e for e in native if e['type']=='click'],[e for e in native if e['type']=='science_click'])}
        counts=collections.Counter();attempts=[]
        for row in rows:
            injection=injection_by_sequence[row['sequence']]
            if not injection.get('burst_critical'):continue
            counts['issued']+=1;counts[row['outcome']]+=1
            detail={'sequence':row['sequence'],'outcome':row['outcome'],
                    'consumption_ms':row['consumption_ms'],'also_recoil':injection.get('burst_recoil',False)}
            index=row['server_attack_index']
            if index is not None:
                event=raw[index];attacker=event['attacker_before'];target=event['target_before']
                # Flat, unenchanted, effect-free sword fixtures: all other native
                # critical predicates are fixed. This uses diagnostics in post.
                falling=attacker['fallDistance']>0 and not attacker['ground']
                damage=12 if falling else 8
                expected=('fresh' if target['hurtResistantTime']<=10 else
                          'upgrade' if damage>target['lastDamage'] else 'immune')
                if row['outcome'] not in ('after_winning_score',expected):
                    raise AssertionError((game['id'],row['sequence'],expected,row['outcome']))
                detail.update({'server_falling':falling,'server_expected_damage':damage,
                    'target_previous_damage':target['lastDamage'],'target_hurt_resistance':target['hurtResistantTime'],
                    'server_sprint_before':attacker['sprint'],'server_sprint_after':event['attacker_after']['sprint']})
                counts['server_falling' if falling else 'server_not_falling']+=1
                if row['outcome']=='immune':
                    cause='immune_equal_critical' if falling else 'immune_server_not_falling'
                    counts[cause]+=1;detail['rejection_explanation']=cause
                if row['outcome']=='upgrade' and attacker['sprint'] and not event['attacker_after']['sprint']:
                    counts['upgrade_consumed_server_sprint']+=1
                    next_fresh=next((e for e in raw[index+1:] if e['type']=='science_server_attack'
                        and e['attacker']==game[side] and e['score_before']!=e['score_after']
                        and max(e['score_before'])<100 and e['target_before']['hurtResistantTime']<=10),None)
                    if next_fresh is not None:
                        sprint=next_fresh['attacker_before']['sprint']
                        counts['next_fresh_sprint_restored' if sprint else 'next_fresh_without_sprint']+=1
                        detail['next_fresh_has_server_sprint']=sprint
            attempts.append(detail)
        if counts['issued']!=result['diagnostic']['sides'][side].get('critical_audit',{}).get('issued',0):
            raise AssertionError('Unconsumed optional critical input at match tail')
        totals.update(counts);games.append({'id':game['id'],'counts':dict(counts),'linkage':links,'attempts':attempts})
    return {'source':str(path),'source_sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
        'scope':'Post-hoc audit of native server decisions for optional critical inputs. Private state is never fed to the policy. Subsequent sprint state is descriptive, not a paired counterfactual.',
        'counts':dict(totals),'games':games}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input',type=Path);parser.add_argument('output',type=Path)
    args=parser.parse_args();result=audit(args.input)
    args.output.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result['counts'],indent=2))
