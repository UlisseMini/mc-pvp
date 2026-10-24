#!/usr/bin/env python3
"""One separately registered equal-CPS control after the original cohort."""
import argparse
import asyncio
import hashlib
import json
import time
from pathlib import Path

from lab import ROOT, BUILD, RUNTIME
from lab_runtime import properties
from experiments import match
from parity import archive
from burst_science import diagnostic
from trade_science import analyze
from cps_science import delivery


async def run(name):
    source=ROOT/'research/cps-wtap-comparison-v1.json'
    original=json.loads(source.read_text())
    assert len(original['results'])==len(original['fixtures'])
    path=ROOT/'research'/(name+'.json')
    if path.exists():raise ValueError('Choose a fresh evidence name')
    for filename,digest in original['source_sha256'].items():
        assert hashlib.sha256((ROOT/filename).read_bytes()).hexdigest()==digest,filename
    assert hashlib.sha256((BUILD/'lab-agent.jar').read_bytes()).hexdigest()==original['agent_sha256']
    policy='trace-cps-wtap-10'
    assert properties(ROOT/'policies'/(policy+'.properties'))==original['policies'][policy]
    assert json.loads((RUNTIME/'manager.json').read_text())['frame_sleep_ms']==1
    plan={'stage':'preregistered','created_unix':time.time(),
          'reason':'Final original 10-vs-10 control exceeded the 250ms maximum cadence gap. Preserve it as excluded; run exactly one separate recovery control with no other benchmark match. This is not a replacement or a high-CPS performance game.',
          'source':str(source.relative_to(ROOT)),'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
          'runner_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
          'agent_sha256':original['agent_sha256'],'policy':original['policies'][policy],
          'rtt_ms':[20,20],'frame_sleep_ms':1,'concurrency':1,
          'timing_gate':original['timing_gate'],'results':[]}
    def save():path.write_text(json.dumps(plan,indent=2)+'\n')
    save()
    try:
        game=await match(policy,policy,20,20)
        archive(game)
        diag=diagnostic(game);inputs=delivery(game)
        for side in ('a','b'):
            if game['client_frame_sleep_ms'].get(side)!=1 or inputs[side]['physical_input_cps_during_click_span']<9:
                diag['issues'].append(side+': frame setting or delivery failed');diag['passed']=False
        plan['results'].append({'game':game,'diagnostic':diag,'exchanges':analyze(game),'delivery':inputs})
        plan['stage']='complete' if diag['passed'] else 'failed'
        save()
        print(json.dumps({'id':game['id'],'passed':diag['passed'],'issues':diag['issues'],
                          'fresh':[plan['results'][0]['exchanges']['sides'][s]['counts']['fresh'] for s in ('a','b')]}),flush=True)
    except BaseException as error:
        plan['stage']='incomplete';plan['error']=str(error);save();raise


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('name')
    asyncio.run(run(parser.parse_args().name))
