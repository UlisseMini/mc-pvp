#!/usr/bin/env python3
"""Post-match comparison against the other actual client; never bot input."""
import argparse,bisect,json,math,statistics
from lab import ROOT,RUNTIME
from experiments import combat_metrics

def diagnostics(identifier):
    raw=[json.loads(l) for l in (RUNTIME/'replays'/f'{identifier}.jsonl').read_text().splitlines()]
    m=raw[0]['match'];events={}
    for s in ('a','b'):
        events[s]=[e for l in (RUNTIME/'bots'/m[s].removeprefix('LabSlot')/'events.jsonl').read_text().splitlines() if (e:=json.loads(l)).get('match')==identifier]
    obs={s:[e for e in events[s] if e['type']=='observation'] for s in ('a','b')}
    plans=[e for e in events['a'] if e['type']=='mpc_plan'];truth=obs['b'];times=[e['time'] for e in truth]
    origin=obs['a'][0];errors={'latent_defender':[],'defender_view_of_attacker':[],'own_delayed_residual':[],'rendered_delayed_residual':[]}
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
        # Early visible-observer logs used a placeholder zero for own residual.
        # Do not report those placeholders as measured prediction accuracy.
        if not p.get('observer') or p.get('own_error_measured'):errors['own_delayed_residual'].append(p['own_observation_error'])
        errors['rendered_delayed_residual'].append(p['view_observation_error'])
    def describe(values):
        v=sorted(values)
        return {'samples':len(v),'median_m':statistics.median(v),'p90_m':v[min(len(v)-1,int(.9*len(v)))],'max_m':max(v)} if v else None
    result={'match':identifier,'rtt_a':m['ping_a'],'rtt_b':m['ping_b'],'errors':{k:describe(v) for k,v in errors.items()},'method':'Post-hoc comparison at real host timestamps, linear interpolation of actual client observations; no private data enters bot'}
    finish=next((e['result'] for e in raw if e['type']=='finish'),None)
    if finish:result['native_overlap']=combat_metrics(finish).get('native_overlap')
    return result

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('match');args=parser.parse_args();data=diagnostics(args.match)
    (ROOT/'experiments'/f'{args.match}-model-diagnostics.json').write_text(json.dumps(data,indent=2)+'\n');print(json.dumps(data,indent=2))
