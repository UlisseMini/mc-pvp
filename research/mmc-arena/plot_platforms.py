"""Draw the nine recorded fighting floors and gold spawn cells at a common scale."""
import gzip
import json

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.collections import PatchCollection
from matplotlib.patches import Rectangle

from platforms import AIRLIKE, OUT


def plot():
    fig, axes = plt.subplots(3, 3, figsize=(8.5, 8))
    fig.subplots_adjust(top=.835, bottom=.035, left=.04, right=.96, hspace=.38, wspace=.08)
    fig.text(.075, .965, 'Recorded MMC sumo floors', fontsize=20, weight='bold', va='top', color='#172f37')
    fig.text(.075, .921, 'Each square is one block. Gold squares mark the two starting positions.',
             fontsize=10, color='#3d565d', va='top')
    entries = [r for r in json.loads((OUT/'catalog.json').read_text()) if r['representative']]
    for ax, entry in zip(axes.flat, entries, strict=True):
        v = json.load(gzip.open(OUT/(entry['name']+'.json.gz')))['volume']
        x0, z0 = v['offset']
        floor = v['states'][v['levels'].index(0)]
        squares, colors = [], []
        for j, row in enumerate(floor):
            for i, state in enumerate(row):
                if state >> 4 in AIRLIKE:
                    continue
                x, z = x0+i, z0+j
                squares.append(Rectangle((x-.5, z-.5), 1, 1))
                colors.append('#e8ad24' if [x,z] in v['spawns'] else '#537b80')
        assert len(squares) == entry['area'] and colors.count('#e8ad24') == 2
        ax.add_collection(PatchCollection(squares, facecolors=colors, edgecolors='white', linewidths=.36))
        ax.set(xlim=(-9.7,9.7), ylim=(9.7,-9.7), aspect='equal')
        ax.axis('off')
        role = 'training shape' if entry['area'] == 137 else entry['split']
        ax.set_title(f"{entry['name']}   ·   {role}\n{int(entry['gap'])}-block opening",
                     fontsize=9, color='#172f37', pad=2, linespacing=1.35)
    fig.savefig(OUT/'floor-atlas.png', dpi=170, facecolor='white')
    plt.close(fig)


if __name__ == '__main__':
    plot()
