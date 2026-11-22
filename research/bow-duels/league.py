"""Continuous whole-duel PPO, with atomic generations and a frozen evaluation panel."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import time
import fcntl

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

from arena import ARROWS, CORE, KEYS, MOUSE, PROJ, TERRAIN, TURN, local, sq, wrap
from rust_arena import arena_factory

Arena = arena_factory()
MODELS = ('mlp', 'gru', 'gru-forecast')
SCRIPTS = ('stationary', 'strafe', 'reactive')
HORIZONS = (4, 8, 16)
SEQ = 32
KEY_COLUMNS = torch.tensor([[sum(KEYS[:i]) + min(k, n-1) for k in range(3)] for i, n in enumerate(KEYS)])
KEY_PADDING = torch.tensor([[k >= n for k in range(3)] for n in KEYS])


def save(path, data):
    partial = path.with_suffix('.tmp')
    if path.suffix == '.pt':
        torch.save(data, partial)
    else:
        partial.write_text(json.dumps(data, indent=2, allow_nan=False) + '\n')
    os.replace(partial, path)


class Policy(nn.Module):
    def __init__(self, kind, conditioned=False, strategy_dim=0):
        super().__init__()
        self.kind = kind
        self.conditioned = conditioned
        self.strategy_dim = strategy_dim
        self.projectile = nn.Sequential(nn.Linear(PROJ, 32), nn.Tanh(), nn.Linear(32, 32), nn.Tanh())
        self.features = nn.Sequential(nn.Linear(CORE + TERRAIN + 64, 128), nn.Tanh())
        self.strategy = nn.Linear(strategy_dim, 128, bias=False) if strategy_dim else None
        if self.strategy is not None:
            nn.init.zeros_(self.strategy.weight)
        self.core = nn.Linear(128, 128) if kind == 'mlp' else nn.GRU(128, 128)
        self.policy = nn.Linear(128, sum(KEYS) + 2)
        self.value = nn.Linear(128, 1)
        self.forecast = nn.Linear(128, len(HORIZONS) * 6) if kind == 'gru-forecast' else None
        self.log_std = nn.Parameter(torch.full((2,), -4.25 if conditioned else -3.35))
        nn.init.orthogonal_(self.policy.weight, .02)
        nn.init.zeros_(self.policy.bias)
        if conditioned:
            self.control = nn.Linear(6, sum(KEYS) + 2, bias=False)
            use_on = sum(KEYS[:5]) + 1
            with torch.no_grad():
                self.control.weight.zero_()
                self.control.weight[-2, 0] = 1
                self.control.weight[-1, 1] = 1
                self.control.weight[use_on, 2:5] = torch.tensor([9., -18., -2.])
                self.control.weight[sum(KEYS[:7]), 5] = 7
                self.policy.bias[1] = .25
                self.policy.bias[4] = .25
                for k in (2, 3, 4, 6, 7):
                    self.policy.bias[sum(KEYS[:k])] = 2

    def memory(self, batch):
        return torch.zeros(1, batch, 128)

    def forward(self, observation, memory, resets=None):
        a, projectiles, strategy = observation.split((CORE + TERRAIN, ARROWS * PROJ, self.strategy_dim), -1)
        p = projectiles.reshape(*a.shape[:-1], ARROWS, PROJ)
        present = p[..., -1:] > 0
        p = self.projectile(p)
        peak = p.masked_fill(~present, -torch.inf).max(-2).values
        mean = (p * present).sum(-2) / present.sum(-2).clamp(min=1)
        fused = torch.cat((a, peak.nan_to_num(neginf=0), mean), -1)
        if self.strategy is None:
            x = self.features(fused)
        else:
            x = (self.features[0](fused) + self.strategy(strategy)).tanh()
        if self.kind == 'mlp':
            x = self.core(x).tanh()
        elif resets is None:
            x, memory = self.core(x, memory)
        else:
            output = []
            for tick, reset in zip(x, resets, strict=True):
                tick, memory = self.core(tick[None], memory * (~reset)[None, :, None])
                output.append(tick[0])
            x = torch.stack(output)
        forecast = self.forecast(x).unflatten(-1, (len(HORIZONS), 6)) if self.forecast else None
        scores = self.policy(x)
        if self.conditioned:
            scores = scores + self.control(control_features(observation))
        return scores, self.value(x).squeeze(-1), forecast, memory

    def distribution(self, output):
        keys, mouse = output.split((sum(KEYS), 2), -1)
        return ([torch.distributions.Categorical(logits=x) for x in keys.split(KEYS, -1)],
                torch.distributions.Normal(mouse, self.log_std.clamp(-4.5, -1.5).exp()))

    def act(self, observation, memory, greedy=False):
        output, value, _, memory = self(observation[None], memory)
        output = output[0]
        logits = output[:, KEY_COLUMNS].masked_fill(KEY_PADDING, -torch.inf)
        scores = logits if greedy else logits - torch.empty_like(logits).exponential_().log()
        keys = scores.argmax(-1)
        mouse = torch.distributions.Normal(output[:, -2:], self.log_std.clamp(-4.5, -1.5).exp())
        motion = mouse.mean if greedy else mouse.sample()
        logp = logits.log_softmax(-1).gather(-1, keys.unsqueeze(-1)).squeeze(-1).sum(-1)
        logp += mouse.log_prob(motion).sum(-1)
        return torch.cat((keys, motion), -1), logp, value[0], memory


def density(keys, mouse, action):
    logp = sum(d.log_prob(action[..., i].long()) for i, d in enumerate(keys))
    entropy = sum(d.entropy() for d in keys)
    return logp + mouse.log_prob(action[..., MOUSE]).sum(-1), entropy + mouse.entropy().sum(-1)


def control_features(obs):
    # All quantities are derived from existing client-side observation channels.
    offset = 24 * obs[..., 18:21]
    velocity = obs[..., 21:24] / 3
    distance = torch.linalg.vector_norm(offset[..., (0, 2)], dim=-1)
    flight = (distance / 2.90).clamp(1, 20)
    lead = offset + velocity * flight[..., None]
    fall = 5 * (flight - (1 - .99**flight) / .01)
    yaw = torch.rad2deg(torch.atan2(-lead[..., 0], lead[..., 2]) - .16 / distance.clamp(min=2))
    height = lead[..., 1] + 1.03 - 1.52 + fall
    range_ = torch.linalg.vector_norm(lead[..., (0, 2)], dim=-1)
    pitch = -torch.rad2deg(torch.atan2(height, range_)) - 90 * obs[..., 8]
    seen = (obs[..., 35] < .875).to(obs.dtype)
    full = (obs[..., 14] >= .999).to(obs.dtype)
    keep_bow = ((obs[..., 14] > 0) | (obs[..., 16] < .5)).to(obs.dtype)
    return torch.stack(((yaw / 35).clamp(-.92, .92).atanh() * seen,
                        (pitch / 20).clamp(-.92, .92).atanh() * seen, seen, full,
                        torch.ones_like(seen), keep_bow), -1)


def controls(action):
    result = action.numpy().copy()
    result[..., MOUSE] = np.tanh(result[..., MOUSE])
    return result


def scripted(arena, seat, style):
    r, o = arena.rows, 1 - seat
    p, yaw, pitch = arena.pos[r, seat], arena.yaw[r, seat], arena.pitch[r, seat]
    age = arena.seen_age[r, seat]
    history = arena.seen_history[r, seat]
    valid = arena.visible_history[r, seat, 4] & (age == 0)
    speed = (history[:, 0] - history[:, 4]) / 4 * valid[:, None]
    displacement = arena.last_seen[r, seat] - p
    distance = np.linalg.norm(displacement[:, [0, 2]], axis=-1)
    flight = np.minimum(20, np.maximum(1, distance / 2.90))
    lead = displacement + speed * (style != 0)[:, None] * flight[:, None]
    travel = (1 - .99**flight) / .01
    fall = .05 * (flight - travel) / .01
    lead[:, 1] += 1.03 - 1.52 + fall
    target_yaw = np.rad2deg(np.arctan2(-lead[:, 0], lead[:, 2])) - .16 / distance.clip(2) * (180 / np.pi)
    target_pitch = -np.rad2deg(np.arctan2(lead[:, 1], np.linalg.norm(lead[:, [0, 2]], axis=-1)))
    x = np.zeros((arena.n, len(KEYS) + 2), np.float32)
    x[:, :2] = 1
    phase = arena.t + (r // 2 if arena.paired else r) * 7
    side = ((phase // np.where(style == 2, 19, 27)) % 2) * 2 - 1
    moving = style > 0
    x[:, 0] += np.where(moving & (distance > 18), 1, np.where(moving & (distance < 9), -1, 0))
    x[:, 1] += side * moving
    projectiles = arena.ap - (p + [0, 1, 0])[:, None]
    toward = -(projectiles * arena.av).sum(-1)
    eta = toward / np.maximum(sq(arena.av), 1e-5)
    threat = ((style == 2)[:, None] & (arena.age > 0) & ~arena.apearl &
              (arena.owner[None] == o[:, None]) & (eta > 1) & (eta < 7) &
              (sq(projectiles + arena.av * eta[..., None]) < 2.2**2) &
              ~np.isfinite(arena.terrain_hit((p + [0, 1.62, 0])[:, None], projectiles)))
    evade = threat.any(-1)
    x[:, 2] = (evade & (phase % 4 == 0)) | ((style == 2) & (phase % 101 == 25))
    x[:, 3] = (x[:, 0] == 2) & (~arena.use[r, seat] | evade)
    x[:, 4] = (style == 1) & (phase % 97 < 10)
    x[:, 5] = ((arena.draw[r, seat] < 20) & (age < 35)) | ((age > 0) & (arena.draw[r, seat] > 0))
    x[:, 5] *= ~evade | (arena.draw[r, seat] < 19)
    x[:, 6] = (distance < 2.75) & (phase % 4 == 0) & (age == 0)
    pearl = (style == 2) & arena.pearl[r, seat] & (age == 0) & (distance > 17) & (phase % 173 == 40)
    x[pearl, 7] = x[pearl, 5] = 1
    target_pitch[pearl] = 14
    turn = np.column_stack((wrap(target_yaw - yaw), target_pitch - pitch))
    x[:, MOUSE] = (turn / TURN).clip(-.92, .92) * (age < 35)[:, None]
    return x


def seat_actions(first, second, seat):
    both = np.stack((first, second), axis=1)
    return np.where(seat[:, None, None] == 0, both, both[:, ::-1])


def get_obs(arena, seat, strategy=None):
    observation = torch.from_numpy(arena.observe(seat))
    return observation if strategy is None else torch.cat((observation, strategy), dim=-1)


@torch.no_grad()
def demonstration(batch, ticks, seed):
    arena = Arena(batch, seed)
    seat = np.arange(batch) % 2
    style = np.arange(batch) // 2 % 2 + 1
    obs, action, starts = [], [], []
    reset = torch.ones(batch, dtype=torch.bool)
    for _ in range(ticks):
        arena.update_vision()
        obs.append(get_obs(arena, seat))
        starts.append(reset)
        target = scripted(arena, seat, style)
        action.append(torch.from_numpy(target))
        done, _ = arena.step(seat_actions(target, scripted(arena, 1-seat, np.arange(batch) % 3), seat))
        arena.reset(arena.rows[done])
        reset = torch.from_numpy(done)
    return torch.stack(obs), torch.stack(action), torch.stack(starts)


def imitate(models, optimizers, batch, ticks):
    for seed in (3205, 4512):
        obs, actions, resets = demonstration(batch, ticks, seed)
        for policy, opt in zip(models.values(), optimizers.values(), strict=True):
            for _ in range(8):
                hidden = policy.memory(batch)
                for start in range(0, ticks, SEQ):
                    window = slice(start, start + SEQ)
                    target = actions[window]
                    output, _, _, hidden = policy(obs[window], hidden, resets[window])
                    hidden = None if hidden is None else hidden.detach()
                    key, mouse = output.split((sum(KEYS), 2), -1)
                    loss = sum(F.cross_entropy(k.reshape(-1, n), target[..., i].flatten().long())
                               for i, (k, n) in enumerate(zip(key.split(KEYS, -1), KEYS, strict=True)))
                    loss += 120 * F.smooth_l1_loss(mouse, target[..., MOUSE].atanh())
                    opt.zero_grad()
                    loss.backward()
                    nn.utils.clip_grad_norm_(policy.parameters(), 1.0)
                    opt.step()
        print(f'Demonstration batch completed (seed {seed}, {batch * ticks} ticks)', flush=True)


@torch.no_grad()
def rollout(policy, champion, batch, ticks, limit, seed, schedule=None, sample_strategy=None):
    env = Arena(batch, seed, limit)
    seat = (np.arange(batch) + (seed % 2)) % 2
    styles = (np.arange(batch) // 2) % len(SCRIPTS)
    against_archive = np.arange(batch) % 4 == 0 if champion else np.zeros(batch, bool)
    hidden = policy.memory(batch)
    strategy = sample_strategy(batch) if sample_strategy is not None else None
    groups = {}
    if schedule is None:
        if champion:
            groups[champion] = np.flatnonzero(against_archive)
    else:
        if len(schedule) != batch:
            raise ValueError('Opponent schedule must cover every arena')
        for row, opponent in enumerate(schedule):
            if isinstance(opponent, int):
                styles[row] = opponent
            else:
                groups.setdefault(opponent, []).append(row)
        groups = {p: np.asarray(rows) for p, rows in groups.items()}
    all_learned = sum(len(rows) for rows in groups.values()) == batch
    enemy_hidden = {p: p.memory(len(rows)) for p, rows in groups.items()}
    records = {k: [] for k in ('obs', 'action', 'value', 'logp', 'reward', 'done', 'reset', 'hidden', 'position', 'opponent', 'yaw')}
    reset = torch.ones(batch, dtype=torch.bool)
    wins = draws = games = shots = hits = 0
    for _ in range(ticks):
        env.update_vision()
        observation = get_obs(env, seat, strategy)
        records['hidden'].append(hidden[0].clone())
        act, logp, value, hidden = policy.act(observation, hidden)
        foe = np.empty((batch, len(KEYS) + 2), np.float32) if all_learned else scripted(env, 1-seat, styles)
        if groups:
            view = get_obs(env, 1-seat)
            for opponent, rows in groups.items():
                archive, _, _, enemy_hidden[opponent] = opponent.act(view[rows], enemy_hidden[opponent])
                foe[rows] = controls(archive)
        records['position'].append(env.pos[env.rows, seat])
        records['opponent'].append(env.pos[env.rows, 1-seat])
        records['yaw'].append(env.yaw[env.rows, seat])
        hp = env.hp.clip(0, 20)
        done, result = env.step(seat_actions(controls(act), foe, seat))
        result = result * (1-2*seat)
        damage = (hp - env.hp.clip(0, 20))
        reward = .045 * (damage[env.rows, 1-seat] - damage[env.rows, seat]) + result * done
        wins += int((done & (result > 0)).sum())
        draws += int((done & (result == 0)).sum())
        games += int(done.sum())
        shots += int(env.shots[env.rows[done], seat[done]].sum())
        hits += int(env.hits[env.rows[done], seat[done]].sum())
        for key, val in zip(('obs', 'action', 'value', 'logp', 'reward', 'done', 'reset'),
                            (observation, act, value, logp, torch.from_numpy(reward).float(), torch.from_numpy(done), reset),
                            strict=True):
            records[key].append(val)
        env.reset(env.rows[done])
        reset = torch.from_numpy(done)
        if strategy is not None and done.any():
            strategy[reset] = sample_strategy(int(done.sum()))
        hidden = hidden * (~reset)[None, :, None]
        for opponent, rows in groups.items():
            enemy_hidden[opponent] *= (~reset[rows])[None, :, None]
    env.update_vision()
    _, bootstrap, _, _ = policy(get_obs(env, seat, strategy)[None], hidden)
    for k in ('position', 'opponent', 'yaw'):
        records[k] = np.stack(records[k])
    for k in set(records) - {'position', 'opponent', 'yaw'}:
        records[k] = torch.stack(records[k])
    advantage = torch.zeros_like(records['reward'])
    back = torch.zeros(batch)
    next_value = bootstrap[0]
    for t in range(ticks - 1, -1, -1):
        active = ~records['done'][t]
        v = records['value'][t]
        back = records['reward'][t] + .995 * active * next_value - v + .995 * .95 * active * back
        advantage[t] = back
        next_value = v
    records['return'] = advantage + records['value']
    records['advantage'] = (advantage - advantage.mean()) / advantage.std().clamp(min=1e-5)
    if policy.forecast:
        labels, masks = [], []
        resets = records['reset'].cumsum(dim=0)
        for h in HORIZONS:
            future = records['opponent'][h:] - records['position'][:-h]
            y = local(future, records['yaw'][:-h]) / 24
            labels.append(np.pad(y, ((0, h), (0, 0), (0, 0))))
            masks.append(F.pad(resets[h:] == resets[:-h], (0, 0, 0, h)))
        records['label'] = torch.tensor(np.stack(labels, axis=-2), dtype=torch.float32)
        records['valid'] = torch.stack(masks, -1)
    return records, dict(games=games, wins=wins, draws=draws, losses=games-wins-draws, shots=shots, arrow_hits=hits)


def optimize(policy, optimizer, data, ticks, batch):
    starts = torch.arange(0, ticks, SEQ).repeat_interleave(batch)
    seats = torch.arange(batch).repeat(ticks // SEQ)
    for _ in range(3):
        for i in torch.randperm(len(starts)).split(24):
            time_ix = starts[i][None, :] + torch.arange(SEQ)[:, None]
            take = (time_ix, seats[i])
            h = data['hidden'][starts[i], seats[i]][None]
            output, value, forecast, _ = policy(data['obs'][take], h, data['reset'][take])
            logp, entropy = density(*policy.distribution(output), data['action'][take])
            ratio = (logp - data['logp'][take]).exp()
            a = data['advantage'][take]
            policy_loss = -torch.minimum(ratio * a, ratio.clamp(.8, 1.2) * a).mean()
            value_loss = F.smooth_l1_loss(value, data['return'][take])
            loss = policy_loss + .5 * value_loss - .004 * entropy.mean()
            if forecast is not None:
                mean, spread = forecast.split(3, -1)
                spread = spread.clamp(-5, 1)
                nll = (spread + .5 * ((data['label'][take] - mean) * (-spread).exp()).square()).mean(-1)
                valid = data['valid'][take]
                loss += .03 * (nll * valid).sum() / valid.sum().clamp(min=1)
            if not torch.isfinite(loss):
                raise ValueError('Non-finite PPO objective')
            optimizer.zero_grad()
            loss.backward()
            nn.utils.clip_grad_norm_(policy.parameters(), .5)
            optimizer.step()


@torch.no_grad()
def evaluate(policy, opponent, style, batch, limit, seed, descriptive=False, paired_results=False,
             strategy=None, opponent_strategy=None):
    env = Arena(batch, seed, limit, evaluation=True, paired=True)
    seat = np.arange(batch) % 2
    a = None if isinstance(policy, int) else policy.memory(batch)
    b = opponent.memory(batch) if opponent else None
    done = np.zeros(batch, bool)
    score = np.zeros(batch, np.int8)
    for _ in range(limit):
        env.update_vision()
        if a is None:
            action = scripted(env, seat, np.full(batch, policy))
        else:
            raw, _, _, a = policy.act(get_obs(env, seat, strategy), a, greedy=True)
            action = controls(raw)
        if opponent:
            response, _, _, b = opponent.act(get_obs(env, 1-seat, opponent_strategy), b, greedy=True)
            foe = controls(response)
        else:
            foe = scripted(env, 1-seat, np.full(batch, style))
        ended, result = env.step(seat_actions(action, foe, seat), ~done)
        score[~done] = result[~done] * (1-2*seat[~done])
        done |= ended
        if done.all():
            break
    wins, draws = int((score > 0).sum()), int((score == 0).sum())
    answer = dict(wins=wins, draws=draws, losses=batch-wins-draws)
    if descriptive:
        answer.update(shots=int(env.shots[env.rows, seat].sum()), hits=int(env.hits[env.rows, seat].sum()),
                      opponent_shots=int(env.shots[env.rows, 1-seat].sum()),
                      opponent_hits=int(env.hits[env.rows, 1-seat].sum()))
    if paired_results:
        answer['pair_margins'] = score.reshape(-1, 2).sum(axis=1).tolist()
    return answer


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--batch', type=int, default=24)
    p.add_argument('--ticks', type=int, default=384)
    p.add_argument('--limit', type=int, default=640)
    p.add_argument('--generation', type=int, default=12, help='Updates per model in each generation')
    p.add_argument('--panel', type=int, default=32)
    p.add_argument('--updates', type=int, help='Stop after this many updates per model')
    options = p.parse_args()
    if options.batch < 2 or options.batch % 2 or options.ticks < SEQ or options.ticks % SEQ:
        p.error('Batch must be even and rollout ticks must be a multiple of 32')
    if options.panel < 2 or options.panel % 2 or options.generation < 1 or options.limit < 2:
        p.error('Panel must be even and limit and generation size must be positive')
    torch.set_num_threads(1)
    torch.set_num_interop_threads(1)
    torch.manual_seed(19856)
    out = options.out
    out.mkdir(exist_ok=True, parents=True)
    with (out / 'runner.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        train(options, out)


def train(options, out):
    models = {kind: Policy(kind) for kind in MODELS}
    opt = {kind: torch.optim.Adam(policy.parameters(), lr=.0003, eps=1e-5) for kind, policy in models.items()}
    panel_seed = 7811023
    signature = dict(batch=options.batch, ticks=options.ticks, limit=options.limit, generation=options.generation,
                     panel=options.panel, seed=19856, evaluation_seed=panel_seed,
                     source={name: hashlib.sha256(Path(__file__).with_name(name).read_bytes()).hexdigest()
                             for name in ('arena.py', 'league.py', 'rust_arena.py', 'geometry.rs')})
    if path := os.environ.get('BOW_GEOMETRY_LIB'):
        signature['geometry_sha256'] = hashlib.sha256(Path(path).read_bytes()).hexdigest()
    path = out / 'checkpoint.pt'
    champion = None
    if path.exists():
        state = torch.load(path, map_location='cpu', weights_only=True)
        if state['signature'] != signature:
            raise ValueError('Checkpoint protocol differs. Use a new output directory for a modified run.')
        for kind, policy in models.items():
            policy.load_state_dict(state['models'][kind])
            opt[kind].load_state_dict(state['optimizers'][kind])
        update = state['update']
        if state['champion']:
            champion = Policy(state['champion']['kind'])
            champion.load_state_dict(state['champion']['state'])
        torch.random.set_rng_state(state['torch_rng'])
    else:
        update = 0
        save(out / 'protocol.json', signature)
        print('Bootstrapping all three policies with scripted demonstrations', flush=True)
        imitate(models, opt, options.batch, min(256, options.ticks))
    previous = time.monotonic()
    while options.updates is None or update < options.updates:
        update += 1
        status = dict(update=update, metrics={})
        for kind, policy in models.items():
            data, metrics = rollout(policy, champion, options.batch, options.ticks, options.limit, 66000 + update*2969)
            optimize(policy, opt[kind], data, options.ticks, options.batch)
            status['metrics'][kind] = metrics
            print(f'update {update} {kind}: {metrics}', flush=True)
        if update % options.generation == 0:
            field = {}
            for kind, policy in models.items():
                foes = {name: evaluate(policy, None, i, options.panel, options.limit, panel_seed + i,
                                       descriptive=True) for i, name in enumerate(SCRIPTS)}
                if champion:
                    foes['incumbent'] = evaluate(policy, champion, 0, options.panel, options.limit, panel_seed + 3)
                field[kind] = foes
            crossplay = {}
            for i, (kind, policy) in enumerate(models.items()):
                for other, opponent in list(models.items())[i+1:]:
                    match = evaluate(policy, opponent, 0, options.panel, options.limit, panel_seed + 4)
                    crossplay[kind+'/'+other] = match
                    field[kind][other] = match
                    field[other][kind] = dict(wins=match['losses'], losses=match['wins'], draws=match['draws'])
            ranking = sorted(models, key=lambda name: -np.mean([
                (v['wins'] + .5*v['draws']) / options.panel for v in field[name].values()]))
            champion = Policy(ranking[0])
            champion.load_state_dict(models[ranking[0]].state_dict())
            final = out / f'g{update//options.generation:03d}.json'
            save(final, dict(update=update, ranking=ranking, field=field, crossplay=crossplay))
            print(f'{final.name} winner: {ranking[0]}', flush=True)
        status['seconds'] = round(time.monotonic() - previous, 2)
        previous = time.monotonic()
        state = dict(signature=signature, update=update, models={k: v.state_dict() for k, v in models.items()},
                     optimizers={k: v.state_dict() for k, v in opt.items()}, torch_rng=torch.random.get_rng_state(),
                     champion=dict(kind=champion.kind, state=champion.state_dict()) if champion else None)
        save(path, state)
        if update % options.generation == 0:
            save(out / f'g{update//options.generation:03d}.pt', state)
        save(out / 'progress.json', status)
        print(f'Checkpoint {update} saved ({status["seconds"]}s)', flush=True)


if __name__ == '__main__':
    main()
