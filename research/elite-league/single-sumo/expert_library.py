"""Fill the remaining per-opponent counter slots, reusing any existing exact-hash search."""
import sys
import time


from adversary import STORE, native_check, obtain
from learner import ROOT, write_json
from remote_league import OUT, read
from series_targeted import DEST as LIB, bank
from specialist_diagnosis import DEST, TRAIN_REPS

FOLDER=DEST/'expanded-library'


def register():
    spec=read(LIB/'protocol.json')
    world,_,_=bank('train',TRAIN_REPS)
    best=(world[...,0].numpy()==1).mean((0,1,2)).max(1)
    names=list(spec['field']);ordered=sorted(names,key=lambda n:(best[names.index(n)],n))
    initial=OUT/'weak-matchups/original-mixture/trainer.pt'
    import hashlib
    write_json(FOLDER/'protocol.json',dict(targets=ordered,
        field={n:{k:spec['field'][n][k] for k in ('sha256','reference')} for n in ordered},
        existing=read(DEST/'protocol.json')['dedicated'],warm=str(initial.relative_to(ROOT)),
        warm_sha256=hashlib.sha256(initial.read_bytes()).hexdigest(),
        design='Fill one 65,536-fight MLP PPO counter slot per distinct target hash, including failed searches. Reuse exact-hash results and any in-flight canonical search. Two remote training lanes start with the worst training matchup. Keep misses for the literal dedicated-routing diagnostic. The league promotion requirement remains more than 60% outright wins on the fixed 512-fight panel. Grade the final dedicated diagonal, train-only best response lookup, fixed actors and plain W-tap separately. Do not recruit a loss as a league champion.'),fixed=True)


def run(lane):
    spec=read(FOLDER/'protocol.json')
    todo=[name for name in spec['targets'] if name not in spec['existing']]
    for i,name in enumerate(todo):
        if i%2!=lane:continue
        target=spec['field'][name]
        result=obtain(target,ROOT/spec['warm'],782761+i*2917)
        write_json(FOLDER/'counters'/(name+'.json'),result,fixed=True)
    write_json(FOLDER/f'lane-{lane}.json',dict(complete=True),fixed=True)


def audit():
    spec=read(FOLDER/'protocol.json');todo=list(spec['targets'])
    while todo:
        for name in list(todo):
            target=spec['field'][name]
            if (STORE/target['sha256']/'result.json').exists():
                native_check(read(STORE/target['sha256']/'result.json'));todo.remove(name)
        if todo:time.sleep(30)


if __name__=='__main__':
    if sys.argv[1]=='register':register()
    elif sys.argv[1]=='native':audit()
    else:run(int(sys.argv[1]))
