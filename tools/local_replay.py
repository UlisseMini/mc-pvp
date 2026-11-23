#!/usr/bin/env python3
"""Exact, unreset local trajectories conditional on recorded boundary events.

NOT a duel predictor or legal bot input. Native contact choices, sprint flags,
aim, acceleration caches and incoming packet payloads are external boundary
inputs. Counterfactual controls invalidate those inputs and are not evaluated.
"""
import argparse
import collections
import hashlib
import json
import struct
import subprocess
import tempfile

from lab import ROOT, RUNTIME, JAVA, JAVAC
from tools.trade_grounding import read

NUMBERS = ['pos'+a for a in 'XYZ'] + ['motion'+a for a in 'XYZ']
FLAGS = ['ground', 'isCollidedHorizontally', 'isCollidedVertically', 'isCollided']
NAMES = NUMBERS + ['box.'+a for a in ('minX','minY','minZ','maxX','maxY','maxZ')] + FLAGS


def row(values):
    return '\t'.join(str(x).lower() if isinstance(x,bool) else str(x) for x in values)


def query(native, own_id):
    events=sorted((e for e in native if 'monotonic_ns' in e),key=lambda e:e['monotonic_ns'])
    first=next(e for e in events if e['type']=='science_move')
    s=first['before']
    queries=[row(['INIT',*[s[k] for k in NUMBERS],*s['box'],*[s[k] for k in FLAGS],s['noClip'],s['isInWeb'],s['sneaking']])]
    references=[('init',first,s)]
    for e in events:
        if e['monotonic_ns']<first['monotonic_ns']:continue
        typ=e['type']
        if typ=='science_move':
            queries.append(row(['MOVE',e['strafe'],e['forward'],e['before']['rotationYaw'],e['ground_acceleration'],
                e['air_acceleration'],e['floor_top_y'],e['floor_slipperiness'],e['loaded_column'],
                e['loaded_column_after'],e['water'],e['lava'],e['ladder']]))
            references.append(('move',e,e['after']))
        elif typ=='engine_attack':
            queries.append(row(['CONTACT',e['sprint_before']]))
            references.append(('contact',e,dict(motionX=e['vx_after'],motionZ=e['vz_after'])))
        elif typ=='replication_apply_after' and e['packet']['kind']=='hm' and e['packet']['entity_id']==own_id:
            queries.append(row(['S12',*[e['packet']['motion'+a]/8000 for a in 'XYZ']]))
            references.append(('s12',e,e['state']))
    return queries,references


def compare(lines,references,start):
    if len(lines)!=len(references):raise ValueError('Missing replay output')
    counts=collections.Counter();failures=collections.Counter();first_failure=None;exact_prefix_moves=0
    for line,(kind,event,native) in zip(lines,references):
        vals=line.split('\t');pred=dict(zip(NAMES,[*map(float,vals[:12]),*[v=='true' for v in vals[12:]]]))
        observed={k:v for k,v in native.items() if k in NAMES}
        if 'box' in native:observed.update(zip(NAMES[6:12],native['box']))
        bad={}
        for k,v in observed.items():
            counts['boolean_checks' if k in FLAGS else 'double_bit_checks']+=1
            equal=pred[k]==v if k in FLAGS else struct.pack('>d',pred[k])==struct.pack('>d',v)
            if not equal:bad[k]={'predicted':pred[k],'native':v};failures[k]+=1
        counts[kind+'_events']+=1
        if bad:
            failures['events']+=1
            if first_failure is None:first_failure={'kind':kind,'time_ms':event['time']-start,'differences':bad}
        elif first_failure is None and kind=='move':exact_prefix_moves+=1
    return dict(counts=dict(counts),failures=dict(failures),first_failure=first_failure,
                exact_prefix_movement_steps=exact_prefix_moves,passed=not failures)


def run(matches,output):
    destination=ROOT/'research'/output
    if destination.exists():raise ValueError('Choose a fresh output filename')
    source=['local_replay.py','tests/ExactLocalReplay.java','src/lab/ExactTravel.java']
    result={'contract':'Initialize local position/motion/box once per client. Replay incoming S12, contact slowdown, cutoff and travel. Compare every subsequent observed state without correction, with zero bit tolerance.',
            'conditional_inputs':['native S12 payload and application order','native entity-contact selection and local sprint at each contact','native movement input, yaw and acceleration getters','native travel environment'],
            'limits':'These boundary inputs depend on both players. Passing is not autonomous duel parity and does not authorize frozen-boundary counterfactual search.',
            'source_sha256':{p:hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in source},'results':[]}
    with tempfile.TemporaryDirectory(prefix='local-replay-',dir=RUNTIME/'tmp') as temp:
        subprocess.run([str(JAVAC),'-proc:none','-d',temp,*[str(ROOT/p) for p in source[1:]]],check=True,capture_output=True,timeout=30)
        for match in matches:
            server,server_hash=read(match,'server');game=next(e['match'] for e in server if e['type']=='start')
            for side in ('a','b'):
                ids={e['packet']['entity_id'] for e in server if e['type']=='replication_send'
                     and e['packet']['kind']=='hm' and e.get('target')==game[side]}
                if len(ids)!=1:raise ValueError('Cannot uniquely identify own S12 stream')
                native,digest=read(match,side);queries,refs=query(native,next(iter(ids)))
                proc=subprocess.run([str(JAVA),'-cp',temp,'ExactLocalReplay'],input='\n'.join(queries)+'\n',
                                    text=True,capture_output=True,check=True,timeout=30)
                checked=compare(proc.stdout.splitlines(),refs,game['start'])
                item=dict(match=match,side=side,trace_sha256=digest,server_trace_sha256=server_hash,**checked)
                result['results'].append(item);print(json.dumps(item))
    result['passed']=all(r['passed'] for r in result['results'])
    destination.write_text(json.dumps(result,indent=2)+'\n')
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('matches',nargs='+');p.add_argument('--output',required=True)
    a=p.parse_args();run(a.matches,a.output)
