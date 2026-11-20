"""Learn the recurrent expert choice from ring-outs on the complete frozen opponent field."""
import io
import sys
import time

import numpy as np
import torch
from torch.nn import functional as F

from expert_population import Actor, SEED, STUDY, fit, play, training_opponents
from learner import CLOCKS, RTTS, Worker, atomic, write_json
from remote_league import read
from reward_router_debug import sample
from series_eval import series, tally
from series_hidden import donor_rows
from series_targeted import DEST as LIB, bank
from series_trace import CHANNELS
from specialist_ablation import bootstrap
from specialist_diagnosis import DATA, fixture_inputs, signal
from specialist_router import visible_feedback

DEST = STUDY / 'full-field'
STEPS = (512, 2048)


def register():
    spec = read(LIB / 'protocol.json')
    write_json(DEST / 'protocol.json', dict(
        field=spec['field'], experts=spec['experts'], seeds=[SEED, SEED + 1],
        reason='The three-counter reward-only router nearly matched true-ID routing after one game on both MMC floors, yet its uninformed opener lost to the broad control. Test whether the same principle works across the entire field with generalists available from the first game.',
        training='Train on all 27 fixed opponents, with two generalists and seven dedicated control MLPs available in each of ten fights. No opponent labels or auxiliary objectives. The recurrent PPO or recurrent discrete-action win predictor gets only the chosen expert, its actual public motion and feedback, and the outright ring-out. Pings and seats vary per game. Score the predeclared seed first, with a second independent initialization. Budget 512 and 2,048 updates of 96 ten-game sequences. PPO uses full-series return. The win predictor uses current-bout ring-out and 30% uniform exploration.',
        assessment='Evaluate every development fixture matching the earlier 27-way informed observer. Test all ten games, opening, rematches, original three and seven subsets, prior PPO, strongest fixed, blinded ping lookup, actual identity lookup both with and without the common opener, coherent other- versus same-opponent history and cleared memory. Compare W-tap, both seats and every RTT pair. Ordinary developer timing recordings remain exposed. A physical branch is checked against its original ending time as well as its score; no unplayed outcomes enter an update.',
    ), fixed=True)


def collect(shard, count):
    spec = read(LIB / 'protocol.json')
    world, contexts, _ = bank('train', 8)
    arms = list(spec['experts'].values())
    foes = list(spec['field'].values())
    worker = Worker(current_runtime=True)
    try:
        for clock_id, clock in enumerate(CLOCKS['train']):
            for rep in range(8):
                if (clock_id * 8 + rep) % count != shard:
                    continue
                path = DATA / 'full-field-reward' / f'{clock_id:02}-{rep:02}.npz'
                if path.exists():
                    continue
                observed = np.empty((10, len(foes), len(arms), 192), np.float32)
                for g, (side, own, other, x, y) in enumerate(fixture_inputs('train', rep, clock_id)):
                    if contexts[clock_id, rep, g].tolist() != [side, own, other]:
                        raise ValueError('Original fixture context differs')
                    for a, actor in enumerate(arms):
                        plan = [[actor['reference'], foe['reference'], clock, 'ab'[side], own, other, x, y] for foe in foes]
                        played = worker.run(plan, True)
                        expected = world[clock_id, rep, g, :, a].numpy()
                        if not np.array_equal([[w, ms] for w, ms, _ in played], expected):
                            raise ValueError(f'Original physical fixture differs at {clock_id}/{rep}/{g}/{a}')
                        observed[g, :, a] = [signal(z[:, CHANNELS])[360:] for _, _, z in played]
                buf = io.BytesIO()
                np.savez_compressed(buf, physical=observed)
                atomic(path, buf.getvalue())
    finally:
        worker.close()
    write_json(DEST / f'collection/shard-{shard}.json', dict(shards=count), fixed=True)


def data(split):
    reps = 8 if split == 'train' else 4
    world, cues, wt = bank(split, reps)
    n, r, g, nf, na, _ = world.shape
    combined = np.empty((n, r, g, nf, na, 198), np.float32)
    for i in range(n):
        for j in range(r):
            name = f'{i:02}-{j:02}.npz'
            if split == 'train':
                with np.load(DATA / 'full-field-reward' / name) as z:
                    combined[i, j, ..., :192] = z['physical']
            else:
                with np.load(DATA / 'dev' / name) as z:
                    if not np.array_equal(z['arms'], np.tile(np.arange(na), (g, 1))):
                        raise ValueError('Development observations have a different expert order')
                    combined[i, j, ..., :192] = z['features'][..., 360:]
            pair = np.broadcast_to(cues[i,j,:,None,None,1:].numpy(), (g, nf, na, 2))
            outcome = world[i, j].numpy()
            combined[i,j,...,192:] = visible_feedback(outcome[...,0], outcome[...,1], pair)
    return torch.from_numpy(combined), world[...,0], cues, wt


def value_fit(data, folder, seed, limit, change_rate=0.):
    x, w, c, _ = data
    nc, nr, ng, nf, na = w.shape
    torch.manual_seed(seed)
    net = Actor(na, False)
    opt = torch.optim.Adam(net.parameters(), lr=.0007)
    rng = torch.Generator().manual_seed(seed+41)
    start = 0
    resume = folder / 'resume.pt'
    if resume.exists():
        old = torch.load(resume, weights_only=True)
        net.load_state_dict(old['actor'])
        opt.load_state_dict(old['opt'])
        torch.set_rng_state(old['torch'])
        rng.set_state(old['gen'])
        start = old['step']
    for step in range(start+1, limit+1):
        b = 96
        ids = training_opponents(nf, b, ng, rng, change_rate, seed+71809*(step-1))
        indices = [torch.randint(k, (b, ng), generator=rng) for k in (nc, nr, ng)]
        ping = c[*indices][...,1:].float()
        a, event, won, *_ = sample(net, x[*indices, ids], w[*indices, ids], ping, 'q', .3)
        for _ in range(3):
            raw, _ = net.sequence(event, ping)
            logits = raw.gather(-1, a.unsqueeze(-1)).squeeze(-1)
            opt.zero_grad()
            F.binary_cross_entropy_with_logits(logits, (won==1).float()).backward()
            torch.nn.utils.clip_grad_norm_(net.parameters(), .8)
            opt.step()
        if step % 64 == 0:
            buf = io.BytesIO()
            torch.save(dict(step=step, actor=net.state_dict(), opt=opt.state_dict(), torch=torch.get_rng_state(), gen=rng.get_state()), buf)
            atomic(resume, buf.getvalue())
    buf = io.BytesIO()
    torch.save(net.state_dict(), buf)
    atomic(folder / f'u{limit}.pt', buf.getvalue())


def evaluate(model, train, dev, dest, seed, step):
    obs, w, c, wt = dev
    n, r, g, nf, na = w.shape
    groups = read(STUDY / 'protocol.json')
    field = list(read(LIB / 'protocol.json')['field'])
    x = obs.permute(0,1,3,2,4,5).reshape(-1,g,na,198)
    scores = w.permute(0,1,3,2,4).reshape(-1,g,na)
    context = c[:,:,None].expand(n,r,nf,g,3).reshape(-1,g,3)
    p = context[...,1:].float()
    same, wrong = donor_rows(c, nf)
    ids = np.tile(np.arange(nf), n*r)
    arms = {'wtap': wt.permute(0,1,3,2).reshape(-1,g).numpy()}
    hist = {}
    for name, donor, clear in [('memory', None, False), ('erased', None, True),
                               ('same', torch.from_numpy(same), False), ('wrong', torch.from_numpy(wrong), False)]:
        a, _, z, *_ = play(model, x, scores, p, True, donor, clear)
        arms[name] = z.numpy()
        hist[name] = [torch.bincount(v,minlength=na).tolist() for v in a.T]
    train_ping = train[2][...,1:].numpy() >= 70
    bins = 2*train_ping[...,0]+train_ping[...,1]
    tab = np.stack([(train[1].numpy()[bins==b]==1).mean(0) for b in range(4)])
    test_bin = (2*(p[...,0]>=70)+(p[...,1]>=70)).numpy()
    idx = np.arange(len(x))[:,None]
    tick = np.arange(g)[None,:]
    raw = scores.numpy()
    fixed = int(tab.mean((0, 1)).argmax())
    arms['fixed'] = raw[:,:,fixed]
    arms['ping'] = raw[idx,tick,tab.mean(1)[test_bin].argmax(-1)]
    arms['id'] = raw[idx,tick,tab[test_bin,ids[:,None]].argmax(-1)]
    reference = read(DEST.parent.parent / 'dev/headroom.json')['scores']
    for mode, old in [('fixed','train-best-fixed'), ('id','id-ringout-spawn'),('ping','ping-ringout-spawn'),('wtap','wtap')]:
        if tally(arms[mode]) != reference[old]['games']:
            raise ValueError('Fixed controls disagree with original development grade: '+mode)
    report = {}
    for mode, z in arms.items():
        report[mode] = dict(games=tally(z), series=tally(series(z)), by_game=[tally(v) for v in z.T], rematches=tally(z[:,1:]),
            opponents={f:tally(z[ids==j]) for j,f in enumerate(field)},
            seats={side:tally(z[context[...,0].numpy()==i]) for i,side in enumerate('ab')},
            rtt={f'{a}/{b}':tally(z[(p[...,0].numpy()==a)&(p[...,1].numpy()==b)]) for a,b in RTTS},
            populations={key:tally(z[np.isin(ids,[field.index(f) for f in names])]) for key,names in [('three',groups['small']),('seven',list(groups['pairs']))]})
    effects = {}
    for a,b in [('memory','erased'),('memory','ping'),('memory','fixed'),('memory','id'),('same','wrong')]:
        delta = ((arms[a]==1).astype(float)-(arms[b]==1)).reshape(n,r,-1).mean(2)
        effects[a+' minus '+b] = dict(delta=float(delta.mean()), conditional_95=bootstrap(delta))
    write_json(dest, dict(scores=report, contrasts=effects, expert_counts_by_game=hist,
        seed=seed, updates=step, trained_decisions=step*96*10,
        same_fixture_reference={k:reference[k]['games'] for k in ('previous-ppo','id-ringout','train-best-fixed','wtap')}), fixed=True)


def run(kind, seed):
    while not all((DEST/f'collection/shard-{j}.json').exists() for j in range(2)):
        time.sleep(8)
    train, dev = data('train'), data('dev')
    folder = DEST / f'{kind}-{seed}'
    for step in STEPS:
        receipt = folder / f'dev-u{step}.json'
        if receipt.exists():
            continue
        torch.manual_seed(seed)
        if kind == 'ppo':
            # fit also returns the final model when its internal checkpoint schedule stops at 1024.
            model = fit(train, folder, step, False, seed)
            if not (folder/f'u{step}.pt').exists():
                buf = io.BytesIO()
                torch.save(model.state_dict(), buf)
                atomic(folder/f'u{step}.pt', buf.getvalue())
        elif kind == 'value':
            value_fit(train, folder, seed, step)
            model = Actor(train[1].shape[-1], False)
            model.load_state_dict(torch.load(folder/f'u{step}.pt', weights_only=True))
        else:
            raise ValueError('Unknown training method')
        evaluate(model.eval(), train, dev, receipt, seed, step)


if __name__ == '__main__':
    if sys.argv[1] == 'register':
        register()
    elif sys.argv[1] == 'collect':
        collect(int(sys.argv[2]), int(sys.argv[3]))
    else:
        run(sys.argv[1], int(sys.argv[2]))
