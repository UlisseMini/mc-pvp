#!/usr/bin/env python3
"""Post-match comparison against the other actual client; never bot input."""
import argparse,bisect,json,math,statistics
from lab import ROOT,RUNTIME
from experiments import combat_metrics
from lab_runtime import match_events

def diagnostics(identifier):
    raw=[json.loads(l) for l in (RUNTIME/'replays'/f'{identifier}.jsonl').read_text().splitlines()]
    m=next(e['match'] for e in raw if e['type']=='start');events={}
    for s in ('a','b'):
        events[s]=list(match_events(RUNTIME/'bots'/m[s].removeprefix('LabSlot')/'events.jsonl',identifier))
    obs={s:[e for e in events[s] if e['type']=='observation'] for s in ('a','b')}
    model_side=next((side for side in ('a','b') if any(e['type']=='mpc_plan' for e in events[side])), 'a')
    opponent_side='b' if model_side=='a' else 'a'
    plans=[e for e in events[model_side] if e['type']=='mpc_plan'];truth=obs[opponent_side];times=[e['time'] for e in truth];own=obs[model_side]
    origin=own[0];errors={k:[] for k in ('latent_defender','defender_view_of_attacker','present_own','present_rendered_target','own_delayed_residual','rendered_delayed_residual')}
    own_times=[e['time'] for e in own]
    for p in plans:
        i=bisect.bisect_right(times,p['time'])
        if i<1 or i>=len(times) or times[i]-times[i-1]>150:continue
        a,b=truth[i-1:i+1];f=(p['time']-a['time'])/(b['time']-a['time'])
        def actual(key):return a[key]+f*(b[key]-a[key])
        if 'predicted_target_z' in p:
            for label,prefix,t_prefix in [('latent_defender','predicted_target_',''),('defender_view_of_attacker','predicted_defender_view_','target_')]:
                err=[]
                for axis in ('x','y','z'):
                    offset=4 if axis=='y' else origin[axis]
                    err.append((p[prefix+axis]+offset-actual(t_prefix+axis))**2)
                errors[label].append(math.sqrt(sum(err)))
            j=bisect.bisect_right(own_times,p['time'])
            if 0<j<len(own_times) and own_times[j]-own_times[j-1]<=150:
                left,right=own[j-1:j+1];fraction=(p['time']-left['time'])/(right['time']-left['time'])
                for label,prefix,t_prefix in [('present_own','predicted_own_',''),('present_rendered_target','predicted_target_view_','target_')]:
                    if prefix+'z' not in p:continue
                    errors[label].append(math.sqrt(sum((p[prefix+axis]+(4 if axis=='y' else origin[axis])-(left[t_prefix+axis]+fraction*(right[t_prefix+axis]-left[t_prefix+axis])))**2 for axis in ('x','y','z'))))
        # Early visible-observer logs used a placeholder zero for own residual.
        # Do not report those placeholders as measured prediction accuracy.
        if not p.get('observer') or p.get('own_error_measured'):errors['own_delayed_residual'].append(p['own_observation_error'])
        errors['rendered_delayed_residual'].append(p['view_observation_error'])
    def describe(values):
        v=sorted(values)
        return {'samples':len(v),'median_m':statistics.median(v),'p90_m':v[min(len(v)-1,int(.9*len(v)))],'max_m':max(v)} if v else None
    result={'match':identifier,'model_side':model_side,'rtt_a':m['ping_a'],'rtt_b':m['ping_b'],'errors':{k:describe(v) for k,v in errors.items()},'method':'Post-hoc comparison at real host timestamps, linear interpolation of actual client observations; no private data enters bot'}
    finish=next((e['result'] for e in raw if e['type']=='finish'),None)
    if finish:result['native_overlap']=combat_metrics(finish).get('native_overlap')
    return result

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('match');args=parser.parse_args();data=diagnostics(args.match)
    (ROOT/'experiments'/f'{args.match}-model-diagnostics.json').write_text(json.dumps(data,indent=2)+'\n');print(json.dumps(data,indent=2))
