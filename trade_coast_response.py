#!/usr/bin/env python3
"""Focused impulse-response calculation, NOT a closed-loop duel counterfactual.

For m0404's marginal return, change only A's ordinary W/Ctrl travel inputs.
Keep recorded incoming/contact/aim boundary events and replication schedule.
Propagate the changed quantized position through the ONE interpolation update
that can affect B's recorded 13250ms contact. This tests the size/timing of a
proposed fix, not whether all endogenous events would stay unchanged in Minecraft.
"""
import hashlib
import json
import math
import struct
import subprocess
import tempfile

from lab import ROOT, RUNTIME, JAVA, JAVAC
from local_replay import compare, query
from trade_grounding import read


def f32(x):return struct.unpack('>f',struct.pack('>f',x))[0]


def nearest(eye,box):
    return math.sqrt(sum(max(box[i]-eye[i],eye[i]-box[i+3],0)**2 for i in range(3)))


def run():
    dest=ROOT/'research'/'trade-coast-response.json'
    if dest.exists():raise ValueError('Evidence output already exists')
    server,sh=read('m0404','server');a,ah=read('m0404','a');b,bh=read('m0404','b')
    game=next(e['match'] for e in server if e['type']=='start');start=game['start']
    relative=lambda e:e['time']-start
    click=next(e for e in b if e['type']=='science_click' and relative(e)==13250)
    own_id=next(e['packet']['entity_id'] for e in server if e['type']=='replication_send'
                and e['packet']['kind']=='hm' and e['target']==game['a'])
    native=[e for e in a if 12801<=relative(e)<=13250]
    qs,refs=query(native,own_id)
    # The only interpolation between the 13165 position goal and the click.
    interp=next(e for e in b if e['type']=='science_interpolation' and relative(e)==13201)
    packet=next(e for e in b if e['type']=='replication_apply_after' and relative(e)==13165
                and e['packet']['kind'].startswith('gv'))
    source_move=next(e for e in native if e['type']=='science_move' and relative(e)==13100)
    goal=packet['interpolation_goal'];old_position=[interp['before']['pos'+k] for k in 'XYZ']
    observed=[click['target']['pos'+k] for k in 'XYZ']
    assert interp['steps']==3
    assert goal==[math.floor(source_move['after']['pos'+k]*32)/32 for k in 'XYZ']
    baseline_view=[v+(g-v)/3 for v,g in zip(old_position,goal)]
    assert baseline_view==observed, 'Recorded view does not equal claimed interpolation chain'
    # Newer packet is present but its goal has not yet been interpolated at click.
    newer=next(e for e in b if e['type']=='replication_apply_after' and relative(e)==13213
               and e['packet']['kind'].startswith('gv'))
    assert newer['interpolation_goal']!=goal
    for e in b:
        if e['type']=='science_interpolation' and interp['monotonic_ns']<e['monotonic_ns']<click['monotonic_ns']:
            raise ValueError('Unaccounted interpolation before target click')
    eye=[click['own']['posX'],click['own']['posY']+f32(1.62),click['own']['posZ']]
    box=[v+(-1 if i<3 else 1)*f32(.1) for i,v in enumerate(click['target']['box'])]
    sources=['trade_coast_response.py','local_replay.py','tests/ExactLocalReplay.java','src/lab/ExactTravel.java']
    result={'match':'m0404','trace_sha256':{'a':ah,'b':bh,'server':sh},
        'source_sha256':{p:hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in sources},
        'scope':'Boundary-conditioned impulse response. Recorded S12/contact sprint flags/aim and packet schedule are held fixed. This is not a native intervention or a proof of control impossibility.',
        'baseline_chain':{'A_source_move_ms':13100,'B_goal_apply_ms':13165,'B_interpolation_ms':13201,
            'B_click_ms':13250,'newer_goal_not_yet_used_ms':13213,'interpolation_fraction':1/3,
            'B_closest_box_distance':nearest(eye,box)},'variants':[]}
    with tempfile.TemporaryDirectory(prefix='coast-response-',dir=RUNTIME/'tmp') as temp:
        subprocess.run([str(JAVAC),'-proc:none','-d',temp,str(ROOT/'tests/ExactLocalReplay.java'),str(ROOT/'src/lab/ExactTravel.java')],
                       capture_output=True,check=True,timeout=30)
        for name,releases in [('unchanged',set()),('one_extra_coast_tick',{13003}),('coast_through_return_source',{13003,13056,13100})]:
            inputs=list(qs);previous_changed_release=False
            for i,(kind,e,_) in enumerate(refs):
                if kind!='move':continue
                fields=inputs[i].split('\t')
                if previous_changed_release:fields[5]='0.02'  # previous update's air-speed cache
                changed=relative(e) in releases and e['forward']!=0
                if relative(e) in releases:fields[2]='0.0';fields[4]='0.1'
                previous_changed_release=changed
                inputs[i]='\t'.join(fields)
            proc=subprocess.run([str(JAVA),'-cp',temp,'ExactLocalReplay'],input='\n'.join(inputs)+'\n',
                                capture_output=True,text=True,check=True,timeout=30)
            lines=proc.stdout.splitlines()
            if not releases:
                result['baseline_replay']=compare(lines,refs,start)
                assert result['baseline_replay']['passed'], 'Original trajectory failed exact replay'
            by_time={relative(e):list(map(float,line.split('\t')[:3])) for line,(kind,e,_) in zip(lines,refs) if kind=='move'}
            changed_goal=[math.floor(p*32)/32 for p in by_time[13100]]
            changed_view=[v+(g-v)/3 for v,g in zip(old_position,changed_goal)]
            shift=[x-y for x,y in zip(changed_view,baseline_view)]
            changed_box=[v+shift[i%3] for i,v in enumerate(box)]
            distance=nearest(eye,changed_box)
            result['variants'].append({'name':name,'released_movement_ms':sorted(releases),
                'A_position_at_13100':by_time[13100],'A_quantized_goal':changed_goal,
                'B_rendered_A_shift':shift,'B_closest_box_distance':distance,
                'denies_even_perfect_reaim_in_fixed_boundary_projection':distance>3})
    result['interpretation']='Neither tested late-coast response denies perfect re-aim under the recorded boundary schedule. A tiny cached-ray margin is misleading: the nearest-box reach margin and delayed/interpolated movement response matter. Regenerating packet times, aim, sprint metadata and every contact is required before treating any branch as a real duel prediction.'
    dest.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))


if __name__=='__main__':run()
