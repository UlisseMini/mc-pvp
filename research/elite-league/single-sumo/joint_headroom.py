"""Measure whether joint ring-out training produced complementary physical policies."""
import hashlib
import sys
import time

import numpy as np

from expert_population import SEED, STUDY
from joint_experts import DEST as JOINT
from learner import CLOCKS, ROOT, RTTS, Worker, write_json
from remote_league import OUT, read
from series_eval import tally
from series_targeted import DEST as LIB
from specialist_ablation import bootstrap
from specialist_diagnosis import fixture_inputs

DEST = JOINT / 'expert-headroom'
STEPS = (256, 410, 1024)


def register():
    write_json(DEST/'protocol.json', dict(
        updates=STEPS, mode='joint-broad', train_replicates=4, development_replicates=4,
        test='All three MLPs started from the same generalist. Test each trained MLP against each opponent regardless of what the deployed router would choose. Fit best fixed, ping-only, ID-only and ID-and-ping expert choices solely on training fixtures. Measure these choices and the original learned route on identical development games. Verify every chosen action reproduces its original physical outcome and count distinct controls. Privileged choices measure the specialization available to this specific library. Include an equal-fixture comparison with the original three target-specific experts and plain W-tap.',
    ), fixed=True)


def panel(worker, split, refs, rivals):
    shape = (len(CLOCKS[split]), 4, 10, len(rivals), len(refs))
    scores = np.empty(shape, np.float32)
    context = np.empty((*shape[:3], 3), np.int32)
    for i, clock in enumerate(CLOCKS[split]):
        for r in range(4):
            plan = []
            for g, (seat, a, b, x, y) in enumerate(fixture_inputs(split, r, i)):
                context[i,r,g] = seat,a,b
                plan.extend([actor, foe, clock, 'ab'[seat], a,b,x,y] for foe in rivals for actor in refs)
            rows = [w for k in range(0, len(plan), 30) for w, _, _ in worker.run(plan[k:k+30], False)]
            scores[i,r] = np.reshape(rows, shape[2:])
    return scores, context


def ping_bin(c):
    return 2*(c[...,1]>=70) + (c[...,2]>=70)


def grade(step):
    source = JOINT/'joint-broad'
    prefix = f'u{step:03}'
    while not (source/(prefix+'-router.pt')).exists():
        time.sleep(10)
    spec = read(LIB/'protocol.json')
    names = read(STUDY/'protocol.json')['small']
    rivals = [spec['field'][foe]['reference'] for foe in names]
    actors = [str((source/f'{prefix}-expert-{i}.net').relative_to(ROOT)) for i in range(3)]
    worker = Worker(current_runtime=True)
    try:
        train, train_c = panel(worker, 'train', actors, rivals)
        dev, context = panel(worker, 'dev', actors+['wtap'], rivals)
    finally:
        worker.close()
    train_bins = ping_bin(train_c)
    table = np.stack([(train[train_bins==b]==1).mean(axis=0) for b in range(4)])
    bins = ping_bin(context)
    values = dev[..., :3]
    with np.load(source/(prefix+'.npz')) as z:
        shape = (len(CLOCKS['dev']), 4, len(names), 10)
        actual = z['wins'].reshape(shape).swapaxes(2,3)
        choices = z['actions'].reshape(shape).swapaxes(2,3)
    def choose(a):
        return np.take_along_axis(values, np.broadcast_to(a, actual.shape)[...,None], axis=-1)[...,0]
    if not np.array_equal(actual, choose(choices)):
        raise ValueError('Reconstructed joints disagree on actual physical outcomes')
    arms = dict(learned=actual, fixed=choose(table.mean((0,1)).argmax()),
                ping=choose(table.mean(1)[bins].argmax(-1)[...,None]),
                identity=choose(table.mean(0).argmax(-1)), identity_ping=choose(table[bins].argmax(-1)), wtap=dev[...,-1])
    controls = read(STUDY/'small'/f'seed-{SEED}'/'dev-u512.json')['scores']
    if tally(arms['wtap']) != controls['wtap']['games']:
        raise ValueError('W-tap differs from the original fixture')
    def describe(w):
        return dict(games=tally(w), opponents={f:tally(w[...,j]) for j,f in enumerate(names)},
            seats={s:tally(w[context[...,0]==i]) for i,s in enumerate('ab')},
            rtts={f'{a}/{b}':tally(w[(context[...,1]==a)&(context[...,2]==b)]) for a,b in RTTS})
    scores = {k:describe(w) for k,w in arms.items()}
    gaps = {}
    for k in ('fixed','ping','learned'):
        d = ((arms['identity_ping']==1).astype(float) - (arms[k]==1)).mean((2,3))
        gaps[k] = dict(gain=float(d.mean()), conditional_95=bootstrap(d))
    write_json(DEST/(prefix+'.json'), dict(
        scores=scores, identity_ping_choices=table.argmax(-1).tolist(), differences=gaps,
        physical_counterfactual=[describe(values[...,i]) for i in range(3)], opponents=names,
        hashes=[hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in actors],
        from_separately_trained_experts={k:controls[k]['games'] for k in ('memory','literal-dedicated','known-id-lookup','wtap')},
    ), fixed=True)


if __name__=='__main__':
    if sys.argv[1]=='register':
        register()
    elif sys.argv[1]=='start':
        from remote_jobs import start
        start('ep-joint-headroom','joint_headroom.py',['run'],DEST.relative_to(OUT)/'u1024.json')
    else:
        for step in STEPS:
            if not (DEST/f'u{step:03}.json').exists():
                grade(step)
