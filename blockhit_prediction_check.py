#!/usr/bin/env python3
"""Evaluate recorded client-only linear forecasts against later native fresh-hurt cues.

Calibration uses first games; reversed-side repeats are held out. Never supplies
opponent/server state to a live bot. Errors are actual cue minus predicted cue.
"""
import argparse
import bisect
import json
import math
from pathlib import Path
import statistics


def describe(errors):
    if not errors:
        return {'n': 0}
    mean = statistics.mean(errors)
    sem = statistics.stdev(errors) / math.sqrt(len(errors)) if len(errors) > 1 else 0
    return {'n': len(errors), 'mean_error_ms': mean, 'mean_95ci_ms': [mean-1.96*sem, mean+1.96*sem],
            'median_error_ms': statistics.median(errors), 'mae_ms': statistics.mean(map(abs, errors)),
            'within_25ms': sum(abs(e) <= 25 for e in errors)/len(errors),
            'within_50ms': sum(abs(e) <= 50 for e in errors)/len(errors)}


def game(directory, match, horizon):
    events = [json.loads(line) for line in (directory/(match['id']+'-client.jsonl')).read_text().splitlines()]
    plans = [e for e in events if e['type'] == 'blockhit_plan' and e.get('model') == 'linear']
    cues = [e['time'] for e in events if e['type'] == 'fresh_hurt_status']
    times = [e['time'] for e in plans]
    errors = []
    missing = 0
    for cue in cues:
        i = bisect.bisect_right(times, cue-horizon)-1
        if i < 0 or cue-horizon-times[i] > 75:
            missing += 1
            continue
        prediction = plans[i].get('predicted_receipt_at')
        if prediction is None:
            missing += 1
        else:
            errors.append(cue-prediction)
    # Forecast-triggered alarms include false positives. One alarm per 200 ms;
    # unlike the fixed-horizon table, no actual hit is needed to select a sample.
    alarms = []
    previous = 0
    for p in plans:
        prediction = p.get('predicted_receipt_at')
        if prediction is None or not 25 <= prediction-p['time'] <= 125 or p['time']-previous < 200:
            continue
        previous = p['time']
        i = bisect.bisect_left(cues, p['time'])
        actual = cues[i] if i < len(cues) else None
        alarms.append(None if actual is None or actual-prediction > 250 else actual-prediction)
    return {'match': match['id'], 'side': match['blockhit_summary']['bot_side'],
            'rtt': [match['ping_'+match['blockhit_summary']['bot_side']], match['ping_'+('b' if match['blockhit_summary']['bot_side']=='a' else 'a')]],
            'cues': len(cues), 'missing': missing, 'errors': errors, 'alarms': alarms}


def run(directories, output):
    tables = {}
    for horizon in (50,100,150,200):
        games = []
        for directory in directories:
            report = json.loads((directory/'report.json').read_text())
            games += [game(directory,m,horizon) for m in report['results']]
        train = [e for g in games if g['side']=='a' for e in g['errors']]
        test = [e for g in games if g['side']=='b' for e in g['errors']]
        bias = 5*round(statistics.mean(train)/5) if train else 0
        tables[str(horizon)] = {'calibration_bias_ms': bias, 'train': describe(train),
            'held_out_raw': describe(test), 'held_out_corrected': describe([e-bias for e in test]), 'games': games}
    report={'definition':'actual native cue receipt - prediction; positive means forecast was early',
            'fixed_horizon_ms':tables,'directories':[str(p) for p in directories],
            'limitations':'Fixed-horizon errors condition on an actual hit; report missing coverage and forecast-triggered false positives too. CI is descriptive and treats hits as independent; matches are the replication unit.'}
    output.write_text(json.dumps(report,indent=2)+'\n')
    for horizon,table in tables.items():
        print(horizon,'ms:',{k:v for k,v in table.items() if k!='games'},'coverage',sum(len(g['errors']) for g in table['games']), '/',sum(g['cues'] for g in table['games']))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('directories',nargs='+',type=Path);p.add_argument('--output',required=True,type=Path)
    args=p.parse_args();run(args.directories,args.output)
