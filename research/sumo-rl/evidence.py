"""Reduce the individual native/model comparisons to a portable fidelity record."""
import argparse,json
from pathlib import Path

def read(path):
    return json.loads(path.read_text())

def strict(path):
    rows=[]
    for q in read(path)['reports']:
        rows.append({'mid':q['match'],'requested_seconds':q['requested_seconds'],'audited_seconds':q['seconds'],
            'events':q['completed_event_rows'],'requested_events':q['requested_event_rows'],
            'bit_mismatch_rows':sum(v['bit_mismatch_rows'] for v in q['summary'].values()),
            'checked_snapshots':sum(v['n'] for v in q['summary'].values()),
            'first_failure':q['replay_failure'].splitlines()[0] if q['replay_failure'] else None,
            'range_error':q['range_summary']})
    return rows

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('build',type=Path);p.add_argument('out',type=Path);a=p.parse_args()
    v=a.build/'v3';physics=v/'physics';lat=v/'lateral-physics'
    out={'head_on_strict_legacy_boxing':strict(a.build/'coupled-replay.json'),
         'head_on_strict_sumo':strict(a.build/'v2/full-sumo.json'),
         'v3_two_sided_probe_availability':read(physics/'availability.json'),
         'v3_head_on_strict_prior_to_tracker_order_fix':strict(physics/'strict.json'),
         'v3_head_on_strict':strict(physics/'order-strict.json'),'physical_input_estimated_transport':read(physics/'all-input.json'),
         'lateral_strict':strict(lat/'strict.json'),'lateral_physical_input_estimated_transport':read(lat/'physical.json'),
         'travel':{k:read(path) for k,path in [('initial',a.build/'arena-travel.json'),('jumps_and_edge',a.build/'v2/jump-travel.json'),
             ('v3',physics/'travel.json'),('lateral',lat/'travel.json')]},
         'throughput':read(v/'throughput-final.json'),'v3_native_mechanism':read(v/'mechanism.json'),
         'clock_split':{d:sorted(q.name.split('.')[0] for q in (Path(__file__).resolve().parent/d).glob('*.txt.gz'))
                       for d in ('train-clocks','test-clocks','league-clocks','validation-clocks','league-validation-clocks')}}
    train=set(out['clock_split']['train-clocks']+out['clock_split']['league-clocks'])
    assert not train.intersection(out['clock_split']['league-validation-clocks'])
    with a.out.open('x') as f:json.dump(out,f,indent=2)
