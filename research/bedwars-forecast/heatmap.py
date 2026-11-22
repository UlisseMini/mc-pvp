"""Hierarchical block forecasts and support/query adaptation for captured BedWars."""
import argparse
from collections import defaultdict
from dataclasses import dataclass
import gzip
import hashlib
import json
from pathlib import Path
import struct

import numpy as np
from scipy.ndimage import gaussian_filter
from scipy.special import ndtr
import torch
from torch import nn
from torch.nn import functional as F

from forecast import HORIZONS, PlayerModel, load_games, observed, player_rows, take, write_csv

SIZE = 16
WIDTH = 16
EDGE = -128
HEIGHT_MIN = -64
HEIGHTS = 256
CELLS = SIZE * SIZE
CAP = 32
REFRESH = 30
STRIDE = 3
MIN_SUPPORT = 4
VIEWS = ('stay', 'velocity', 'drift', 'motion', 'motion-no-motion', 'motion-no-context',
         'heatmap-ce', 'heatmap', 'head-tune', 'anil-prior', 'anil', 'history', 'meta-history',
         'personal-hotspots', 'personal-routes')


def tile_ids(xyz):
    cell = (xyz[:, [0, 2]].astype(np.int64) - EDGE) // WIDTH
    return cell[:, 0] * SIZE + cell[:, 1]


@dataclass
class Dataset:
    features: torch.Tensor
    xyz: torch.Tensor
    velocity: torch.Tensor
    hindex: torch.Tensor
    label: torch.Tensor
    tile: torch.Tensor
    raw: dict

    def __len__(self):
        return len(self.tile)

    def batch(self, indices):
        return self.features[indices], self.xyz[indices], self.velocity[indices], self.hindex[indices]

    def target(self, indices):
        return self.label[indices], self.tile[indices]


def concatenate(parts, column):
    return np.concatenate([p[column] for p in parts])


def data_for(games):
    parts = []
    for i, h in enumerate(HORIZONS):
        rows = player_rows(games, h)
        rows['hindex'] = np.full(len(rows['time']), i, dtype=int)
        parts.append(rows)
    raw = {column: concatenate(parts, column) for column in parts[0]}
    mask = observed(raw)
    label = np.floor(np.where(mask[:, None], raw['future'], 0)).astype(np.int64)
    if not (np.all((label[:, [0, 2]] >= EDGE) & (label[:, [0, 2]] < -EDGE))
            and np.all((label[:, 1] >= HEIGHT_MIN) & (label[:, 1] < HEIGHT_MIN + HEIGHTS))):
        raise ValueError('Foot block falls outside the declared lattice')
    floats = [torch.tensor(raw[k], dtype=torch.float32) for k in ('features', 'xyz', 'velocity')]
    return Dataset(*floats, torch.tensor(raw['hindex']), torch.tensor(label), torch.tensor(tile_ids(label)), raw)


class Heatmap(nn.Module):
    def __init__(self):
        super().__init__()
        self.encoder = nn.Sequential(nn.Linear(20, 32), nn.Tanh(), nn.Linear(32, 24), nn.Tanh())
        self.mix = nn.Linear(24, 3)
        self.field = nn.Linear(24, 64)
        self.fine = nn.Sequential(nn.Linear(28, 32), nn.Tanh(), nn.Linear(32, 39))
        self.step = nn.Parameter(torch.zeros(len(HORIZONS)))
        centers = torch.arange(SIZE, dtype=torch.float32) * WIDTH + EDGE + WIDTH / 2
        self.register_buffer('tile_xy', torch.cartesian_prod(centers, centers))
        self.register_buffer('horizons', torch.tensor(HORIZONS, dtype=torch.float32))
        self.register_buffer('axis', torch.arange(WIDTH, dtype=torch.float32) + .5)
        self.register_buffer('height_axis', torch.arange(HEIGHTS, dtype=torch.float32) + HEIGHT_MIN + .5)
        self.register_buffer('smoother', torch.outer(torch.tensor([.25, .5, .25]),
                                                    torch.tensor([.25, .5, .25]))[None, None])
        nn.init.zeros_(self.field.weight)
        nn.init.zeros_(self.field.bias)

    def coarse(self, batch):
        feature, xyz, velocity, hindex = batch
        h = self.horizons[hindex]
        xz = xyz[:, [0, 2]]
        enc = self.encoder(torch.cat((feature, xz / 64, torch.log1p(h[:, None]) / 5), dim=1))
        centers = torch.stack((xz, xz + velocity[:, [0, 2]] * h.clamp(max=22)[:, None] * .7,
                               torch.zeros_like(xz)), dim=1)
        sigmas = torch.stack((5 + .23 * h.clamp(max=100), 10 + .44 * h.clamp(max=115),
                             torch.full_like(h, 51)), dim=1)
        distance = ((self.tile_xy[None, None] - centers[:, :, None]) ** 2).sum(-1)
        prior = F.log_softmax(-.5 * distance / sigmas[:, :, None] ** 2, dim=2)
        logits = torch.logsumexp(prior + F.log_softmax(self.mix(enc), dim=1)[:, :, None], dim=1)
        residual = self.field(enc).reshape(-1, 8, 8).repeat_interleave(2, 1).repeat_interleave(2, 2)
        return logits + residual.flatten(1), enc

    def fine_logp(self, enc, xyz, label, tile, batch_hindex):
        log_xz, log_y = self.fine_logp_parts(enc, xyz, label, tile)
        return log_xz + log_y

    def fine_logp_parts(self, enc, xyz, label, tile):
        horiz, y_logits, y_weights = self.fine_components(enc, xyz, tile)
        fine = (label[:, [0, 2]] - EDGE) % WIDTH
        log_xz = horiz.gather(2, fine[:, :, None])[:, :, 0].sum(dim=1)
        height_label = (label[:, 1] - HEIGHT_MIN)[:, None, None].expand(-1, 3, 1)
        log_y = torch.logsumexp(y_logits.gather(2, height_label)[:, :, 0] + y_weights, dim=1)
        return log_xz, log_y

    def fine_components(self, enc, xyz, tile):
        center = self.tile_xy[tile]
        delta = (center - xyz[:, [0, 2]]) / 64
        raw = self.fine(torch.cat((enc, center / 112, delta), dim=1))
        # Two conditional 16-way block heads. A narrow same-tile prior helps sharp stationary labels.
        xz = xyz[:, [0, 2]]
        cell_pos = xz - (center - WIDTH / 2)
        distance = self.axis[None, None] - cell_pos[:, :, None]
        current = F.log_softmax(-.5 * (distance / 1.45) ** 2, dim=-1)
        learnt = F.log_softmax(raw[:, :32].reshape(-1, 2, WIDTH), dim=-1)
        near = (abs(delta) < .15).all(dim=1)
        gate = torch.where(near, torch.sigmoid(raw[:, 32]) * .97, torch.full_like(raw[:, 32], .001))
        horiz = torch.logaddexp(learnt + torch.log1p(-gate)[:, None, None],
                                current + torch.log(gate)[:, None, None])
        # Height is a normalized histogram with an exact 1-block bin width.
        center_y = xyz[:, 1] + 12 * torch.tanh(raw[:, 33])
        scale = .65 + 3 * F.softplus(raw[:, 34])
        broad_y = xyz[:, 1] + 35 * torch.tanh(raw[:, 35])
        broad_scale = 4 + 14 * F.softplus(raw[:, 36])
        heights = torch.stack((center_y, broad_y, torch.full_like(center_y, 52)), dim=1)
        scales = torch.stack((scale, broad_scale, torch.full_like(scale, 49)), dim=1)
        y_logits = F.log_softmax(-.5 * ((self.height_axis[None, None] - heights[:, :, None])
                                        / scales[:, :, None]) ** 2, dim=2)
        y_weights = F.log_softmax(torch.cat((raw[:, 37:39], torch.full_like(raw[:, 37:38], -4)), dim=1), dim=1)
        return horiz, y_logits, y_weights

    def head_bias(self, support_logits, support_tiles, valid, hindex, meta=True):
        valid = valid.float()
        count = valid.sum(dim=1, keepdim=True).clamp(min=1)
        residual = F.one_hot(support_tiles, CELLS) - support_logits.softmax(dim=-1)
        averaged = (residual * valid[:, :, None]).sum(dim=1) / count
        smooth = F.conv2d(averaged.reshape(-1, 1, SIZE, SIZE), self.smoother, padding=1).flatten(1)
        # One support gradient step on a spatial bias head, preconditioned by a fixed local kernel.
        step = (4 + 76 * self.step[hindex].sigmoid()) if meta else torch.full_like(count[:, 0], 38.)
        return smooth * (step[:, None] * count / (count + MIN_SUPPORT))


def scores(logits, fine, tile):
    log_prob = logits.log_softmax(dim=-1)
    p16 = log_prob.gather(1, tile[:, None])[:, 0]
    p32 = parent_mass(log_prob.exp(), tile).clamp_min(1e-12).log()
    return torch.stack((-(p16 + fine), -p16, -p32), dim=1)


def parent_mass(prob, tile):
    blocks = prob.reshape(-1, 8, 2, 8, 2).sum(dim=(2, 4)).flatten(1)
    parent = ((tile // SIZE) // 2) * 8 + ((tile % SIZE) // 2)
    return blocks.gather(1, parent[:, None])[:, 0]


def supervised(data, mode, eligible, epochs, seed):
    torch.manual_seed(seed)
    model = Heatmap()
    optimizer = torch.optim.AdamW(model.parameters(), lr=.003, weight_decay=.006)
    rng = np.random.default_rng(seed)
    train_weights = torch.tensor([1, .45, .28] if mode == 'multiscale' else [1, 0, 0])
    for epoch in range(epochs):
        total = 0.
        for index in np.array_split(rng.permutation(eligible), max(1, len(eligible) // 512)):
            batch = data.batch(index)
            label, tile = data.target(index)
            coarse, enc = model.coarse(batch)
            fine = model.fine_logp(enc, batch[1], label, tile, batch[3])
            loss = (scores(coarse, fine, tile) * train_weights).mean() * 3
            optimizer.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 3.)
            optimizer.step()
            total += loss.detach().item() * len(index)
        if epoch in (0, epochs - 1):
            print(f'{mode} epoch {epoch+1}/{epochs} loss {total/len(eligible):.4f}', flush=True)
    return model


def support_for(rows, track, cutoff):
    t = rows['time'][track]
    h = np.asarray(HORIZONS)[rows['hindex'][track]]
    valid = (t % STRIDE == 0) & (t + h <= cutoff) & np.isfinite(rows['future'][track, 0])
    return track[valid][-CAP:]


def tasks(data, game_set):
    raw = data.raw
    tracks = defaultdict(list)
    for i, game in enumerate(raw['game']):
        if game in game_set:
            tracks[game, raw['uid'][i], int(raw['hindex'][i])].append(i)
    episodes = []
    ages = {g: int(raw['time'][raw['game'] == g].max()) for g in game_set}
    seen = observed(raw)
    for (game, _, _), values in tracks.items():
        track = np.asarray(values)
        for fraction in (.30, .50, .69, .83):
            time = (int(ages[game] * fraction) // REFRESH) * REFRESH
            support = support_for(raw, track, time)
            query = track[(raw['time'][track] >= time + 5) & (raw['time'][track] < time + 90) & seen[track]]
            if len(support) >= MIN_SUPPORT and len(query) >= 8:
                episodes.append((support, query))
    return episodes


def draw_episodes(episodes, rng, n=10, query_count=12):
    support, valid, query = [], [], []
    for i in rng.integers(0, len(episodes), size=n):
        early, late = episodes[i]
        budget = rng.choice((8, 16, 32), p=(.2, .25, .55))
        early = early[-budget:]
        support.append(np.pad(early, (0, CAP-len(early)), constant_values=early[0]))
        valid.append(np.arange(CAP) < len(early))
        query.append(rng.choice(late, query_count, replace=len(late) < query_count))
    return np.asarray(support), torch.tensor(np.array(valid)), np.asarray(query)


def fit_anil(data, init, episodes, iterations, seed):
    model = Heatmap()
    model.load_state_dict(init.state_dict())
    optimizer = torch.optim.AdamW(model.parameters(), lr=.0008, weight_decay=.006)
    rng = np.random.default_rng(seed)
    qsize = 12
    for n in range(iterations):
        early, valid, late = draw_episodes(episodes, rng, query_count=qsize)
        sb, _ = model.coarse(data.batch(early.ravel()))
        qb, enc = model.coarse(data.batch(late.ravel()))
        label, tile = data.target(late.ravel())
        fine = model.fine_logp(enc, data.xyz[late.ravel()], label, tile, data.hindex[late.ravel()])
        bias = model.head_bias(sb.reshape(-1, CAP, CELLS), data.tile[early], valid, data.hindex[late[:, 0]])
        after = scores(qb + bias.repeat_interleave(qsize, dim=0), fine, tile)
        before = scores(qb, fine, tile)
        loss = ((.83 * after + .17 * before) * torch.tensor([1, .45, .28])).sum(dim=1).mean()
        optimizer.zero_grad()
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 3.)
        optimizer.step()
        if n in (0, iterations - 1):
            print(f'ANIL step {n+1}/{iterations} objective {loss.item():.4f}', flush=True)
    return model


def history_dist(tiles):
    hist = np.bincount(np.asarray(tiles, dtype=int), minlength=CELLS).astype(np.float64)
    smoothed = gaussian_filter(hist.reshape(SIZE, SIZE), .65, mode='constant', radius=2).ravel()
    return ((smoothed + .00002) / (smoothed.sum() + .00002 * CELLS)).astype(np.float32)


def mix_features(hindex, support_count, context):
    return torch.stack((torch.log1p(support_count.float()) / 4, context[:, 9],
                        context[:, 12], context[:, 16]), dim=1)


class MetaHistory(nn.Module):
    def __init__(self):
        super().__init__()
        self.bias = nn.Parameter(torch.zeros(len(HORIZONS)))
        self.feature = nn.Parameter(torch.zeros(4))

    def weight(self, hindex, support_count, context):
        logits = self.bias[hindex] + mix_features(hindex, support_count, context) @ self.feature
        return .94 * torch.sigmoid(logits) * support_count / (support_count + 4)


def fit_meta_history(data, prior, episodes, iterations, seed):
    meta = MetaHistory()
    opt = torch.optim.AdamW(meta.parameters(), lr=.045, weight_decay=.04)
    rng = np.random.default_rng(seed)
    for i in range(iterations):
        support, valid, query = draw_episodes(episodes, rng, n=22, query_count=12)
        late = query.ravel()
        with torch.no_grad():
            raw, _ = prior.coarse(data.batch(late))
            base = raw.softmax(dim=1)
        hist = torch.tensor(np.stack([history_dist(data.tile[row[m]].numpy())
                                      for row, m in zip(support, valid.numpy())])).repeat_interleave(12, dim=0)
        count = valid.sum(1).repeat_interleave(12)
        w = meta.weight(data.hindex[late], count, data.features[late])[:, None]
        prob = base * (1-w) + hist * w
        y = data.tile[late]
        loss = -prob.gather(1, y[:, None]).log().mean() - .45 * parent_mass(prob, y).log().mean()
        opt.zero_grad()
        loss.backward()
        opt.step()
    print('Meta history horizon logits', meta.bias.detach().tolist(), flush=True)
    return meta


def axis_mass(centers, scales):
    edge = EDGE + np.arange(SIZE + 1) * WIDTH
    return np.diff(ndtr((edge[None, None] - centers[:, :, None]) / scales[:, :, None]), axis=2)


def mixture_tiles(law):
    x = axis_mass(law.centers[:, :, 0], law.scales[:, :, 0])
    z = axis_mass(law.centers[:, :, 2], law.scales[:, :, 2])
    p = np.einsum('bki,bkj,bk->bij', x, z, law.weights).reshape(-1, CELLS)
    return p / p.sum(axis=1, keepdims=True).clip(min=1e-80)


def raw_mix_scores(law, prob, target, tile):
    pt = prob[np.arange(len(tile)), tile].clip(min=1e-300)
    parent = ((tile // SIZE) // 2) * 8 + ((tile % SIZE) // 2)
    pp = prob.reshape(-1, 8, 2, 8, 2).sum(axis=(2, 4)).reshape(-1, 64)[np.arange(len(tile)), parent]
    return np.stack((-np.log(law.block_mass(target).clip(min=1e-300)), -np.log(pt),
                     -np.log(pp.clip(min=1e-300))), axis=1)


def _group(raw, subset):
    tracks = defaultdict(list)
    for row in subset:
        tracks[raw['uid'][row], int(raw['hindex'][row])].append(row)
    return tracks


def alias_map(data, game):
    roster = {}
    own = None
    for _, (focus, _, players) in game['frames'].items():
        own = focus
        for uid, player in players.items():
            roster.setdefault(uid, player[0])
    players = sorted(roster, key=lambda uid: (uid != own, roster[uid].lower(), uid))
    return {uid: i for i, uid in enumerate(players)}, own


def quantize(prob):
    # A 12.5-nat dynamic range, ~0.05 nat per code. Browser renormalizes these display fields.
    p = np.log(np.maximum(prob, 1e-32))
    code = 255 + (p - p.max(axis=-1, keepdims=True)) * 20.
    return np.rint(code).clip(0, 255).astype(np.uint8)


@torch.no_grad()
def evaluate_game(data, game, models, mix_models, out, split, write_assets):
    ce, heatmap, anil, meta = models
    raw = data.raw
    included = np.flatnonzero(raw['game'] == game['id'])
    roster, own = alias_map(data, game)
    results, player_results = [], []
    archives = {h: [] for h in HORIZONS}
    evaluated_tracks = _group(raw, included)
    for (uid, hi), values in evaluated_tracks.items():
        index = np.array(values)
        h = HORIZONS[hi]
        times = raw['time'][index]
        label, tile = data.target(index)
        seen = observed(raw)[index]
        c_logits, c_enc = ce.coarse(data.batch(index))
        h_logits, h_enc = heatmap.coarse(data.batch(index))
        a_logits, a_enc = anil.coarse(data.batch(index))
        c_fine = ce.fine_logp(c_enc, data.xyz[index], label, tile, data.hindex[index])
        h_fine = heatmap.fine_logp(h_enc, data.xyz[index], label, tile, data.hindex[index])
        a_fine = anil.fine_logp(a_enc, data.xyz[index], label, tile, data.hindex[index])
        counts = np.zeros(len(index), dtype=np.int16)
        anil_bias, ordinary_bias = torch.zeros_like(a_logits), torch.zeros_like(h_logits)
        histograms = torch.full_like(h_logits, 1 / CELLS)
        follows = None
        if write_assets:
            follows = {'personal-hotspots': np.zeros((len(index), CELLS), dtype=np.float32),
                       'personal-routes': np.zeros((len(index), CELLS), dtype=np.float32)}
        for cutoff in np.unique(times // REFRESH * REFRESH):
            on = times // REFRESH * REFRESH == cutoff
            support = support_for(raw, index, cutoff)
            counts[on] = len(support)
            if len(support) >= MIN_SUPPORT:
                sh, _ = heatmap.coarse(data.batch(support))
                sa, _ = anil.coarse(data.batch(support))
                mask = torch.ones(1, len(support), dtype=torch.bool)
                sh_labels = data.tile[support][None]
                hindex = data.hindex[support[:1]]
                ordinary_bias[on] = heatmap.head_bias(sh[None], sh_labels, mask, hindex, False)
                anil_bias[on] = anil.head_bias(sa[None], sh_labels, mask, hindex)
                histograms[on] = torch.tensor(history_dist(sh_labels[0].numpy()))
            if follows is not None:
                from forecast import PersonalModel
                r = take(raw, index[on])
                motion = mix_models[hi].predict(r)['Motion mixture']
                personal = PersonalModel(take(raw, support))
                for name, (law, _) in personal.predict(r, motion, h).items():
                    key = name.lower().replace(' ', '-')
                    follows[key][on] = mixture_tiles(law)
        count_tensor = torch.tensor(counts, dtype=torch.float32)
        plain_w = .7 * count_tensor / (count_tensor + 24)
        meta_w = meta.weight(data.hindex[index], count_tensor, data.features[index])
        plain_w[counts < MIN_SUPPORT] = 0
        meta_w[counts < MIN_SUPPORT] = 0
        prior_prob = h_logits.softmax(dim=1)
        histories = {
            'history': (prior_prob * (1 - plain_w[:, None]) + histograms * plain_w[:, None]),
            'meta-history': (prior_prob * (1 - meta_w[:, None]) + histograms * meta_w[:, None])}
        variants = {
            'heatmap-ce': (c_logits, c_fine), 'heatmap': (h_logits, h_fine),
            'head-tune': (h_logits + ordinary_bias, h_fine),
            'anil-prior': (a_logits, a_fine), 'anil': (a_logits + anil_bias, a_fine),
            **{key: (p.log(), h_fine) for key, p in histories.items()}}
        probs = {key: logits.softmax(dim=1).numpy() for key, (logits, _) in variants.items()}
        losses = {key: scores(logits, fine, tile).numpy() for key, (logits, fine) in variants.items()}
        baseline_keys = {'Stay': 'stay', 'Constant velocity': 'velocity', 'Learned drift': 'drift',
                         'Motion mixture': 'motion', 'Mixture without motion': 'motion-no-motion',
                         'Mixture without context': 'motion-no-context'}
        bdata = take(raw, index)
        for name, (law, _) in mix_models[hi].predict(bdata).items():
            key = baseline_keys[name]
            p = mixture_tiles(law)
            probs[key] = p
            losses[key] = raw_mix_scores(law, p, label.numpy(), tile.numpy())
        player = roster[uid]
        if write_assets:
            probs.update(follows)
            packs = np.stack([quantize(probs[key]) for key in VIEWS], axis=1)
            for j, t in enumerate(times):
                archives[h].append((int(t), player, int(counts[j]), packs[j].tobytes()))
        for cohort, keep in (('all', seen), ('adapt', seen & (times >= game['cut']+10) & (counts >= 8)),
                             ('early-style', seen & (counts >= 4) & (counts < 12)),
                             ('any-style', seen & (counts >= 4))):
            if not np.any(keep):
                continue
            for model, matrix in losses.items():
                block, near16, near32 = matrix[keep].mean(axis=0)
                record = {'split': split, 'game': game['id'], 'horizon': h, 'model': model, 'cohort': cohort,
                          'n': int(keep.sum()), 'block': float(block), 'near16': float(near16),
                          'near32': float(near32)}
                results.append(record)
                if cohort == 'adapt':
                    player_results.append({**record, 'player': player, 'own': uid == own,
                                           'support_mean': float(counts[keep].mean())})
    if write_assets:
        for h, records in archives.items():
            records.sort(key=lambda row: (row[0], row[1]))
            with gzip.open(out / f'{game["id"]}-{h}.bin.gz', 'wb', compresslevel=5) as stream:
                stream.write(struct.pack('<4sIHH', b'BW11', len(records), len(VIEWS), CELLS))
                for t, player, support, values in records:
                    stream.write(struct.pack('<HBB', t, player, support))
                    stream.write(values)
        frames = {str(t): [[roster[uid], round(player[1], 3), round(player[2], 3), round(player[3], 3),
                            round(player[4] % 360, 1)] for uid, player in frame[2].items()]
                  for t, frame in game['frames'].items()}
        with gzip.open(out / f'poses-{game["id"]}.json.gz', 'wt') as stream:
            json.dump({'id': game['id'], 'start': min(game['frames']), 'end': max(game['frames']),
                       'baseY': game['base_y'], 'split': split, 'own': roster.get(own),
                       'labels': ['You' if uid == own else f'Player {p:02}'
                                  for uid, p in sorted(roster.items(), key=lambda v:v[1])],
                       'frames': frames}, stream, separators=(',', ':'))
    print(f'{split} {game["id"]}: {len(included)} forecast starts', flush=True)
    return results, player_results


def matrix_from(module):
    return {name: tensor.detach().cpu().tolist() for name, tensor in module.state_dict().items()
            if name.endswith(('.weight', '.bias')) and not name.startswith('fine')}


def export_portable(path, models, baselines):
    # Forecast rendering uses the horizontal 16-block marginal.
    ce, main, anil, meta = models
    packed = {'version': 1, 'horizons': HORIZONS, 'views': VIEWS,
              'ce': matrix_from(ce), 'heatmap': matrix_from(main), 'anil': matrix_from(anil),
              'steps': (4+76*anil.step.detach().sigmoid()).tolist(),
              'historyWeights': [meta.bias.detach().tolist(), meta.feature.detach().tolist()],
              'baseline': []}
    for model in baselines:
        analogues = {}
        for name, (columns, analogue) in model.models.items():
            analogues[name] = {'columns': columns, 'center': analogue.center.tolist(),
                               'scale': analogue.scale.tolist(), 'features': analogue.index.data.astype('float32').tolist(),
                               'targets': analogue.targets.astype('float32').tolist()}
        packed['baseline'].append({'stay': [v.tolist() for v in model.stay],
                                   'velocity': [v.tolist() for v in model.velocity],
                                   'drift': [v.tolist() for v in model.drift],
                                   'driftCoefficients': model.ridge.coef_.tolist(),
                                   'driftIntercept': model.ridge.intercept_.tolist(),
                                   'analogues': analogues})
    with gzip.open(path, 'wt', compresslevel=5) as stream:
        json.dump(packed, stream, separators=(',', ':'), allow_nan=False)


def parity_out(path, data, games, models, baselines):
    rows = data.raw
    ce, heatmap, anil, meta = models
    fixtures = []
    with torch.no_grad():
        for hi, h in enumerate(HORIZONS):
            for game in games[:2]:
                subset = np.flatnonzero((rows['game'] == game['id']) & (rows['hindex'] == hi))
                if not len(subset):
                    continue
                track = subset[rows['uid'][subset] == rows['uid'][subset[0]]]
                index = track[min(len(track)-1, len(track)*3//4)]
                support = support_for(rows, track, rows['time'][index]//REFRESH*REFRESH)
                count = len(support)
                c, _ = ce.coarse(data.batch([index]))
                q, _ = heatmap.coarse(data.batch([index]))
                a, _ = anil.coarse(data.batch([index]))
                fixture = {'horizon': h, 'features': rows['features'][index].tolist(),
                           'xyz': rows['xyz'][index].tolist(), 'velocity': rows['velocity'][index].tolist(),
                           'yaw': float(rows['yaw'][index]),
                           'support': np.column_stack([rows['xyz'][support], rows['velocity'][support],
                                                       rows['features'][support], rows['future'][support],
                                                       rows['yaw'][support]]).tolist()}
                expect = {'heatmap-ce': c.softmax(-1)[0].tolist(), 'heatmap': q.softmax(-1)[0].tolist(),
                          'anil-prior': a.softmax(-1)[0].tolist(),
                          'head-tune': q.softmax(-1)[0].tolist(), 'anil': a.softmax(-1)[0].tolist(),
                          'history': q.softmax(-1)[0].tolist(), 'meta-history': q.softmax(-1)[0].tolist()}
                if count >= MIN_SUPPORT:
                    sh, _ = heatmap.coarse(data.batch(support))
                    sa, _ = anil.coarse(data.batch(support))
                    valid = torch.ones(1, count, dtype=torch.bool)
                    ti = data.tile[support][None]
                    hh = data.hindex[[index]]
                    ordinary = heatmap.head_bias(sh[None], ti, valid, hh, False)
                    adapted = anil.head_bias(sa[None], ti, valid, hh)
                    hist = torch.tensor(history_dist(ti[0].numpy()))
                    w = meta.weight(hh, torch.tensor([count]), data.features[[index]])[0]
                    pw = .7*count/(count+24)
                    pq = q.softmax(-1)[0]
                    expect.update({'head-tune': (q+ordinary).softmax(-1)[0].tolist(),
                                   'anil': (a+adapted).softmax(-1)[0].tolist(),
                                   'history': ((1-pw)*pq+pw*hist).tolist(),
                                   'meta-history': ((1-w)*pq+w*hist).tolist()})
                mapping = {'Stay': 'stay', 'Constant velocity': 'velocity', 'Learned drift': 'drift',
                           'Motion mixture': 'motion', 'Mixture without motion': 'motion-no-motion',
                           'Mixture without context': 'motion-no-context'}
                r = take(rows, np.array([index]))
                b = baselines[hi].predict(r)
                for label, (law, _) in b.items():
                    expect[mapping[label]] = mixture_tiles(law)[0].tolist()
                from forecast import PersonalModel
                pmodel = PersonalModel(take(rows, support))
                for name, (law, _) in pmodel.predict(r, b['Motion mixture'], h).items():
                    expect[name.lower().replace(' ', '-')] = mixture_tiles(law)[0].tolist()
                fixture['expected'] = expect
                fixtures.append(fixture)
    path.write_text(json.dumps(fixtures, separators=(',', ':')))


def aggregate(rows):
    groups = defaultdict(list)
    for r in rows:
        groups[r['split'], r['game'], r['horizon'], r['model'], r['cohort']].append(r)
    results = []
    for (split, game, h, m, cohort), part in groups.items():
        n = sum(r['n'] for r in part)
        values = {k: sum(r[k] * r['n'] for r in part)/n for k in ('block', 'near16', 'near32')}
        results.append({'split': split, 'game': game, 'horizon': h, 'model': m,
                        'cohort': cohort, 'n': n, **values})
    return results


def fit_suite(data, training, epochs, iterations, seed):
    eligible = np.flatnonzero(np.isin(data.raw['game'], tuple(training)) & observed(data.raw))
    episodes = tasks(data, set(training))
    print('Training examples', len(eligible), 'support/query episodes', len(episodes), flush=True)
    ce = supervised(data, 'exact', eligible, epochs, seed)
    ms = supervised(data, 'multiscale', eligible, epochs, seed)
    anil = fit_anil(data, ms, episodes, iterations, seed+1)
    meta = fit_meta_history(data, ms, episodes, max(60, iterations//2), seed+2)
    baselines = []
    for i, h in enumerate(HORIZONS):
        choose = ((data.raw['hindex'] == i) & np.isin(data.raw['game'], tuple(training)))
        baselines.append(PlayerModel(take(data.raw, choose), h))
    return (ce, ms, anil, meta), baselines


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('tracks', type=Path)
    parser.add_argument('--protocol', type=Path, default=Path(__file__).with_name('heatmap_protocol.json'))
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--assets', type=Path, required=True)
    parser.add_argument('--epochs', type=int, default=10)
    parser.add_argument('--iterations', type=int, default=300)
    parser.add_argument('--threads', type=int, default=4)
    args = parser.parse_args()
    torch.set_num_threads(args.threads)
    args.out.mkdir(parents=True, exist_ok=True)
    args.assets.mkdir(parents=True, exist_ok=True)
    protocol = json.loads(args.protocol.read_text())
    games = load_games(args.tracks)
    data = data_for(games)
    results, players = [], []
    for split, training in (('development', protocol['train']), ('test', protocol['train']+protocol['development'])):
        models, baselines = fit_suite(data, training, args.epochs, args.iterations, protocol['seeds'][0])
        for game in games:
            if game['id'] in protocol[split]:
                r, p = evaluate_game(data, game, models, baselines, args.assets, split, write_assets=True)
                results.extend(r)
                players.extend(p)
        if split == 'test':
            portable = args.out / 'bedwars-forecast.json.gz'
            export_portable(portable, models, baselines)
            parity_out(args.out / 'parity.json', data, [g for g in games if g['id'] in protocol[split]], models, baselines)
            torch.save({key: model.state_dict() for key, model in zip(('ce','heatmap','anil','meta-history'), models)},
                       args.out / 'heatmap-models.pt')
        write_csv(args.out / 'game-scores.csv', aggregate(results))
        write_csv(args.out / 'player-scores.csv', players)
    summary = {'version': 1, 'protocol': protocol, 'models': VIEWS, 'games': [
               {'id': game['id'], 'split': split, 'frames': len(game['frames']), 'end': max(game['frames']),
                'cut': game['cut']} for split in ('development', 'test') for game in games if game['id'] in protocol[split]],
               'sha256': hashlib.sha256(args.tracks.read_bytes()).hexdigest(), 'scores': aggregate(results),
               'players': players}
    (args.assets / 'report.json').write_text(json.dumps(summary, separators=(',', ':')))


if __name__ == '__main__':
    main()
