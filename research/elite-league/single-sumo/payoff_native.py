"""Verify the fitted payoff router as a live Minecraft actor and audit its two training matchups."""
import hashlib
import subprocess
import time

from learner import BUILD,ROOT,write_json
from matched_native import assess
from remote_import import HOST, OPTIONS, REMOTE, available, wait_for, retrieve
from remote_league import OUT
from remote_round import entrants
from payoff_pair import FOES,OPENER
from adaptive_router import runtime,trace_parity

FOLDER=OUT/'payoff-pair'


def run():
    wait_for(['payoff-pair/protocol.json','payoff-pair/development.json'])
    # A training-only worker launched before the exporter was installed may have just finished.
    while not available('payoff-pair/parity.json'):
        subprocess.run(['ssh',*OPTIONS,HOST,'cd '+REMOTE+' && python3 research/elite-league/single-sumo/remote_jobs.py'],check=True)
        time.sleep(20)
    choices=('history','response-A','response-B')
    retrieve(['payoff-pair/'+s for s in ('gru.pt','mlp.pt','parity.json',*['actors/'+n+'.net' for n in choices])])
    pin=runtime();bridge=BUILD/pin['forge']['jar']
    if hashlib.sha256(bridge.read_bytes()).hexdigest()!=pin['forge']['sha256']:raise ValueError('Registered router bridge changed')
    field,_,_=entrants()
    arms={};hashes={}
    for label in choices:
        path=FOLDER/'actors'/(label+'.net')
        hashes[label]=trace_parity(path,pin)
        arms[label]=dict(reference=str(path.relative_to(ROOT)),sha256=hashlib.sha256(path.read_bytes()).hexdigest())
    write_json(FOLDER/'forge-parity.json',dict(actors=hashes,bridge=pin['forge']['sha256']),fixed=True)
    arms.update({'keep-opener':field[OPENER],'wtap':field['wtap']})
    assess(FOLDER/'native','payoff-pair-001',arms,{n:field[n] for n in (*FOES,'wtap')},bridge_override=bridge)


if __name__=='__main__':run()
