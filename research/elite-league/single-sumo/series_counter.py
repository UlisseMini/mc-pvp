"""Train a legal 65k response when the eight-expert library cannot win the series learner's weakest matchup."""
from adversary import obtain,native_check
from learner import write_json
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


if __name__=='__main__':run()
