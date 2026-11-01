#!/usr/bin/env python3
"""Offline training/holdout analysis; dense samples are not independent trials."""
import argparse, collections, gzip, hashlib, json, math, statistics
from pathlib import Path
from lab import ROOT
from science import client_events, server_events
from reach_speed_science import OUT, Track, distribution, vector, direction, dot, sub


def tracks(g):
    answer={}
    for side in ('a','b'):
        ms=[e for e in client_events(g,side) if e['type']=='science_move']
        answer[side]=Track([(ms[0]['time'],vector(ms[0]['before']))]+[(e['time']+.001,vector(e['after'])) for e in ms])
    return answer

def past(track,t):
    if t<=track.times[0]:return track.values[0] # Setup stood still before the first observed travel.
    return track.linear(t)

def history_prediction(tr,t,tau):
    a,b=tr['a'].at(t),tr['b'].at(t)
    pa,pb=past(tr['a'],t-tau*1000),past(tr['b'],t-tau*1000)
    if any(x is None for x in (a,b,pa,pb)):return None
    n=direction(a,b)
    return dot(sub(a,pa),n)+dot(sub(b,pb),n)

def report(name):
    d=json.loads((OUT/(name+'.json')).read_text());rows=[]
    for r in d['results']:
        p=OUT/(name+'-samples')/(r['game']['id']+'.json.gz')
        with gzip.open(p,'rt') as f:s=json.load(f)
        raw=server_events(r['game']['id']);ts=[e['time'] for e in raw if e['type']=='tick' and e['time']>=r['game']['start']]
        gaps=[b-a for a,b in zip(ts,ts[1:])]
        bad=sum(x>80 for x in gaps)/len(gaps) if gaps else 1
        r={**r,'data':s,'tracks':tracks(r['game']),'load_ok':bool(gaps) and bad<=.02 and max(gaps)<=200,'long_tick_fraction':bad}
        rows.append(r)
    train=[r for r in rows if r['fixture']['phase']=='geometry' and r['fixture']['split']=='calibration' and r['fixture']['condition'] in ('walk-still','sprint-still','sprint-walk') and r['load_ok'] and r['data']['opening_samples']]
    means=[(statistics.mean(s['dv'] for s in r['data']['opening_samples']),statistics.mean(s['adv'] for s in r['data']['opening_samples'])) for r in train]
    coefficient=sum(x*y for x,y in means)/sum(x*x for x,y in means) if means else .15
    candidates=[]
    for ms in range(50,301,5):
        losses=[]
        for r in train:
            ss=r['data']['opening_samples'];errors=[abs(history_prediction(r['tracks'],s['t'],ms/1000)-s['adv']) for s in ss]
            losses.append(statistics.mean(errors))
        candidates.append((statistics.mean(losses) if losses else 1,ms/1000))
    loss,tau=min(candidates)
    groups=collections.defaultdict(list)
    firsthits=collections.defaultdict(list)
    scalar_errors=collections.defaultdict(list);history_errors=collections.defaultdict(list)
    trial_metrics=[];native_classification={'samples':0,'mismatches':0,'boundary_excluded':0}
    for r in rows:
        f=r['fixture'];s=r['data'];g=r['game'];unequal=f['condition'].split('-')[0]!=f['condition'].split('-')[-1]
        oriented_flip=f['flipped'] and unequal
        flip=-1 if oriented_flip else 1
        opening=s['opening_samples'];mean=lambda k:statistics.mean(x[k] for x in opening) if opening else None
        ping=(g['ping_a']+g['ping_b'])/2000
        pa,pb=(g['ping_b'],g['ping_a']) if oriented_flip else (g['ping_a'],g['ping_b'])
        prefix=f['phase']+'/'+f['condition']+'/'+str(int(pa))+'_'+str(int(pb))
        # Constant-height, straight approach threshold: center gap 3 + 0.3 player half-width + 0.1 border.
        # The 5-ms state reconstruction estimates opportunity timing; actual50-ms native probes retain brackets.
        geometric={side:next((x['t'] for x in s['samples'] if x['opening'] and x['flat'] and x['dg'+side]<=3.40000002),None) for side in ('a','b')}
        geom_lead=(geometric['b']-geometric['a'])*flip if all(v is not None for v in geometric.values()) else None
        entry_time=min((t for t in geometric.values() if t is not None),default=None)
        entry=next((x for x in s['samples'] if x['t']==entry_time),None)
        stat={'id':g['id'],'phase':f['phase'],'condition':f['condition'],'split':f['split'],'flipped':f['flipped'],'ping':[g['ping_a'],g['ping_b']],
              'load_ok':r['load_ok'],'long_tick_fraction':r['long_tick_fraction'],'qualification':s['qualification'],
              'entry_view_advantage':entry['adv']*flip if entry else None,
              'entry_speed_difference':entry['dv']*flip if entry else None,
              'mean_speed_difference':mean('dv')*flip if opening else None,'mean_view_advantage':mean('adv')*flip if opening else None,
              'predicted_view_advantage':(coefficient+ping)*mean('dv')*flip if opening else None,
              'native_probe_reach_lead_ms':s['first_reach_lead_ms_a']*flip if s['first_reach_lead_ms_a'] is not None else None,'reconstructed_geometric_reach_lead_ms':geom_lead,
              'first_hit_by_faster':s['first_server_hit_side']==('b' if oriented_flip else 'a') if s['first_server_hit_side'] else None,
              'score_oriented':s['score'][::-1] if oriented_flip else s['score']}
        if r['load_ok'] and opening:
            groups[prefix].append(stat)
        if f['phase']=='first_hit' and r['load_ok']:firsthits[f['condition']].append(stat)
        # Errors are computed per match, then pooled as match-level summaries.
        evaluation=[x for x in s['samples'] if 2<=x['d']<=5] if f['phase']=='sustained' else opening
        if evaluation and r['load_ok']:
            e1=[(coefficient+ping)*x['dv']-x['adv'] for x in evaluation]
            e2=[v-x['adv'] for x in evaluation if (v:=history_prediction(r['tracks'],x['t'],tau+ping)) is not None]
            label=f['phase']+('/'+f['split'] if f['phase']=='geometry' else '')
            metrics={'instant_mae':statistics.mean(map(abs,e1)),'instant_bias':statistics.mean(e1),'history_mae':statistics.mean(map(abs,e2)) if e2 else None,
                     'mean_prediction_error':stat['predicted_view_advantage']-stat['mean_view_advantage'] if opening else None}
            stat.update(metrics);scalar_errors[label].append(metrics['instant_mae']);history_errors[label].append(metrics['history_mae'])
        if f['phase'] in ('geometry','ping_holdout','acceleration_holdout'):
            for side in ('a','b'):
                for e in client_events(g,side):
                    if e['type']!='reach_probe' or 'own' not in e:continue
                    a,b=vector(e['own']),vector(e['target'])
                    if abs(a[1]-4)>1e-6 or abs(b[1]-4)>1e-6 or abs(a[2]-b[2])>.001:continue
                    gap=abs(a[0]-b[0]);threshold=3.40000002
                    if abs(gap-threshold)<.0005:native_classification['boundary_excluded']+=1;continue
                    # Exclude post-crossing near-body turns; use only the initial approach.
                    if e['time']>min([x['t'] for x in s['samples'] if x['d']<1.8],default=g['finish']):continue
                    native_classification['samples']+=1
                    native_classification['mismatches']+=int((gap<=threshold)!=e['in_range'])
        trial_metrics.append(stat)
    grouped={k:{'n':len(v), 'advantage':distribution([x['mean_view_advantage'] for x in v]),'speed_difference':distribution([x['mean_speed_difference'] for x in v]),
                'entry_advantage':distribution([x['entry_view_advantage'] for x in v if x['entry_view_advantage'] is not None]),
                'native_probe_lead_ms':distribution([x['native_probe_reach_lead_ms'] for x in v if x['native_probe_reach_lead_ms'] is not None]),
                'geometric_lead_ms':distribution([x['reconstructed_geometric_reach_lead_ms'] for x in v if x['reconstructed_geometric_reach_lead_ms'] is not None])} for k,v in groups.items()}
    errors={k:{'trials':len(v),'mean_instantaneous_absolute_error':statistics.mean(v),'history_model_error':statistics.mean(x for x in history_errors[k] if x is not None)} for k,v in scalar_errors.items()}
    hits={k:{'n':len(v),'first_hits_by_first_named_speed':sum(x['first_hit_by_faster'] is True for x in v),'no_hit':sum(x['first_hit_by_faster'] is None for x in v),'scores':[x['score_oriented'] for x in v]} for k,v in firsthits.items()}
    out={'source':str(OUT/(name+'.json')),'stage':d['stage'],'completed':len(rows),'planned':len(d['fixtures']),'coefficient_seconds':coefficient,
         'history_delay_seconds':tau,'history_calibration_mae':loss,'calibration_matches':[r['game']['id'] for r in train],
         'load_excluded':[r['game']['id'] for r in rows if not r['load_ok']], 'native_flat_ray_classification':native_classification,
         'groups':grouped,'first_hits':hits,'error_by_phase':errors,'trials':trial_metrics,
         'method':'Fit one coefficient on preregistered calibration trials; all numerical errors are match-level means. Data from two shared-host clients, not server position truth. 5-ms reconstructed crossings approximate native frame timing; direct probes are about50ms apart.',
         'analysis_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    path=OUT/(name+'-analysis.json');path.write_text(json.dumps(out,indent=2)+'\n')
    print(json.dumps({k:out[k] for k in ('stage','completed','coefficient_seconds','history_delay_seconds','load_excluded','native_flat_ray_classification','groups','first_hits','error_by_phase')},indent=2))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('name');args=p.parse_args();report(args.name)
