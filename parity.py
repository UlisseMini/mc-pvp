#!/usr/bin/env python3
"""Zero-tolerance conditional native parity checks and fresh diagnostic fixtures.

No search, parameter fitting, or controller access to diagnostic state.
"""
import argparse
import asyncio
import collections
import gzip
import hashlib
import json
import struct
import subprocess
import tempfile
import time

from lab import ROOT, RUNTIME, BUILD, JAVA, JAVAC
from lab_runtime import properties
from experiments import match, save_policy
from science import OUT, client_events, server_events


def bits(value):
    return struct.pack('>d', float(value))


def exact_travel(result):
    source_paths=['src/lab/ExactTravel.java','tests/ExactTravelCheck.java','parity.py']
    source_hashes={p:hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in source_paths}
    references = [(side, e) for side in ('a', 'b') for e in client_events(result, side)
                  if e['type'] == 'science_move']
    queries = []
    for side, e in references:
        s = e['before']
        q = [s['pos'+a] for a in 'XYZ'] + [s['motion'+a] for a in 'XYZ']
        q += [s['ground'], *s['box'], s['noClip'], s['isInWeb'], s['sneaking'],
              e['strafe'], e['forward'], s['rotationYaw'], e['ground_acceleration'],
              e['air_acceleration'], e['floor_top_y'], e['floor_slipperiness'],
              e['loaded_column'], e['loaded_column_after'], e['water'], e['lava'], e['ladder']]
        queries.append('\t'.join(str(v).lower() if isinstance(v, bool) else str(v) for v in q))
    with tempfile.TemporaryDirectory(prefix='exact-travel-', dir=RUNTIME/'tmp') as temp:
        subprocess.run([str(JAVAC), '-proc:none', '-d', temp, str(ROOT/'src/lab/ExactTravel.java'),
                        str(ROOT/'tests/ExactTravelCheck.java')], check=True, capture_output=True, timeout=30)
        run = subprocess.run([str(JAVA), '-cp', temp, 'ExactTravelCheck', '--predict-tsv'],
                             input='\n'.join(queries)+'\n', text=True, capture_output=True, check=True, timeout=30)
    lines = run.stdout.splitlines()[1:]
    if len(lines) != len(references):
        raise ValueError('Missing exact-travel predictions')
    counts = collections.Counter(); failures = collections.Counter(); examples = []
    names = ['pos'+a for a in 'XYZ'] + ['motion'+a for a in 'XYZ'] + ['box.'+k for k in ('minX','minY','minZ','maxX','maxY','maxZ')]
    flags = ['ground','isCollidedHorizontally','isCollidedVertically','isCollided']
    for (side, e), line in zip(references, lines):
        counts['samples'] += 1
        f = line.split('\t')
        if f[0] != 'OK':
            failures['unsupported'] += 1
            if len(examples) < 8: examples.append({'side':side, 'time':e['time'], 'reason':line})
            continue
        after = e['after']
        actual = [after[k] for k in names[:6]] + after['box']
        diffs = {}
        for key, predicted, observed in zip(names, map(float, f[1:13]), actual):
            counts['double_comparisons'] += 1
            if bits(predicted) != bits(observed):
                failures[key] += 1
                diffs[key] = {'predicted_hex':predicted.hex(), 'native_hex':float(observed).hex(), 'absolute_error':abs(predicted-observed)}
        for key, predicted in zip(flags, f[13:17]):
            counts['boolean_comparisons'] += 1
            if (predicted == 'true') != after[key]: failures[key] += 1; diffs[key] = [predicted, after[key]]
        if diffs:
            failures['steps'] += 1
            if len(examples) < 8: examples.append({'side':side,'time':e['time'],'differences':diffs,'native':e})
        else: counts['bit_exact_steps'] += 1
        counts['ground_entry' if e['before']['ground'] else 'air_entry'] += 1
        if not e['before']['ground'] and after['ground']: counts['landings'] += 1
        if e['before']['ground'] and not after['ground']: counts['takeoffs'] += 1
    return {'contract':'Measured native g(FF) entry to return: 12 double bit patterns and 4 collision flags. Zero epsilon; unsupported rows fail.',
            'validator_source_sha256':source_hashes,
            'counts':dict(counts),'failures':dict(failures),'examples':examples,
            'passed':bool(references) and not failures,
            'limits':'Conditional travel only. Preceding input, attacks, packets, later pushing and full duel composition are not validated by this check.'}


def archive(result):
    for side in ('a','b'): client_events(result,side)
    dest=OUT/'traces'/f'{result["id"]}-server.jsonl.gz'
    if not dest.exists():
        raw=server_events(result['id'])
        with gzip.open(dest,'wt') as stream:
            for e in raw: stream.write(json.dumps(e,separators=(',',':'))+'\n')


async def run(name,smoke=False):
    dest=OUT/(name+'.json')
    if dest.exists(): raise ValueError('Choose a fresh evidence name')
    templates={'parity-hold':{'kind':'hold','click_mode':'none'},
               'parity-still':{'kind':'standstill','click_mode':'none'},
               'parity-motion':{'kind':'scripted','click_mode':'none','input_script':'0:1:0:0|1500:0:0:0|2500:1:1:0|4000:0:0:0|5000:1:1:-1|6500:1:1:1'},
               'parity-wtap':properties(ROOT/'policies/wtap.properties')}
    for policy,p in templates.items():
        save_policy(policy,{**p,'science_trace':1,'science_network_trace':1,'description':'Exact parity diagnostic: '+policy})
    fixtures=[('parity-hold','parity-hold',20,100,8000,'Asymmetric no-click crossing: tracker and packet ordering'),
              ('parity-motion','parity-still',0,0,8000,'Walk, sprint, coast and strafe float arithmetic'),
              ('parity-wtap','parity-hold',20,100,20000,'Knockback takeoff, air travel and landing'),
              ('parity-hold','parity-hold',0,0,8000,'Zero-added-RTT replication holdout'),
              ('parity-hold','parity-hold',100,20,8000,'Reversed-asymmetry replication holdout')]
    if smoke:fixtures=[fixtures[1]]
    digest=lambda:hashlib.sha256((BUILD/'lab-agent.jar').read_bytes()).hexdigest()
    source_paths=['src/lab/ExactTravel.java','tests/ExactTravelCheck.java','parity.py']
    sources=lambda:{p:hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in source_paths}
    plan={'created_unix':time.time(),'stage':'preregistered','agent_sha256':digest(),'validator_source_sha256':sources(),
          'policies':{p:properties(ROOT/'policies'/f'{p}.properties') for p in templates},
          'fixtures':fixtures,'gate':'Every supported travel state double bit-for-bit and every collision flag equal. No excluded numeric boundary.',
          'scope':'Component parity and replication diagnosis; no claim of whole-fight parity.','results':[]}
    def checkpoint():
        tmp=dest.with_suffix('.json.tmp');tmp.write_text(json.dumps(plan,indent=2)+'\n');tmp.replace(dest)
    checkpoint()
    for a,b,pa,pb,duration,purpose in fixtures:
        assert digest()==plan['agent_sha256'],'Build changed during fixture suite'
        assert sources()==plan['validator_source_sha256'],'Validator source changed during fixture suite'
        for p in (a,b): assert properties(ROOT/'policies'/f'{p}.properties')==plan['policies'][p]
        result=await match(a,b,pa,pb,duration_ms=duration)
        archive(result)
        check=exact_travel(result)
        plan['results'].append({'purpose':purpose,'game':result,'exact_travel':check})
        plan['stage']='running';checkpoint()
        print(json.dumps({'match':result['id'],'exact_travel':check}),flush=True)
    plan['stage']='complete';checkpoint()


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('command',choices=['run','validate']);p.add_argument('name');p.add_argument('--smoke',action='store_true');args=p.parse_args()
    if args.command=='run': asyncio.run(run(args.name,args.smoke))
    else:
        result=json.loads((RUNTIME/'results'/f'{args.name}.json').read_text())
        print(json.dumps(exact_travel(result),indent=2))
