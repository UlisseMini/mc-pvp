"""Train a legal 65k response when the eight-expert library cannot win the series learner's weakest matchup."""
from adversary import obtain,native_check
from learner import ROOT,write_json
from remote_league import OUT,read
from series_bank import FOLDER


def run():
    evaluation=read(FOLDER/'dev/evaluation-02.json')
    rivals=evaluation['scores']['memory-9']['opponents']
    weakest=min(rivals,key=lambda n:(rivals[n]['series'][0],rivals[n]['ringouts'][0],n))
    source=read(FOLDER/'protocol.json')['field'][weakest]
    target={k:source[k] for k in ('reference','sha256')}
    dest=FOLDER/'weakest-response'
    write_json(dest/'protocol.json',dict(target=weakest,policy=target,
        reason='At the first 69120-fight checkpoint, every available fixed response won fewer than half of its duels against this opponent. Run the usual 65536-fight adversary procedure on this exact opponent hash. Keep the original eight-expert tournament frozen.',
        best_fixed_ringouts=max(v['opponents'][weakest]['ringouts'][0] for k,v in evaluation['scores'].items() if k.startswith('fixed-')),
        ringouts_per_opponent=sum(rivals[weakest]['ringouts'])),fixed=True)
    result=obtain(target,OUT/'weak-matchups/original-mixture/trainer.pt',371016)
    write_json(dest/'result.json',result,fixed=True)
    native_check(result)


def publish_response():
    import time
    from adaptive_router import runtime,trace_parity
    from native_league import publish
    dest=FOLDER/'weakest-response'
    while not (dest/'result.json').exists():time.sleep(10)
    verdict=read(dest/'result.json')
    if not verdict['confirmed']:return
    model=verdict['policy']
    name=publish('sumo-counter-g1-reply-64k',model,'7 CPS MLP trained for 65536 fights against the G1 reply, the weakest matchup of the eight-expert series bot')
    pin=runtime()
    write_json(dest/'publication.json',dict(name=name,policy=model,target=verdict['target'],bridge=pin['forge']['sha256'],
        action_trace=trace_parity(ROOT/model['reference'],pin)),fixed=True)


if __name__=='__main__':
    import sys
    if sys.argv[1:]==['publish']:publish_response()
    else:run()
