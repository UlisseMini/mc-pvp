#!/usr/bin/env python3
"""Fit one visible-pose delay on one match; validate landing phase on others.

This is an offline observer check, not a bot policy or a full combat simulator.
The predictor sees only timestamped rendered target heights and configured own
RTT; actual server height is used only as the training/validation label.
"""
import bisect
import hashlib
import json
import math
from pathlib import Path
from science import client_events, server_events
from trade_return_windows import link_clicks

ROOT=Path(__file__).resolve().parent


def samples(mid):
    game=json.loads((ROOT/'runtime/results'/(mid+'.json')).read_text())
    native=client_events(game,'a');raw=server_events(mid)
    _,links,audit=link_clicks(raw,native,game,'a')
    if audit['errors']:raise ValueError(audit)
    obs=[e for e in native if e['type']=='observation']
    times=[e['time'] for e in obs]
    result=[]
    for i,e in enumerate(raw):
        if e['type']!='science_server_attack' or e['attacker']!=game['a'] or e['score_before']==e['score_after'] or e['target_before']['hurtResistantTime']>10 or i not in links:
            continue
        click_at=game['start']+links[i]['consumption_ms']
        # Strictly earlier wall timestamps avoid same-ms event ordering leakage.
        prior=obs[max(0,bisect.bisect_left(times,click_at)-5):bisect.bisect_left(times,click_at)]
        prior=[p for p in prior if click_at-200<=p['time']<click_at]
        if len(prior)<3:continue
        t=[(p['time']-click_at)/1000 for p in prior];y=[p['target_y']-4 for p in prior]
        mt=sum(t)/len(t);my=sum(y)/len(y)
        denom=sum((v-mt)**2 for v in t)
        slope=sum((a-mt)*(b-my) for a,b in zip(t,y))/denom if denom else 0
        # Extrapolate from latest actual visible pose, not a retrospective
        # intercept which could shift flat/landing samples above the floor.
        result.append(dict(match=mid,t_ms=e['time']-game['start'],
                           visible_height=y[-1],slope=slope,sample_age_ms=click_at-prior[-1]['time'],
                           own_rtt_ms=game['ping_a'],actual_height=e['target_before']['posY']-4,
                           actual_sprint=e['attacker_before']['sprint']))
    return result


def predict(row,base_ms):
    if base_ms is None:return row['visible_height']
    horizon=(base_ms+row['own_rtt_ms']+row['sample_age_ms'])/1000
    return max(0,row['visible_height']+row['slope']*horizon)


def metrics(rows,base_ms):
    errors=[predict(r,base_ms)-r['actual_height'] for r in rows]
    matrix={'ground_ground':0,'ground_air':0,'air_ground':0,'air_air':0}
    for r in rows:
        key=('ground' if predict(r,base_ms)<=.05 else 'air')+'_'+('ground' if r['actual_height']<=.05 else 'air')
        matrix[key]+=1
    absolute=sorted(map(abs,errors))
    return dict(n=len(rows),bias=sum(errors)/len(errors),mae=sum(absolute)/len(absolute),
                p95_absolute=absolute[min(len(absolute)-1,int(.95*len(absolute)))],
                predicted_then_actual=matrix,phase_accuracy=(matrix['ground_ground']+matrix['air_air'])/len(rows))


def main():
    train=samples('m1170')
    # One physically interpretable nuisance parameter; no policy search.
    delay=min(range(0,201,5),key=lambda d:metrics(train,d)['mae'])
    document=dict(training_match='m1170',base_delay_ms=delay,
                  source_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                  method='Causal last200ms rendered-height slope, clamp at floor; horizon = fitted base + known own RTT + sample age. Single training match; subsequent listed matches are held out from fitting.',
                  limitation='Observation-only linear phase estimate; server pose at attack is not target-client state at velocity receipt. No full-physics or prospective live-policy validation claim.',
                  training={'raw_visible':metrics(train,None),'linear':metrics(train,delay)},validation=[])
    for mid in ('m1167','m1172','m1156'):
        rows=samples(mid)
        document['validation'].append(dict(match=mid,raw_visible=metrics(rows,None),linear=metrics(rows,delay),samples=rows))
    path=ROOT/'research/launch-phase-model-20261101.json'
    path.write_text(json.dumps(document,indent=2)+'\n')
    print('Fitted base delay',delay,'ms; training',document['training'])
    for v in document['validation']:print(v['match'],v['linear'],'raw MAE',v['raw_visible']['mae'])


if __name__=='__main__':main()
