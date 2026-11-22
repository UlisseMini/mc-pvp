"""Fit block-valued BedWars forecasts, score held-out games, and save fitted models."""
import argparse
from dataclasses import dataclass
import csv
import gzip
import json
from pathlib import Path

import joblib
import numpy as np
from scipy.spatial import cKDTree
from scipy.special import gammaln, ndtr
from sklearn.linear_model import LogisticRegression, Ridge


HORIZONS = (5, 10, 30, 60, 120)
KERNEL_RADIUS = 8.0
DRAWS = 96
MOTION = (0, 1, 2, 3, 4, 5, 6, 7, 8)
CONTEXT = tuple(range(9, 17))


def cube_mass(center, scale, block):
    lower = (block - center) / scale
    upper = lower + 1 / scale
    # Use the lower tail on either side for accurate CDF differences.
    mass = np.where(lower >= 0, ndtr(-lower) - ndtr(-upper), ndtr(upper) - ndtr(lower))
    return mass.prod(axis=-1)


@dataclass
class Law:
    centers: np.ndarray
    scales: np.ndarray
    weights: np.ndarray

    def block_mass(self, block):
        return np.sum(cube_mass(self.centers, self.scales, block[:, None, :]) * self.weights, axis=1)

    def samples(self, rng, draws):
        cdf = np.cumsum(self.weights, axis=1)
        cdf[:, -1] = 1
        row = np.arange(len(cdf))[:, None]
        index = np.searchsorted((cdf + row).ravel(), rng.random((len(cdf), draws)) + row,
                                side='right') % cdf.shape[1]
        return np.floor(self.centers[row, index] + self.scales[row, index] * rng.standard_normal((*index.shape, 3)))

    def blend(self, other, weight):
        return Law(np.concatenate((self.centers, other.centers), axis=1),
                   np.concatenate((self.scales, other.scales), axis=1),
                   np.concatenate((self.weights * weight, other.weights * (1 - weight)), axis=1))


def gaussian_law(centers, scales, weights):
    return Law(centers, np.broadcast_to(scales, centers.shape), np.broadcast_to(weights, centers.shape[:2]))


def position(player):
    return np.asarray(player[1:4])


def local_to_world(local, yaw):
    c, s = np.cos(yaw)[..., None], np.sin(yaw)[..., None]
    x, y, z = np.moveaxis(local, -1, 0)
    return np.stack((c * x - s * z, y, s * x + c * z), axis=-1)


def player_rows(games, horizon):
    columns = {key: [] for key in ('game', 'time', 'uid', 'name', 'own', 'xyz', 'future', 'yaw', 'features', 'velocity')}
    for game in games:
        frames = game['frames']
        for t, (own, _, visible) in frames.items():
            if t + horizon not in frames:
                continue
            future = frames[t + horizon][2]
            prev2 = frames.get(t - 2, (None, None, {}))[2]
            prev8 = frames.get(t - 8, (None, None, {}))[2]
            for uid, player in visible.items():
                xyz = position(player)
                yaw = np.deg2rad(player[4] % 360)
                histories = []
                for seconds, past in ((2, prev2), (8, prev8)):
                    velocity = (xyz - position(past[uid])) / seconds if uid in past else np.zeros(3)
                    if np.linalg.norm(velocity) > 9:
                        velocity = np.zeros(3)  # Exclude respawn teleports from velocity.
                    histories.append(velocity)
                local = local_to_world(np.asarray(histories)[None, :, :], np.array([-yaw]))[0]
                radius = np.linalg.norm(xyz[[0, 2]])
                toward_center = local_to_world(xyz[None, None, :], np.array([-yaw]))[0, 0, 2]
                distance = np.linalg.norm(xyz - position(visible[own])) if own in visible else 160
                item = player[6]
                features = [*local.ravel(), uid in prev2, uid in prev8, player[5],
                            radius / 35, (xyz[1] - game['base_y']) / 10, toward_center / 55,
                            np.log1p(t / 30), len(visible) / 6, min(distance, 200) / 50,
                            uid == own, item in (267, 268, 272, 276, 283)]
                values = (game['id'], t, uid, player[0], uid == own, xyz,
                          position(future[uid]) if uid in future else [np.nan] * 3,
                          yaw, features, histories[0] * [1, 0, 1])
                for key, value in zip(columns, values):
                    columns[key].append(value)
    return {col: np.asarray(values) for col, values in columns.items()}


def take(rows, index):
    return {k: v[index] for k, v in rows.items()}


def observed(rows):
    return np.isfinite(rows['future'][:, 0])


def motion_projection(rows, horizon):
    return rows['xyz'] + rows['velocity'] * horizon


def fit_residuals(residual):
    """Fit a narrow/wide Gaussian mixture by maximum likelihood with fixed mean."""
    square = residual ** 2
    square[:, [0, 2]] = square[:, [0, 2]].mean(axis=1)[:, None]
    width = np.sqrt(square.mean(axis=0) + .7 ** 2)
    scales, weights = width * np.array([[.3], [1.6]]), np.array([.5, .5])
    for _ in range(50):
        likelihood = -(.5 * square[:, None, :] / scales ** 2 + np.log(scales)).sum(axis=2) + np.log(weights)
        responsibility = np.exp(likelihood - likelihood.max(axis=1, keepdims=True))
        responsibility /= responsibility.sum(axis=1, keepdims=True)
        weights = np.maximum(responsibility.mean(axis=0), .005)
        weights /= weights.sum()
        scales = np.sqrt((responsibility.T @ square) / responsibility.sum(axis=0)[:, None] + .55 ** 2)
    return scales, weights


def gaussian_residuals(center, params):
    scales, weights = params
    return gaussian_law(np.repeat(center[:, None, :], len(weights), axis=1), scales, weights)


class Analogues:
    """Gaussian-smoothed distribution of outcomes from similar recorded starts."""

    def __init__(self, features, targets):
        self.center = np.median(features, axis=0)
        self.scale = np.maximum(np.std(features, axis=0), .75)
        self.index = cKDTree((features - self.center) / self.scale)
        self.targets = targets

    def query(self, features, maximum=48):
        width = min(self.index.n, maximum)
        d, idx = self.index.query((features - self.center) / self.scale, k=list(range(1, width + 1)))
        w = np.exp(-.5 * d ** 2 / np.maximum(d[:, -1:] ** 2, .25))
        return self.targets[idx], w / w.sum(axis=1, keepdims=True)


class PlayerModel:
    def __init__(self, train, horizon, ablate=True):
        self.h = horizon
        seen = observed(train)
        full = take(train, seen)
        target, xyz, features, yaw = (full[k] for k in ('future', 'xyz', 'features', 'yaw'))
        world = target - xyz
        aligned = local_to_world(world[:, None, :], -yaw)[:, 0, :]
        self.stay = fit_residuals(world)
        self.velocity = fit_residuals(target - motion_projection(full, horizon))
        self.ridge = Ridge(alpha=100).fit(features, aligned)
        self.drift = fit_residuals(world - self.drift_center(full) + xyz)
        self.models = {'Motion mixture': (MOTION + CONTEXT, Analogues(features, aligned))}
        if ablate:
            self.models['Mixture without motion'] = (CONTEXT, Analogues(features[:, CONTEXT], aligned))
            self.models['Mixture without context'] = (MOTION, Analogues(features[:, MOTION], aligned))
        self.availability = np.array([(seen[train['own'] == q].sum() + 1) / ((train['own'] == q).sum() + 2)
                                      for q in (False, True)])
        self.logistic = LogisticRegression(C=.25).fit(train['features'], seen) if seen.min() != seen.max() else None

    def drift_center(self, rows):
        delta = self.ridge.predict(rows['features'])[:, None, :]
        return rows['xyz'] + local_to_world(delta, rows['yaw'])[:, 0, :]

    def predict(self, rows):
        xyz, yaw, features = (rows[k] for k in ('xyz', 'yaw', 'features'))
        stay = gaussian_residuals(xyz, self.stay)
        base_avail = self.availability[rows['own'].astype(int)]
        avail = self.logistic.predict_proba(features)[:, 1] if self.logistic is not None else base_avail
        result = {'Stay': (stay, base_avail),
                  'Constant velocity': (gaussian_residuals(motion_projection(rows, self.h), self.velocity), base_avail),
                  'Learned drift': (gaussian_residuals(self.drift_center(rows), self.drift).blend(stay, .88), avail)}
        scale = [2.2 + np.sqrt(self.h) * .55, 1.0 + np.sqrt(self.h) * .15, 2.2 + np.sqrt(self.h) * .55]
        for name, (columns, analogue) in self.models.items():
            delta, weights = analogue.query(features[:, columns])
            centers = local_to_world(delta, yaw) + xyz[:, None, :]
            result[name] = (gaussian_law(centers, scale, weights).blend(stay, .77), avail)
        return result


def route_features(rows):
    return np.column_stack((rows['xyz'][:, [0, 2]] / 11, rows['xyz'][:, 1] / 6,
                            rows['velocity'][:, [0, 2]] / 2, rows['features'][:, [6, 8, 12, 14]]))


class PersonalModel:
    """Within-arena adaptation that shrinks toward the out-of-game model."""

    def __init__(self, train):
        self.n = len(train['xyz'])
        self.n_seen = int(observed(train).sum())
        self.p_seen = (self.n_seen + 3) / (self.n + 6)
        self.analogue = None
        if self.n_seen:
            seen = take(train, observed(train))
            self.analogue = Analogues(route_features(seen), seen['future'])
            # Uniform-time coverage of the player's historical destinations.
            self.hotspots = seen['future'][np.linspace(0, self.n_seen - 1, min(64, self.n_seen), dtype=int)]

    def predict(self, rows, shared, horizon):
        general, visibility = shared
        weight = min(.85, self.n_seen / (self.n_seen + 45))
        p = (self.n * self.p_seen + 30 * visibility) / (self.n + 30)
        candidates = {'Personal hotspots': (general, p), 'Personal routes': (general, p)}
        if self.analogue is not None:
            scale = [2.2 + np.sqrt(horizon) * .55, 1.0 + np.sqrt(horizon) * .15, 2.2 + np.sqrt(horizon) * .55]
            centers = np.broadcast_to(self.hotspots, (len(rows['time']), *self.hotspots.shape))
            hotspots = gaussian_law(centers, scale, np.ones(len(self.hotspots)) / len(self.hotspots))
            centers, weights = self.analogue.query(route_features(rows))
            routes = gaussian_law(centers, scale, weights)
            candidates = {'Personal hotspots': (hotspots.blend(general, weight), p),
                          'Personal routes': (routes.blend(general, weight), p)}
        return candidates


def gaussian_kernel(x, y):
    return np.exp(-np.sum((x - y) ** 2, axis=-1) / (2 * KERNEL_RADIUS ** 2))


def kernel_score(law, truth, seed):
    rng = np.random.default_rng(seed)
    a, b = law.samples(rng, DRAWS), law.samples(rng, DRAWS)
    return (1 + gaussian_kernel(a, b) - 2 * gaussian_kernel(a, truth[:, None, :])).mean(axis=1)


def log_loss(probability):
    # Guard numerical underflow, even in the tails of a normalized Gaussian law.
    return -np.log(np.maximum(np.finfo(float).tiny, probability))


def score_players(rows, model, personal=None):
    scores = {}
    for start in range(0, len(rows['time']), 128):
        group = take(rows, slice(start, start + 128))
        future = np.floor(group['future'])
        seen = observed(group)
        safe_future = np.where(seen[:, None], future, 0)
        candidates = model.predict(group)
        if personal is not None:
            candidates = {name: candidates[name] for name in ('Stay', 'Constant velocity', 'Motion mixture')}
            candidates.update(personal.predict(group, candidates['Motion mixture'], model.h))
        for name, (law, p) in candidates.items():
            block = np.where(seen, log_loss(law.block_mass(safe_future)), np.nan)
            state = log_loss(np.where(seen, p, 1 - p))
            kernel = np.where(seen, kernel_score(law, safe_future, 3319 + start), np.nan)
            metrics = np.stack((block, kernel, state, state + np.nan_to_num(block)), axis=1)
            scores.setdefault(name, []).append(metrics)
    return {name: np.concatenate(chunks) for name, chunks in scores.items()}


def aggregate(scores, index):
    return {key: float(values[np.isfinite(values)].mean()) if np.any(np.isfinite(values)) else None
            for key, values in zip(('block_nll', 'gaussian_score', 'state_nll', 'joint_nll'), scores[index].T)}


def formation(frame):
    return np.array([position(p) for p in frame[2].values()]).reshape(-1, 3)


def density_rows(games, horizon):
    rows = []
    for game in games:
        frames = game['frames']
        for t in sorted(frames):
            if t % 5 != 2 or t + horizon not in frames:
                continue
            current = formation(frames[t])
            past = [formation(frames[s]) for s in range(max(2, t - 30), t + 1, 5) if s in frames]
            recent = np.concatenate(past + [current])
            base = [0, game['base_y'], 0]
            feat = [np.log1p(t / 30), len(current) / 5, np.mean([len(p) for p in past]) / 5]
            rows.append((game['id'], t, current, recent, base, feat, formation(frames[t + horizon])))
    return rows


def cloud(points, scale):
    return gaussian_law(points[None, :, :], scale, np.ones(len(points)) / len(points))


def repeat_law(law, n):
    return Law(np.repeat(law.centers, n, axis=0), np.repeat(law.scales, n, axis=0), np.repeat(law.weights, n, axis=0))


def cloud_mass(law, truth):
    return (cube_mass(law.centers, law.scales, truth[:, None, :]) * law.weights).sum(axis=1)


class DensityModel:
    """Direct future formation KDE and expected observed count, without player identities."""

    def __init__(self, train, horizon):
        self.h = horizon
        features = np.array([r[5] for r in train])
        counts = np.array([len(r[6]) for r in train])
        self.count = Ridge(alpha=20).fit(features, counts)
        self.features = features
        self.formations = [r[6] - r[4] for r in train]
        self.prior_points = np.concatenate(self.formations)
        self.widths = {}
        for k in (2, 3):
            options = [(xz, y) for xz in (3, 7, 13, 22, 36, 55) for y in (2, 5, 10, 19)]
            losses = []
            for xz, y in options:
                width = [xz, y, xz]
                test = (log_loss(cloud_mass(cloud(r[k], width), np.floor(r[6]))) for r in train if len(r[k]))
                losses.append(sum(values.sum() for values in test))
            xz, y = options[int(np.argmin(losses))]
            self.widths[k] = [xz, y, xz]

    def predict(self, row):
        _, _, current, recent, base, features, _ = row
        delta = (self.features - features) * [1.5, .7, .7]
        distance = (delta ** 2).sum(axis=1)
        nearest = np.argsort(distance, kind='stable')[:24]
        points = np.concatenate([self.formations[i] for i in nearest])
        if not len(points):
            points = self.prior_points
        points = points[np.linspace(0, len(points) - 1, min(128, len(points)), dtype=int)]
        direct = cloud(points + base, [7, 2.5, 7])
        # Tail support is shared by the spatial alternatives.
        broad = cloud(np.array([base]), [95, 27, 95])
        direct = direct.blend(broad, .95)
        stay = cloud(current, self.widths[2]).blend(broad, .98) if len(current) else broad
        recent = cloud(recent, self.widths[3]).blend(broad, .98) if len(recent) else broad
        predicted = float(np.maximum(.1, self.count.predict([features])[0]))
        baseline = max(.1, len(current))
        return {'Current density': (stay, baseline), 'Recent density': (recent, baseline),
                'Learned density': (direct, predicted), 'Blended density': (stay.blend(direct, .65), predicted)}


def density_scores(model, rows):
    result = []
    for row in rows:
        game, t, _, _, _, _, future = row
        truth = np.floor(future)
        n = len(truth)
        for name, (law, count) in model.predict(row).items():
            count_nll = count - n * np.log(count) + gammaln(n + 1)
            spatial = log_loss(cloud_mass(law, truth))
            kernel = kernel_score(repeat_law(law, n), truth, 3319 + t)
            result.append({'game': game, 'horizon': model.h, 'time': t, 'model': name, 'n': n,
                           'block_loss': spatial.sum(), 'kernel_loss': kernel.sum(), 'count_nll': count_nll,
                           'count_absolute_error': abs(n - count), 'predicted_count': count,
                           'joint_nll': count_nll + spatial.sum()})
    return result


def write_csv(path, rows):
    with path.open('w', newline='') as file:
        if rows:
            writer = csv.DictWriter(file, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(rows)


def score_fold(game, model, rows, player_scores):
    scores = score_players(rows, model)
    for group, index in [('all', np.ones(len(rows['time']), dtype=bool)),
                         ('recorder', rows['own']), ('others', ~rows['own'])]:
        if np.any(index):
            for name, matrix in scores.items():
                player_scores.append({'game': game['id'], 'horizon': model.h, 'group': group, 'model': name,
                                      'n': int(index.sum()), 'observed': int(observed(rows)[index].sum()),
                                      **aggregate(matrix, index)})


def fit_personal_roster(rows):
    return {(game, uid): PersonalModel(take(rows, (rows['game'] == game) & (rows['uid'] == uid)))
            for game in np.unique(rows['game'])
            for uid in np.unique(rows['uid'][rows['game'] == game])}


def evaluate(games, out):
    global_scores, personal_scores, density_results = [], [], []
    trained, personal_models, personal_refit, illustration = {}, {}, {}, {}
    for horizon in HORIZONS:
        rows = player_rows(games, horizon)
        dense = density_rows(games, horizon)
        horizons_models = {}
        for game in games:
            key = game['id']
            holdout = take(rows, rows['game'] == key)
            train = take(rows, rows['game'] != key)
            if not len(holdout['time']) or observed(train).sum() < 25:
                continue
            model = PlayerModel(train, horizon)
            score_fold(game, model, holdout, global_scores)
            count_model = DensityModel([r for r in dense if r[0] != key], horizon)
            density_results.extend(density_scores(count_model, [r for r in dense if r[0] == key]))
            if key == max(games, key=lambda g: len(g['frames']))['id']:
                illustration[horizon] = (key, model, count_model)
            cut = game['cut']
            for player in np.unique(holdout['uid']):
                own_rows = take(holdout, holdout['uid'] == player)
                earlier = take(own_rows, own_rows['time'] + horizon <= cut)
                later = take(own_rows, own_rows['time'] >= cut + 10)
                personal = PersonalModel(earlier)
                horizons_models[(key, player)] = personal
                if not len(later['time']):
                    continue
                matrices = score_players(later, model, personal)
                record = {'game': key, 'horizon': horizon, 'player': own_rows['name'][0],
                          'recorder': bool(own_rows['own'][0]), 'train': personal.n, 'train_observed': personal.n_seen,
                          'n': len(later['time']), 'observed': int(observed(later).sum())}
                for name, matrix in matrices.items():
                    personal_scores.append({**record, 'model': name, **aggregate(matrix, slice(None))})
        personal_models[horizon] = horizons_models
        personal_refit[horizon] = fit_personal_roster(rows)
        trained[horizon] = (PlayerModel(rows, horizon, ablate=False), DensityModel(dense, horizon))
        print(f'{horizon:>3}s: {len(rows["time"]):>6} player origins, {int(observed(rows).sum()):>6} paired locations, '
              f'{len(dense)} density origins', flush=True)
    write_csv(out / 'individual-games.csv', global_scores)
    write_csv(out / 'individual-players.csv', personal_scores)
    write_csv(out / 'density.csv', density_results)
    joblib.dump({'shared': trained, 'personal': personal_models, 'personal_refit': personal_refit,
                 'illustration': illustration}, out / 'models.joblib', compress=3)


def load_games(path):
    with gzip.open(path, 'rt') as stream:
        games = json.load(stream)['matches']
    for game in games:
        game['frames'] = {int(t): v for t, v in game['frames'].items()}
        first = game['frames'][min(game['frames'])]
        game['base_y'] = float(first[2][first[0]][2]) if first[0] in first[2] else 75.
        game['cut'] = int(.55 * max(game['frames']))
    return games


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('tracks', type=Path)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    evaluate(load_games(args.tracks), args.out)


if __name__ == '__main__':
    from forecast import main as run
    run()
