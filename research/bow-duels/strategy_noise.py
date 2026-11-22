"""Matched PPO and replay interventions for a private, per-duel strategy input."""
import argparse
import hashlib
import io
import itertools
import math
from pathlib import Path
import time

import numpy as np
import torch

from arena import SIZE, TURN
from competition import thaw
from league import Arena, Policy, SCRIPTS, controls, evaluate, get_obs, optimize, rollout, save, scripted, seat_actions

DIM = 4
HORIZONS = (20, 40, 80)
OPENINGS = ((653101, 0), (653123, 1), (653171, 0), (653183, 1), (653197, 0), (653203, 1))
ARMS = ('zeros', 'episode-noise')
MODES = ('incumbent', 'frozen-mlp', *SCRIPTS)


def sample_bits(count, generator):
    return torch.randint(0, 2, (count, DIM), generator=generator).float().mul_(2).sub_(1)


def generator(seed):
    return torch.Generator().manual_seed(seed)


def record(model):
    return dict(kind=model.kind, conditioned=model.conditioned, strategy_dim=model.strategy_dim,
                state=model.state_dict())


def warm_model(source):
    model = Policy('gru', conditioned=source['conditioned'], strategy_dim=DIM)
    message = model.load_state_dict(source['state'], strict=False)
    if message.missing_keys != ['strategy.weight'] or message.unexpected_keys:
        raise RuntimeError(f'Unexpected warm start: {message}')
    return model


def match_schedule(actors, batch, seed):
    pairs_per_foe = [batch // 8, batch // 8]
    scripts = batch // 2 - sum(pairs_per_foe)
    order = ([MODES[0]] * pairs_per_foe[0] + [MODES[1]] * pairs_per_foe[1] +
             [SCRIPTS[i % len(SCRIPTS)] for i in range(scripts)])
    np.random.default_rng(seed).shuffle(order)
    return [actors[n] for n in order for _ in range(2)]


def fixture_bits(batch, seed):
    return sample_bits(batch // 2, generator(seed)).repeat_interleave(2, dim=0)


@torch.no_grad()
def panel(models, opponents, batch, limit, seeds):
    results = {}
    for arm, model in models.items():
        rows = {}
        for i, name in enumerate(MODES):
            opponent = opponents[name]
            rows[name] = [
                evaluate(model, opponent if not isinstance(opponent, int) else None,
                         opponent if isinstance(opponent, int) else 0, batch, limit, seed + i,
                         descriptive=True,
                         strategy=fixture_bits(batch, seed*101 + i) if arm == 'episode-noise'
                         else torch.zeros(batch, DIM))
                for seed in seeds
            ]
        results[arm] = rows
        print('Panel', arm, summarize(rows), flush=True)
    return results


def summarize(rows):
    return {n: {k: sum(m[k] for m in matches) for k in ('wins','draws','losses','shots','hits')}
            for n, matches in rows.items()}


@torch.no_grad()
def interventions(model, openings):
    codes = torch.tensor(list(itertools.product((-1., 1.), repeat=DIM)))
    cases = []
    for seed, side in openings:
        n = len(codes)
        # Every row starts from the identical deterministic one-arena state.
        # The first-frame comparison therefore isolates the bits exactly.
        arena = Arena(1, seed, 640, evaluation=True)
        arena.update_vision()
        inputs = get_obs(arena, side).expand(n, -1)
        scores, _, _, _ = model(torch.cat((inputs, codes), -1)[None], model.memory(n))
        distributions, mouse = model.distribution(scores[0])
        move = torch.stack([d.mode for d in distributions[:2]], -1).tolist()
        turn = (mouse.mean.tanh().numpy() * TURN).tolist()
        variation = []
        for d in distributions:
            variation.append(float(.5 * (d.probs[:, None] - d.probs[None, :]).abs().sum(-1).max()))
        paths, release, totals = [], [], []
        for code in codes.split(1):
            game = Arena(1, seed, 640, evaluation=True)
            seat = np.array([side])
            state = model.memory(1)
            points = [game.pos[0, side, [0,2]].tolist()]
            first = None
            for tick in range(1, HORIZONS[-1] + 1):
                game.update_vision()
                action, _, _, state = model.act(get_obs(game, seat, code), state, greedy=True)
                foe = scripted(game, 1-seat, np.full(1, 2))
                done, _ = game.step(seat_actions(controls(action), foe, seat))
                if first is None and game.shots[0,side]:
                    first = tick
                points.append(game.pos[0,side,[0,2]].tolist())
                if done[0]:
                    break
            paths.append(points)
            release.append(first)
            totals.append(int(game.shots[0,side]))
        separations = {}
        for tick in HORIZONS:
            p = np.array([path[tick] for path in paths if len(path) > tick])
            separations[str(tick)] = float(np.linalg.norm(p[:, None] - p[None, :], axis=-1).max()) if len(p) else None
        cases.append(dict(seed=seed, seat=side, first_tick_movement=move,
                          first_tick_turn_degrees=turn, max_button_total_variation=variation,
                          max_horizontal_separation_blocks=separations, first_shot_tick=release,
                          shots_in_opening=totals, opening_paths=paths))
    return cases


@torch.no_grad()
def validate_initial(source, models, seed):
    baseline = thaw(source)
    env = Arena(8, seed, 640)
    env.update_vision()
    obs = get_obs(env, env.rows % 2)[None]
    expected = baseline(obs, baseline.memory(8))
    for model in models.values():
        extra = sample_bits(8, generator(seed))[None]
        result = model(torch.cat((obs, extra),-1),model.memory(8))
        for i in (0,1,3):
            torch.testing.assert_close(result[i], expected[i], rtol=0, atol=0)


def experiment(args):
    args.out.mkdir(parents=True, exist_ok=False)
    snapshot = (args.league/'state.pt').read_bytes()
    state = torch.load(io.BytesIO(snapshot), map_location='cpu', weights_only=True)
    base = state['learners']['generalist-gru']['policy']
    peer = state['learners']['generalist-mlp']['policy']
    incumbent = state['incumbent']
    champ_bytes = (args.league/'frozen'/(incumbent + '.pt')).read_bytes()
    champion = torch.load(io.BytesIO(champ_bytes), map_location='cpu', weights_only=True)
    actors = dict(zip(SCRIPTS, range(len(SCRIPTS)), strict=True))
    actors.update({'frozen-mlp':thaw(peer), 'incumbent':thaw(champion)})
    models = {n:warm_model(base) for n in ARMS}
    optimizers = {n:torch.optim.Adam(m.parameters(), lr=.0001, eps=1e-5) for n,m in models.items()}
    validate_initial(base, models, args.seed)
    train_seeds = [3341331 + 1009*i + args.seed for i in range(args.updates)]
    eval_seeds = [87664327 + 10009*i for i in range(args.panels)]
    protocol = dict(seed=args.seed, conditioned=base['conditioned'], start_role='generalist-gru',
                    league_generation=state['generation'], league_phase=state['phase'], league_step=state['step'],
                    league_state_sha256=hashlib.sha256(snapshot).hexdigest(), incumbent=incumbent,
                    incumbent_sha256=hashlib.sha256(champ_bytes).hexdigest(),
                    training_opponents=MODES, training_seeds=train_seeds, evaluation_seeds=eval_seeds,
                    intervention_openings=OPENINGS, strategy_dimensions=DIM, batch=args.batch, ticks=args.ticks,
                    limit=args.limit, evaluation_games_per_seed=args.eval_batch,
                    parameters=sum(p.numel() for p in models['zeros'].parameters()))
    save(args.out/'protocol.json',protocol)
    save(args.out/'starting-weights.pt',dict(base=base, peer=peer, incumbent=champion))
    reports = []
    for i, seed in enumerate(train_seeds,1):
        started = time.monotonic()
        schedule = match_schedule(actors,args.batch,seed)
        step = {}
        for arm, model in models.items():
            torch.manual_seed(seed + 3301)
            rng = generator(seed + 6301)
            draw = (lambda n: sample_bits(n,rng)) if arm == 'episode-noise' else (lambda n: torch.zeros(n,DIM))
            data, metrics = rollout(model,None,args.batch,args.ticks,args.limit,seed,schedule=schedule,
                                    sample_strategy=draw)
            keep = data['obs'][..., SIZE:]
            if arm == 'episode-noise':
                if not bool((keep.abs() == 1).all()):
                    raise RuntimeError('Episode context left its registered support')
                switched = (keep[1:] != keep[:-1]).any(-1)
                if bool((switched & ~data['reset'][1:]).any()):
                    raise RuntimeError('Episode context changed mid-duel')
            elif bool(keep.any()):
                raise RuntimeError('Nonzero context in the control arm')
            optimize(model,optimizers[arm],data,args.ticks,args.batch)
            metrics['strategy_weight_norm'] = float(model.strategy.weight.norm().detach())
            step[arm] = metrics
        reports.append(step)
        save(args.out/'checkpoints.pt',{n:record(m) for n,m in models.items()})
        save(args.out/'progress.json',dict(update=i,reports=reports))
        print('Update',i,step,'seconds',round(time.monotonic()-started,1),flush=True)
    scores = panel(models,actors,args.eval_batch,args.limit,eval_seeds)
    h2h = [evaluate(models['episode-noise'],models['zeros'],0,args.eval_batch,args.limit,s+17,
                    descriptive=True, paired_results=True, strategy=fixture_bits(args.eval_batch,s+1231),
                    opponent_strategy=torch.zeros(args.eval_batch,DIM)) for s in eval_seeds]
    wins = sum(w > 0 for row in h2h for w in row['pair_margins'])
    losses = sum(w < 0 for row in h2h for w in row['pair_margins'])
    informative = wins + losses
    p = min(1.,2*sum(math.comb(informative,n) for n in range(min(wins,losses)+1))/2**informative)
    causes = interventions(models['episode-noise'], OPENINGS[:args.openings])
    save(args.out/'results.json',dict(protocol=protocol,training=reports,panel=scores,
                                    head_to_head=h2h,head_to_head_paired_sign_p=p,interventions=causes))
    print('Head to head', summarize({'noise vs. zeros':h2h}), 'paired sign p',p,flush=True)
    for row in causes:
        print('Intervention',row['seed'],row['seat'],row['max_horizontal_separation_blocks'],
              row['first_shot_tick'],flush=True)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--league',type=Path,required=True)
    p.add_argument('--out',type=Path,required=True)
    p.add_argument('--updates',type=int,default=6)
    p.add_argument('--batch',type=int,default=48)
    p.add_argument('--ticks',type=int,default=640)
    p.add_argument('--limit',type=int,default=640)
    p.add_argument('--eval-batch',type=int,default=32)
    p.add_argument('--panels',type=int,default=2)
    p.add_argument('--openings',type=int,default=len(OPENINGS))
    p.add_argument('--seed',type=int,default=2771)
    args = p.parse_args()
    if args.batch < 8 or args.batch%8 or args.ticks%32 or args.limit > args.ticks:
        p.error('Training needs an eight-arena multiple and whole windows covering the duel limit')
    if min(args.eval_batch,args.panels,args.updates,args.limit)<1 or args.eval_batch%2 or not 0<=args.openings<=len(OPENINGS):
        p.error('Use positive updates and panels, an even evaluation batch, and up to six interventions')
    torch.set_num_threads(1)
    torch.set_num_interop_threads(1)
    experiment(args)


if __name__ == '__main__':
    main()
