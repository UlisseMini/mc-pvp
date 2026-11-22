"""Summarize held-out scores and render block marginals for one recorded forecast."""
import argparse
from collections import Counter, defaultdict
import csv
import json
from pathlib import Path

import joblib
import matplotlib.pyplot as plt
from matplotlib.colors import LogNorm
import numpy as np
from scipy.special import ndtr

from forecast import HORIZONS, density_rows, load_games, player_rows, take, write_csv


def average(values):
    return float(np.mean(values)) if values else None


def read_csv(path):
    with path.open() as file:
        return list(csv.DictReader(file))


def individual_summary(path):
    games = read_csv(path)
    result = {}
    for horizon in HORIZONS:
        selected = [r for r in games if int(r['horizon']) == horizon and r['group'] == 'all']
        baselines = {r['game']: float(r['block_nll']) for r in selected if r['model'] == 'Stay'}
        table = {}
        for name in dict.fromkeys(r['model'] for r in selected):
            rows = [r for r in selected if r['model'] == name]
            scores = [float(r['block_nll']) for r in rows]
            table[name] = {'nll': average(scores),
                           'kernel': average([float(r['gaussian_score']) for r in rows]),
                           'state_nll': average([float(r['state_nll']) for r in rows]),
                           'joint_nll': average([float(r['joint_nll']) for r in rows]),
                           'delta_by_game': {r['game']: float(r['block_nll']) - baselines[r['game']] for r in rows},
                           'origins': sum(int(r['n']) for r in rows), 'seen': sum(int(r['observed']) for r in rows)}
        result[horizon] = table
    return result


def personal_summary(path):
    data = read_csv(path)
    result = {}
    for horizon in HORIZONS:
        group = [r for r in data if int(r['horizon']) == horizon and int(r['train_observed']) >= 20
                 and int(r['observed']) >= 20]
        baseline = {(r['game'], r['player']): float(r['block_nll']) for r in group if r['model'] == 'Motion mixture'}
        result[horizon] = {}
        for model in dict.fromkeys(r['model'] for r in group):
            rows = [r for r in group if r['model'] == model]
            delta = {f'{r["game"]}/{r["player"]}': float(r['block_nll']) - baseline[r['game'], r['player']]
                     for r in rows}
            result[horizon][model] = {'nll': average([float(r['block_nll']) for r in rows]),
                                      'kernel': average([float(r['gaussian_score']) for r in rows]),
                                      'n': len(rows), 'avg_delta': average(list(delta.values())),
                                      'won': sum(v < 0 for v in delta.values()), 'delta_by_player': delta}
    return result


def density_summary(path):
    data = read_csv(path)
    groups = defaultdict(list)
    for row in data:
        groups[int(row['horizon']), row['model'], row['game']].append(row)
    scores = defaultdict(list)
    for (horizon, model, game), rows in groups.items():
        count = sum(int(r['n']) for r in rows)
        scores[horizon, model].append({'game': game, 'origins': len(rows), 'n': count,
                                      'nll': sum(float(r['block_loss']) for r in rows) / count,
                                      'kernel': sum(float(r['kernel_loss']) for r in rows) / count,
                                      'count_nll': average([float(r['count_nll']) for r in rows]),
                                      'count_mae': average([float(r['count_absolute_error']) for r in rows]),
                                      'joint_nll': average([float(r['joint_nll']) for r in rows])})
    result = {h: {} for h in HORIZONS}
    for (horizon, model), games in scores.items():
        result[horizon][model] = {k: average([g[k] for g in games])
                                  for k in ('nll', 'kernel', 'count_nll', 'count_mae', 'joint_nll')}
        result[horizon][model]['by_game'] = {g['game']: g for g in games}
    return result


def histories(games):
    result = []
    for game in games:
        frames = game['frames']
        tracks = defaultdict(dict)
        focus = next(iter(frames.values()))[0]
        for t, (_, _, players) in frames.items():
            for uid, player in players.items():
                tracks[uid][t] = player
        for uid, track in tracks.items():
            xyz = np.array([p[1:4] for p in track.values()])
            radius = np.linalg.norm(xyz[:, [0, 2]], axis=1)
            speed = [np.linalg.norm(np.array(p[1:4]) - track[t - 2][1:4]) / 2
                     for t, p in track.items() if t - 2 in track]
            result.append({'game': game['id'], 'player': next(iter(track.values()))[0], 'recorder': uid == focus,
                           'observed_seconds': len(track), 'first_s': min(track), 'last_s': max(track),
                           'within_30_from_center': (radius < 30).mean(),
                           'outside_65_from_center': (radius > 65).mean(),
                           'slow_2s_fraction': float((np.array(speed) < .5).mean()) if speed else None,
                           'median_radius': float(np.median(radius)), 'y_span_90pct': float(np.percentile(xyz[:, 1], 95)
                                                                                         - np.percentile(xyz[:, 1], 5))})
    return result


def horizontal_mass(law, grid):
    def marginal(axis):
        center, scale = law.centers[0, :, axis], law.scales[0, :, axis]
        lower = (grid[:, None] - center) / scale
        upper = lower + 1 / scale
        return np.where(lower >= 0, ndtr(-lower) - ndtr(-upper), ndtr(upper) - ndtr(lower))
    return (marginal(2) * law.weights[0]) @ marginal(0).T


def forecast_figure(games, artifact, out):
    horizons = (10, 60)
    game_id = artifact['illustration'][10][0]
    game = next(g for g in games if g['id'] == game_id)
    frames = game['frames']
    times = [t for t in frames if t % 5 == 2 and all(t+h in frames for h in horizons)]
    t = min(times, key=lambda s: abs(s - max(frames) / 3))
    own, _, current = frames[t]
    if own not in current:
        own = next(iter(current))
    others = [u for u in current if u != own]
    # Choose the other figure subject using just the history available at issue time.
    frequencies = Counter(u for s, frame in frames.items() if s <= t for u in frame[2])
    opponent = max(others, key=frequencies.get)
    past = np.array([p[1:4] for s, frame in frames.items() if t-180 <= s <= t for p in frame[2].values()])
    extent = int(np.ceil(np.percentile(abs(past[:, [0, 2]]), 99.7) / 10) * 10 + 12)
    grid = np.arange(-extent, extent)
    plt.rcParams.update({'font.family': 'DejaVu Sans', 'axes.titlesize': 11, 'font.size': 10})
    fig, axes = plt.subplots(3, 2, figsize=(10.5, 13.6), sharex=True, sharey=True, layout='constrained')
    fig.set_facecolor('#0b1222')
    norm = LogNorm(vmin=.000008, vmax=.012)
    details = []
    for j, horizon in enumerate(horizons):
        _, model, density = artifact['illustration'][horizon]
        rows = player_rows([game], horizon)
        future = frames[t+horizon][2]
        for i, uid in enumerate((own, opponent, None)):
            ax = axes[i, j]
            ax.set_facecolor('#070d17')
            for spine in ax.spines.values():
                spine.set_color('#293951')
            ax.tick_params(colors='#c3cedc', labelsize=8)
            if uid is None:
                drow = next(r for r in density_rows([game], horizon) if r[1] == t)
                law, count = density.predict(drow)['Blended density']
                p = None
                title = f'Any player · {horizon}s\n{count:.1f} expected in capture · {len(future)} recorded'
                now = [p[1:4] for p in current.values()]
                actual = [p[1:4] for p in future.values()]
            else:
                row = take(rows, (rows['time'] == t) & (rows['uid'] == uid))
                law, p = model.predict(row)['Motion mixture']
                count = None
                title = f'{current[uid][0]} · {horizon}s\n{p[0]:.0%} chance of a recorded position'
                now = [current[uid][1:4]]
                actual = [future[uid][1:4]] if uid in future else []
            map_ = horizontal_mass(law, grid)
            im = ax.imshow(map_, origin='lower', extent=(-extent, extent, -extent, extent), cmap='magma',
                           norm=norm, interpolation='nearest')
            ax.scatter(past[:, 0], past[:, 2], color='#a6b6d2', alpha=.16, s=2, linewidths=0)
            now = np.array(now).reshape(-1, 3)
            actual = np.array(actual).reshape(-1, 3)
            ax.scatter(now[:, 0], now[:, 2], s=33, facecolor='none', edgecolor='white', linewidths=1)
            ax.scatter(actual[:, 0], actual[:, 2], s=32, color='#56e7df', marker='x', linewidths=1.25)
            ax.set(xlim=(-extent, extent), ylim=(-extent, extent))
            ax.set_title(title, loc='left', color='#eef4ff', pad=7, linespacing=1.45)
            details.append({'subject': current[uid][0] if uid else 'Any player', 'horizon': horizon,
                            'future_observed': len(actual), 'forecast_seen': float(p[0]) if p is not None else None,
                            'forecast_count': count, 'mass_inside_panel': float(map_.sum())})
    fig.suptitle(f'BedWars position forecasts\n{game_id} · issued {t//60}:{t%60:02} into the match',
                 fontsize=19, weight='bold', color='#eef4ff')
    fig.supxlabel('World X (blocks)  ·  ○ at issue time  ·  × recorded outcome', color='#eef4ff')
    fig.supylabel('World Z (blocks)', color='#eef4ff', fontsize=10)
    cbar = fig.colorbar(im, ax=axes, shrink=.63, aspect=40, pad=.025)
    cbar.set_label('Probability per horizontal block (Y summed)', color='#eef4ff', fontsize=9)
    cbar.ax.yaxis.set_tick_params(color='#c3cedc', labelcolor='#c3cedc', labelsize=8)
    cbar.outline.set_edgecolor('#39485b')
    fig.savefig(out, dpi=160, facecolor=fig.get_facecolor())
    plt.close(fig)
    return {'game': game_id, 'issued_second': t, 'panels': details}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('tracks', type=Path)
    parser.add_argument('results', type=Path)
    parser.add_argument('--figure', type=Path, required=True)
    args = parser.parse_args()
    games = load_games(args.tracks)
    artifact = joblib.load(args.results / 'models.joblib')
    result = {'individual': individual_summary(args.results / 'individual-games.csv'),
              'personal': personal_summary(args.results / 'individual-players.csv'),
              'density': density_summary(args.results / 'density.csv'),
              'figure': forecast_figure(games, artifact, args.figure),
              'learned_models': {horizon: len(models) for horizon, models in artifact['personal'].items()}}
    (args.results / 'summary.json').write_text(json.dumps(result, indent=2) + '\n')
    write_csv(args.results / 'track-histories.csv', histories(games))


if __name__ == '__main__':
    main()
