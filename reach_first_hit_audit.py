#!/usr/bin/env python3
"""Retrospective first-hitter audit of frozen speed/reach models, with explicit abstentions.

No new fit: use the sign of each model's reach advantage at the first available
sample within five blocks. Samples begin 150 ms after fight start. Both players'
local movement histories are offline inputs; remote-view samples and future hits
are not prediction inputs. This is not a setup-only or click-scheduling model.
"""
import collections
import gzip
import hashlib
import json
import statistics
from pathlib import Path

from reach_speed_report import tracks, history_prediction
from science import server_events

ROOT=Path(__file__).resolve().parent


def pick(edge):
    return 'a' if edge>1e-9 else 'b' if edge< -1e-9 else None


def tally(rows,key):
    called=[r for r in rows if r[key] is not None]
    correct=sum(r[key]==r['actual_first_side'] for r in called)
    return {'trials':len(rows),'correct':correct,'wrong':len(called)-correct,'no_predicted_edge':len(rows)-len(called),
            'correct_fraction_all_trials':correct/len(rows) if rows else None,
            'accuracy_when_a_side_was_named':correct/len(called) if called else None,
            'coverage':len(called)/len(rows) if rows else None}


def run():
    source=ROOT/'research/speed-reach-v2.json';fit_path=ROOT/'research/speed-reach-v2-analysis.json'
    cohort=json.loads(source.read_text());fit=json.loads(fit_path.read_text());rows=[]
    for entry in cohort['results']:
        fixture,game=entry['fixture'],entry['game']
        if fixture['phase'] not in ('first_hit','sustained'):continue
        assert game['id'] not in fit['calibration_matches']
        assert game['id'] not in fit['load_excluded']
        with gzip.open(ROOT/'research/speed-reach-v2-samples'/f'{game["id"]}.json.gz','rt') as stream:data=json.load(stream)
        # Do not select the checkpoint using the actual first-hit time, its outcome,
        # or the rendered-opponent observations. Use only current local separation.
        checkpoint=next(s for s in data['samples'] if s['d']<=5)
        tau=fit['history_delay_seconds']+(game['ping_a']+game['ping_b'])/2000
        edge=history_prediction(tracks(game),checkpoint['t'],tau)
        old_edge=(fit['coefficient_seconds']+(game['ping_a']+game['ping_b'])/2000)*checkpoint['dv']
        hits=[e for e in server_events(game['id']) if e['type']=='hit'];first=hits[0]
        assert checkpoint['t']<first['time'],game['id']
        other=next((e for e in hits if e['attacker']!=first['attacker']),None)
        rows.append({'match':game['id'],'phase':fixture['phase'],'condition':fixture['condition'],
                     'a':game['a'],'b':game['b'],'policy_a':game['policy_a'],'policy_b':game['policy_b'],
                     'ping':[game['ping_a'],game['ping_b']],'checkpoint_ms':checkpoint['t']-game['start'],
                     'checkpoint_separation':checkpoint['d'],'prediction_lead_ms':first['time']-checkpoint['t'],
                     'instantaneous_predicted_edge':old_edge,'history_predicted_edge':edge,
                     'instantaneous_pick':pick(old_edge),'history_pick':pick(edge),
                     'actual_first_side':'a' if first['attacker']==game['a'] else 'b',
                     'first_opposing_hit_delay_ms':other['time']-first['time'] if other else None})
    groups=collections.defaultdict(list)
    for row in rows:groups[row['phase']+'/'+row['condition']].append(row)
    result={'method':'Retrospective sign-of-reach-edge classifier at first available local separation <=5 blocks; stored samples begin at +150ms. No tie-breaking: |edge|<=1e-9 is no prediction. Native accepted-hit log order defines the first hitter, including same-millisecond contacts.',
            'limitations':['Scoring rule chosen after the cohort existed; no parameter refit or new prospective matches.',
                           'Uses both players\' actual recent local movement histories from the logs; not a prediction from nominal speeds alone.',
                           'Predicts first hitter by a reach-edge proxy; does not model click timing or future client/server packet scheduling.'],
            'fit_matches':fit['calibration_matches'],'history_delay_seconds':fit['history_delay_seconds'],
            'original_speed_coefficient_seconds':fit['coefficient_seconds'],
            'cohort_trials':len(cohort['results']),'no_attack_trials':len(cohort['results'])-len(rows),
            'source_sha256':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in (source,fit_path,Path(__file__),ROOT/'reach_speed_report.py')},
            'all_instantaneous':tally(rows,'instantaneous_pick'),'all_refined':tally(rows,'history_pick'),
            'dedicated_first_hit':tally([r for r in rows if r['phase']=='first_hit'],'history_pick'),
            'sustained_openings':tally([r for r in rows if r['phase']=='sustained'],'history_pick'),
            'stationary_opponent':tally([r for r in rows if 'still' in r['condition']],'history_pick'),
            'both_moving':tally([r for r in rows if 'still' not in r['condition']],'history_pick'),
            'prediction_lead_ms':{'min':min(r['prediction_lead_ms'] for r in rows),'max':max(r['prediction_lead_ms'] for r in rows),'median':statistics.median(r['prediction_lead_ms'] for r in rows)},
            'conditions':{k:{'instantaneous':tally(v,'instantaneous_pick'),'refined':tally(v,'history_pick')} for k,v in groups.items()},
            'trials':rows}
    assert len(rows)==36 and len(cohort['results'])==88
    path=ROOT/'research/speed-reach-first-hit-audit.json';path.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({k:v for k,v in result.items() if k not in ('trials','source_sha256','fit_matches')},indent=2))


if __name__=='__main__':run()
