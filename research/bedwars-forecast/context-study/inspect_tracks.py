"""Render development tracks with path color and time-aligned distance/pace strips."""
from collections import defaultdict
import gzip
import json
from pathlib import Path

import matplotlib.pyplot as plt
from matplotlib.collections import LineCollection
import matplotlib as mpl
import numpy as np
from scipy.ndimage import median_filter
from scipy.spatial.distance import jensenshannon


def position_table(game):
    tracks = defaultdict(list)
    for stamp, frame in game['frames'].items():
        for uid, pose in frame[2].items():
            tracks[uid].append((int(stamp), *pose[1:4]))
    return {uid: np.array(sorted(values)) for uid, values in tracks.items()}


def tiles(pos):
    cell = ((pos[:, [1, 3]] + 128) // 16).astype(int).clip(0, 15)
    return cell[:, 0] * 16 + cell[:, 1]


def before_after(path):
    middle = (path[0, 0] + path[-1, 0]) / 2
    early = np.bincount(tiles(path[path[:, 0] < middle]), minlength=256)
    late = np.bincount(tiles(path[path[:, 0] >= middle]), minlength=256)
    return early, late


def cases(game):
    pos = position_table(game)
    eligible = [p for p in pos.values() if len(p) >= 100]
    mid = max(int(key) for key in game['frames']) / 2
    stationary = max(eligible, key=lambda p: np.max(np.bincount(tiles(p))) / len(p))
    changes = [p for p in eligible if p is not stationary and p[0, 0] < mid - 80 and p[-1, 0] > mid + 80]
    changed = max(changes, key=lambda p: jensenshannon(*before_after(p)))
    return pos, [stationary, changed]


def velocity(path):
    delta = np.diff(path[:, 0], prepend=path[0, 0])
    jump = np.linalg.norm(np.diff(path[:, [1, 3]], axis=0, prepend=[path[0, [1, 3]]]), axis=1)
    return np.where((delta == 1) & (jump <= 10), jump, np.nan)


def world_background(asset_dir, game, positions):
    asset = asset_dir / f'terrain-{game}.json.gz'
    if not asset.exists():
        return np.unique(np.floor(positions[:, [1, 3]]), axis=0)
    with gzip.open(asset, 'rt') as stream:
        world = json.load(stream)
    raw = np.asarray(world['blocks']).reshape(-1, 5)
    return np.unique(raw[:, [0, 2]], axis=0)


def main():
    root = Path(__file__).resolve().parent
    project = root.parents[2]
    protocol = json.loads((root / 'protocol.json').read_text())
    with gzip.open(project / protocol['input'], 'rt') as stream:
        games = json.load(stream)['matches']
    asset_dir = root.parent / 'viewer/dist/assets'
    plt.rcParams.update({'font.family':'DejaVu Sans', 'axes.labelcolor':'#c0cbd6',
                         'xtick.color':'#9caac1','ytick.color':'#9caac1','axes.titlecolor':'#ecf1f6'})
    figure = plt.figure(figsize=(16, 12.5), layout='constrained', facecolor='#0b1520')
    grids = figure.add_gridspec(6, 3, height_ratios=[4, 1, 1.25, 4, 1, 1.25])
    aqua, gold = '#63dac9', '#ffca7a'
    summary = []
    for col, game_id in enumerate(protocol['manual_inspection']):
        game = next(item for item in games if item['id']==game_id)
        paths, selected = cases(game)
        map_points = world_background(asset_dir, game_id, np.concatenate(list(paths.values())))
        for case, path in enumerate(selected):
            ax = figure.add_subplot(grids[case * 3, col])
            line_ax = figure.add_subplot(grids[case * 3 + 1, col])
            speed_ax = figure.add_subplot(grids[case * 3 + 2, col], sharex=line_ax)
            for panel in (ax, line_ax, speed_ax):
                panel.set_facecolor('#121d29')
                for spine in panel.spines.values():
                    spine.set_color('#304457')
                panel.tick_params(labelsize=8)
            xy = path[:, [1, 3]]
            t = path[:, 0]
            visible = (np.diff(t) == 1) & (np.linalg.norm(np.diff(xy, axis=0), axis=1)<13)
            segments = np.stack((xy[:-1], xy[1:]), axis=1)[visible]
            line = LineCollection(segments, cmap='viridis', linewidths=2.0, alpha=.84)
            line.set_array(t[:-1][visible] / 60)
            ax.add_collection(line)
            ax.scatter(map_points[:, 0], map_points[:, 1], color='#506579', alpha=.36, s=4, linewidths=0)
            ax.scatter(*xy[0], color='#fff4a2', marker='o', s=35, label='First recorded', zorder=5)
            ax.scatter(*xy[-1], color='#fc8a70', marker='X', s=41, label='Last recorded', zorder=5)
            xzmax = max(80, np.max(np.abs(map_points))+5)
            ax.set(xlim=(-xzmax,xzmax),ylim=(xzmax,-xzmax), xlabel='World X', ylabel='World Z',aspect='equal')
            head = 'Repeated locations' if case==0 else 'Changing locations'
            ax.set_title(f'Match {game_id[-2:]} · {head}',fontsize=13,loc='left',pad=8)
            cbar = figure.colorbar(line,ax=ax,pad=.02,shrink=.7)
            cbar.ax.set_ylabel('Elapsed minutes',color='#bdcad9',fontsize=9)
            cbar.outline.set_edgecolor('#344a5b')
            rad = np.linalg.norm(xy,axis=1)
            cut_x = t.copy() / 60
            cuts = np.flatnonzero(np.diff(t)>1)
            for segment in np.split(np.arange(len(t)),cuts+1):
                line_ax.plot(cut_x[segment],rad[segment],color=aqua,lw=1.45)
                speed_ax.plot(cut_x[segment],median_filter(np.nan_to_num(velocity(path)[segment]),size=5),color=gold,lw=1.2)
            line_ax.set(ylim=(-4,132),ylabel='Radius')
            speed_ax.set(ylim=(-.35,6.4),ylabel='Blocks/s',xlabel='Elapsed minutes')
            line_ax.tick_params(labelbottom=False)
            for panel in (line_ax, speed_ax):
                panel.grid(axis='y',color='#2b3b4d',alpha=.75)
                panel.set_xlim(0,max(int(x) for x in game['frames'])/60+.05)
            early, late = before_after(path)
            entry = {'match':game_id,'case':head,'n':len(t),'start':int(t[0]),'end':int(t[-1]),
                     'earliest_tile_share_late':float(late[np.argmax(early)]/late.sum()),
                     'earliest_tile_share_early':float(early.max()/early.sum()),
                     'JS_distance':float(jensenshannon(early,late)),
                     'slow_fraction':float(np.mean(velocity(path)[np.isfinite(velocity(path))] < .5)),
                     'early_tile':list(map(int,divmod(int(np.argmax(early)),16))),
                     'late_tile':list(map(int,divmod(int(np.argmax(late)),16)))}
            summary.append(entry)
    figure.suptitle('BedWars movement in three development matches',fontsize=26,fontweight='semibold',color='#edf3fb')
    out = project / 'runtime/bedwars-forecast/context-study'
    out.mkdir(parents=True,exist_ok=True)
    figure.savefig(out/'development-tracks.png',dpi=145,facecolor=figure.get_facecolor())
    (out/'track-observations.json').write_text(json.dumps(summary,indent=2)+'\n')
    print(json.dumps(summary,indent=2))


if __name__ == '__main__':
    main()
