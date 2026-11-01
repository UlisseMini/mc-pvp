#!/usr/bin/env python3
"""Conditional recorded-target reach envelopes; never launches Minecraft."""
import copy
import gzip
import hashlib
import json
import math
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / 'research/launch-envelope-20261101.json'


def load(mid, side):
    with gzip.open(ROOT / 'research/traces' / (mid + '-' + side + '.jsonl.gz'), 'rt') as f:
        return [json.loads(l) for l in f]


def nearest(eye, box):
    return math.sqrt(sum(max(box[k]-eye[k], 0, eye[k]-box[k+3])**2 for k in range(3)))


def eye(s):
    return [s['posX'], s['posY'] + float(struct.unpack('f', struct.pack('f', 1.62))[0]), s['posZ']]


def bits(v):
    return struct.pack('>d', float(v))


class Kernel:
    def __init__(self, java, classes):
        self.p = subprocess.Popen([str(java), '-cp', classes, 'ExactTravelCheck', '--predict-tsv'],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                  text=True, bufsize=1)
        assert self.p.stdout.readline().startswith('status\t')

    def predict(self, s, e, overrides=None):
        fields = [s['pos'+a] for a in 'XYZ'] + [s['motion'+a] for a in 'XYZ']
        fields += [s['ground'], *s['box'], s['noClip'], s['isInWeb'], s['sneaking']]
        fields += [e['strafe'], e['forward'], s['rotationYaw'], e['ground_acceleration'],
                   e['air_acceleration'], e['floor_top_y'], e['floor_slipperiness'],
                   e['loaded_column'], e['loaded_column_after'], e['water'], e['lava'], e['ladder']]
        if overrides:
            fields[16:21] = overrides
        self.p.stdin.write('\t'.join(str(v).lower() if isinstance(v, bool) else str(v) for v in fields)+'\n')
        self.p.stdin.flush()
        row = self.p.stdout.readline().strip().split('\t')
        assert row[0] == 'OK', row
        after = copy.deepcopy(s)
        for k, v in zip(['pos'+a for a in 'XYZ'] + ['motion'+a for a in 'XYZ'], row[1:7]):
            after[k] = float(v)
        after['box'] = list(map(float, row[7:13]))
        for k, v in zip(['ground', 'isCollidedHorizontally', 'isCollidedVertically', 'isCollided'], row[13:17]):
            after[k] = v == 'true'
        return after

    def close(self):
        self.p.stdin.close()
        assert self.p.wait(timeout=10) == 0, self.p.stderr.read()


def analyze(mid, relative_hit, kernel):
    r = json.loads((ROOT/'runtime/results'/(mid+'.json')).read_text())
    a, b, server = load(mid, 'a'), load(mid, 'b'), load(mid, 'server')
    t0 = r['start']
    attacks = [e for e in server if e['type'] == 'science_server_attack'
               and e['score_before'] != e['score_after'] and e['target_before']['hurtResistantTime'] <= 10]
    hit = next(e for e in attacks if e['attacker'] == r['a'] and e['time']-t0 == relative_hit)
    next_fresh = min(e['time'] for e in attacks if e['time'] > hit['time'])
    next_accepted = min(e['time'] for e in server if e['type'] == 'science_server_attack'
                        and e['score_before'] != e['score_after'] and e['time'] > hit['time'])
    later_b_contacts = [e['time'] for e in b if e['type'] == 'science_click'
                        and e['selected_target'] and e['time'] > hit['time']]
    next_b_contact = min(later_b_contacts, default=next_fresh)
    # Even a rejected B attack would change B's local motion through contact
    # drag. Stop before that first contingent event, not only its server return.
    end = min(next_accepted, next_b_contact)
    contacts = [e for e in a if e['type'] == 'science_click' and e['selected_target']]
    packets = [e for e in server if e['type'] == 'replication_server_input'
               and e.get('player') == r['a'] and e.get('action') == 'ATTACK']
    assert abs(len(contacts)-len(packets)) <= 1
    receive = max((e for e in packets if e['monotonic_ns'] <= hit['monotonic_ns']),
                  key=lambda e: e['monotonic_ns'])
    click = contacts[packets.index(receive)]
    assert 0 <= receive['time']-click['time'] < 150
    moves = [e for e in a if e['type'] == 'science_move']
    first = next(e for e in moves if e['tick'] == click['tick'] and e['monotonic_ns'] > click['monotonic_ns'])
    moves = [e for e in moves if first['monotonic_ns'] <= e['monotonic_ns'] and e['time'] < end]
    initial = first['before']
    b_moves = [e for e in b if e['type'] == 'science_move']
    b_window_moves = [e for e in b_moves if first['time'] <= e['time'] < end]
    assert all(e['strafe'] == 0 and e['forward'] == .98 and e['before']['rotationYaw'] == 90
               for e in b_window_moves)
    b_initial = next(e['before'] for e in b_moves if e['time'] >= first['time'])
    b_prior_probe = max((e for e in b if e['type'] == 'reach_probe' and e['time'] < first['time']),
                        key=lambda e: e['time'])
    defender_reach = b_prior_probe['attack_reach']
    assert initial['ground'] and initial['posY'] == 4.0
    assert abs(initial['rotationYaw'] + 90) < .001, 'This first envelope is explicitly +X only'
    assert all(e['before']['ground'] and not e['before']['isCollidedHorizontally'] for e in moves)
    assert not [e for e in a if e['type'] == 'velocity_packet' and first['time'] <= e['time'] < end]
    parity = []
    paths = {'sprint_no_extra_clicks': [], 'walk_attribute_no_extra_clicks': [],
             'coast_no_extra_clicks': []}
    states = {k: copy.deepcopy(initial) for k in paths}
    for index, e in enumerate(moves):
        factual = kernel.predict(e['before'], e)
        values = ['pos'+v for v in 'XYZ'] + ['motion'+v for v in 'XYZ']
        assert all(bits(factual[k]) == bits(e['after'][k]) for k in values)
        assert all(bits(u) == bits(v) for u, v in zip(factual['box'], e['after']['box']))
        assert all(factual[k] == e['after'][k] for k in ['ground', 'isCollidedHorizontally', 'isCollidedVertically', 'isCollided'])
        parity.append(e['time']-t0)
        for name in paths:
            s = states[name]
            if index:
                for axis in 'XYZ':
                    if abs(s['motion'+axis]) < .005:
                        s['motion'+axis] = 0.0
            sprint = name.startswith('sprint')
            moving = not name.startswith('coast')
            # Native g-entry is after the initial required contact drag/cutoff.
            # Later optional contacts are suppressed; each later living cutoff
            # is explicit. Grounded max sprint attribute uses native float value.
            inp = [0.0, .98 if moving else 0.0, -90.0, .13000001 if sprint else .1,
                   e['air_acceleration'] if index == 0 else (.026 if sprint else .02)]
            s = kernel.predict(s, e, inp)
            states[name] = s
            paths[name].append({'time': e['time'], 'monotonic_ns': e['monotonic_ns'], 'state': s})
    probes = [e for e in a if e['type'] == 'reach_probe' and first['time'] <= e['time'] < end]
    rows = []
    for p in probes:
        row = {'t_ms': p['time']-t0, 'actual': nearest(p['native_eye'], p['expanded_box'])}
        for name, path in paths.items():
            eligible = [e for e in path if e['time'] < p['time']]
            # Equal wall-clock millisecond is ordering-ambiguous without a probe
            # monotonic timestamp; disclose and omit those samples for this path.
            if any(e['time'] == p['time'] for e in path):
                row[name] = None
                continue
            s = eligible[-1]['state'] if eligible else initial
            row[name] = nearest(eye(s), p['expanded_box'])
            row[name+'_x'] = s['posX']
            ey = eye(s);box = p['expanded_box']
            vertical = max(box[1]-ey[1], 0, ey[1]-box[4])
            lateral = max(box[2]-ey[2], 0, ey[2]-box[5])
            budget = 9-vertical*vertical-lateral*lateral
            translation = max(0.0, box[0]-ey[0]-math.sqrt(budget)) if budget >= 0 else None
            row[name+'_required_forward_translation'] = translation
            if name.startswith('sprint'):
                row['sprint_required_forward_translation'] = translation
        rows.append(row)
    border = float(struct.unpack('f', struct.pack('f', .1))[0])
    expanded = [v + (-border if i < 3 else border) for i, v in enumerate(click['target']['box'])]
    initial_range = nearest(eye(click['own']), expanded)
    # Audit every current-AABB mutation boundary on A's client thread. Native
    # getMouseOver(1.0) uses this current box, not frame partial interpolation.
    dense_states = {'actual': copy.deepcopy(click['own']),
                    **{name: copy.deepcopy(initial) for name in paths}}
    dense_box = list(click['target']['box'])
    dense_rows = []
    def dense_sample(e, phase):
        box = [v+(-border if i < 3 else border) for i,v in enumerate(dense_box)]
        row={'t_ms': e['time']-t0, 'event': e['type'], 'phase': phase,
             **{name: nearest(eye(s),box) for name,s in dense_states.items()}}
        for name,s in dense_states.items():
            ey=eye(s)
            vertical=max(box[1]-ey[1],0,ey[1]-box[4])
            lateral=max(box[2]-ey[2],0,ey[2]-box[5])
            budget=9-vertical*vertical-lateral*lateral
            row[name+'_forward_translation']=max(0,box[0]-ey[0]-math.sqrt(budget)) if budget>=0 else None
        dense_rows.append(row)
    dense_sample(click, 'initial')
    target_id = probes[0]['target_id']
    dense_events = [e for e in a if e.get('monotonic_ns',0) > click['monotonic_ns']
                    and e['time'] < end and e['type'] in
                    ('science_move','science_interpolation','replication_apply_before',
                     'replication_apply_after','science_click')]
    for e in sorted(dense_events,key=lambda e:e['monotonic_ns']):
        typ=e['type']
        if typ == 'science_move':
            assert e['before']['box'] == dense_states['actual']['box']
            dense_sample(e,'before')
            dense_states['actual']=e['after']
            for name,path in paths.items():
                dense_states[name]=next(p['state'] for p in path if p['monotonic_ns']==e['monotonic_ns'])
        elif typ == 'science_interpolation':
            assert e['before']['box'] == dense_box, 'Unaccounted rendered-target mutation'
            dense_sample(e,'before')
            dense_box=list(e['after']['box'])
        elif typ.startswith('replication_apply'):
            if e.get('packet',{}).get('entity_id') != target_id:
                continue
            if not e.get('state') or 'box' not in e['state']:
                continue
            if typ.endswith('before'):
                assert e['state']['box'] == dense_box, 'Unaccounted packet-time target mutation'
            dense_box=list(e['state']['box'])
        else:
            assert e['target']['box'] == dense_box, 'Unaccounted click-time target mutation'
        dense_sample(e,'after')
    dense_stats = {}
    for name in dense_states:
        high=max(dense_rows,key=lambda e:e[name]);low=min(dense_rows,key=lambda e:e[name])
        failed=[e for e in dense_rows if e[name]>3]
        dense_stats[name]={'minimum_range':low[name],'maximum_range':high[name],
                           'maximum_time_ms':high['t_ms'],'maximum_event':high['event'],
                           'samples_outside_3':sum(e[name]>3 for e in dense_rows),
                           'boundary_states':len(dense_rows),
                           'first_failure_ms':failed[0]['t_ms'] if failed else None,
                           'extra_forward_translation':max(e[name+'_forward_translation'] or 0 for e in dense_rows),
                           'extra_forward_translation_before_nominal_500ms':max(e[name+'_forward_translation'] or 0 for e in dense_rows if e['t_ms']<relative_hit+500)}
    server_ticks = [e for e in server if e['type']=='tick' and e['time']>hit['time']]
    ready = next(e for e in server_ticks if e['b']['hurtResistantTime']<=10)
    preceding = [e for e in server_ticks if e['time']<ready['time']]
    ready_ms=ready['time']-t0
    ready_state=max((e for e in dense_rows if e['t_ms']<ready_ms),key=lambda e:e['t_ms'])
    # Multiple ordered states may share a wall millisecond: take the final one.
    ready_state=[e for e in dense_rows if e['t_ms']==ready_state['t_ms']][-1]
    ready_evidence={'first_recorded_server_tick_hurt_resistant_le_10_ms':ready_ms,
                    'recorded_hurt_resistant_time':ready['b']['hurtResistantTime'],
                    'previous_recorded_tick_ms':preceding[-1]['time']-t0 if preceding else None,
                    'previous_recorded_hurt_resistant_time':preceding[-1]['b']['hurtResistantTime'] if preceding else None,
                    'own_ranges_at_first_recorded_ready_tick':{n:ready_state[n] for n in dense_states},
                    'note':'Observed server readiness bracket, not a local oracle or exact packet dispatch deadline.'}
    stats = {}
    for name in ['actual', *paths]:
        samples = [e for e in rows if e.get(name) is not None]
        high = max(samples, key=lambda e: e[name])
        low = min(samples, key=lambda e: e[name])
        stats[name] = {'minimum_range': low[name], 'maximum_range': high[name],
                       'maximum_time_ms': high['t_ms'], 'maximum_increase_from_required_contact': high[name]-initial_range,
                       'samples_outside_3': sum(e[name] > 3 for e in samples), 'samples': len(samples)}
        last = moves[-1]['after'] if name == 'actual' else paths[name][-1]['state']
        stats[name]['last_movement_ms'] = moves[-1]['time']-t0
        stats[name]['last_motion'] = [last['motion'+v] for v in 'XYZ']
        stats[name]['displacement_from_contact'] = [last['pos'+v]-initial['pos'+v] for v in 'XYZ']
        if name in paths:
            key = name+'_required_forward_translation'
            stats[name]['extra_forward_translation_for_sampled_upper_bound'] = max(e.get(key) or 0 for e in rows)
            stats[name]['extra_forward_translation_before_nominal_500ms'] = max(
                e.get(key) or 0 for e in rows if e['t_ms'] < relative_hit+500)
    # Conditional one-sided replica bound: a delayed, non-overshooting view of
    # a nondecreasing A cannot lie ahead of current A plus quantization slack.
    # Use B's LOCAL body position, never the server's stale copy. At coincident
    # wall milliseconds take the worst before/after positions, covering order.
    lower_rows = []
    halfwidth = initial['box'][3]-initial['posX'] + border
    for stamp in sorted({first['time'], *(e['time'] for e in moves),
                         *(e['time'] for e in b_window_moves)}):
        prior_b = [e for e in b_moves if e['time'] < stamp]
        bx = prior_b[-1]['after']['posX'] if prior_b else b_initial['posX']
        bx = min([bx] + [e[k]['posX'] for e in b_window_moves if e['time'] == stamp
                          for k in ('before', 'after')])
        row = {'t_ms': stamp-t0, 'b_local_x_lower': bx}
        for name, path in paths.items():
            prior = [e for e in path if e['time'] <= stamp]
            ax = prior[-1]['state']['posX'] if prior else initial['posX']
            row[name] = bx-ax-halfwidth-1/32-defender_reach
        lower_rows.append(row)
    for name in paths:
        worst = min(lower_rows, key=lambda e: e[name])
        failed = [e for e in lower_rows if e[name] <= 0]
        stats[name]['conservative_defender_horizontal_margin_min'] = worst[name]
        stats[name]['conservative_defender_margin_min_time_ms'] = worst['t_ms']
        stats[name]['first_uncertified_defender_exclusion_ms'] = failed[0]['t_ms'] if failed else None
    immunity_prefix = [e for e in rows if e['t_ms'] < relative_hit+500]
    failures = [e for e in rows if e.get('sprint_no_extra_clicks') is not None
                and e['sprint_no_extra_clicks'] > 3]
    return {'match': mid, 'launch_server_ms': relative_hit, 'source_click_ms': click['time']-t0,
            'g_entry_ms': first['time']-t0, 'stop_exclusive_ms': end-t0,
            'next_fresh_event_ms': next_fresh-t0,
            'next_accepted_server_attack_ms': next_accepted-t0,
            'next_recorded_b_contact_ms': next_b_contact-t0,
            'initial_local_nearest_range': initial_range,
            'initial_precontact_motion': [click['own']['motion'+v] for v in 'XYZ'],
            'initial_precontact_local_sprint': click['own']['sprint'],
            'initial_g_entry_motion_after_contact': [initial['motion'+v] for v in 'XYZ'],
            'server_sprint': hit['attacker_before']['sprint'],
            'target_height_at_server_attack': hit['target_before']['posY']-4,
            'velocity_packet': hit.get('velocity_packet'),
            'conditional_exact_travel_steps_passed': len(parity),
            'minimum_extra_forward_start_translation_for_sampled_upper_bound': max(
                (e.get('sprint_required_forward_translation') or 0) for e in rows),
            'headroom_deficit_before_nominal_500ms_next_fresh': max(
                (e.get('sprint_required_forward_translation') or 0) for e in immunity_prefix),
            'first_fastest_upper_boundary_failure_ms': failures[0]['t_ms'] if failures else None,
            'defender_bound_assumptions': {'defender_reach': defender_reach, 'quantization_slack': 1/32,
                'initial_b_rendered_a_x': b_prior_probe['target']['posX'],
                'initial_a_x': initial['posX'],
                'initial_view_not_ahead': b_prior_probe['target']['posX'] <= initial['posX']+1/32,
                'fixed_b_strafe_forward_yaw_verified': [0, .98, 90],
                'requires': 'Monotone A history, no forward replica overshoot beyond 1/32, no correction/teleport or earlier counterfactual contact. Failure is inconclusive; positive margin is a sufficient conditional horizontal exclusion bound.'},
            'stats': stats, 'samples': rows, 'defender_lower_bound_events': lower_rows,
            'dense_own_current_box_boundary_stats':dense_stats,
            'native_fresh_window_evidence':ready_evidence,
            'dense_own_current_box_boundaries':dense_rows}


def main():
    java = next(p for p in [Path('/opt/homebrew/opt/openjdk@17/bin/java'),
                            Path('/usr/local/opt/openjdk@17/bin/java'), Path(shutil.which('java') or '/usr/bin/java')]
                if p.exists()).resolve()
    sources = ['src/lab/ExactTravel.java', 'tests/ExactTravelCheck.java', 'research/launch-envelope.py']
    with tempfile.TemporaryDirectory(prefix='launch-envelope-', dir=ROOT/'research') as temp:
        subprocess.run([str(java.with_name('javac')), '-proc:none', '-d', temp,
                        str(ROOT/sources[0]), str(ROOT/sources[1])], check=True, capture_output=True)
        golden = subprocess.run([str(java), '-cp', temp, 'ExactTravelCheck'], check=True, capture_output=True, text=True)
        kernel = Kernel(java, temp)
        try:
            cases = [analyze(mid, t, kernel) for mid, t in [('m1170', 886), ('m1170', 1391), ('m1170', 2987), ('m1172', 3040)]]
        finally:
            kernel.close()
    report = {'scope': 'Fixed recorded B rendered boxes and exogenous native movement times; A travel uses validated ExactTravel, initialized once after factual required contact drag. No Minecraft/server launch. No B-view reconstruction or whole-duel claim.',
              'limits': 'Own local upper reach is audited at recorded current-box/own-position mutation boundaries with continuity assertions; sparse probes are retained separately. Defender horizontal exclusion is a conditional conservative bound at all movement events. Fastest path suppresses all later optional clicks; pure coast is a reference, not a slowest bound. Walk assumes native unsprinted attribute and does not establish that the unobserved double-tap timer permits transition to it. Stop strictly before next recorded accepted server hit or B local contact, whichever is earlier. Factual B trajectory may cease to be counterfactually valid if changed A motion enables an earlier B contact, crossing, changed aim, or a different attack. Same-millisecond movement/probe ambiguities omitted; defender margin uses worst same-millisecond before/after order. Initial translations are geometric diagnostics, not new native matches.',
              'golden_test': golden.stdout.strip(),
              'source_sha256': {s: hashlib.sha256((ROOT/s).read_bytes()).hexdigest() for s in sources},
              'cases': cases}
    DEST.write_text(json.dumps(report, indent=2)+'\n')
    for c in cases:
        print(c['match'], c['launch_server_ms'], 'initial', round(c['initial_local_nearest_range'],4),
              'headroom deficit', round(c['minimum_extra_forward_start_translation_for_sampled_upper_bound'],4),
              'maxima', {k: round(v['maximum_range'],4) for k,v in c['stats'].items()})


if __name__ == '__main__':
    main()
