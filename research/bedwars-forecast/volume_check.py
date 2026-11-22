"""Export independent Python 3D display probabilities for the native parity check."""
import argparse
import gzip
import json
from pathlib import Path

import numpy as np
from scipy.special import ndtr
import torch

from forecast import HORIZONS, PersonalModel, PlayerModel, load_games, take
from heatmap import CELLS, HEIGHTS, HEIGHT_MIN, Heatmap, axis_mass, data_for
from portable import MODELS, NETWORKS, floats, integers, rows

HEIGHT_STEP = 4
NEURAL = {
    'heatmap-ce': 'ce',
    'heatmap': 'heatmap',
    'head-tune': 'heatmap',
    'anil-prior': 'anil',
    'anil': 'anil',
    'history': 'heatmap',
    'meta-history': 'heatmap',
}
GAUSSIAN = {
    'Stay': 'stay', 'Constant velocity': 'velocity', 'Learned drift': 'drift',
    'Motion mixture': 'motion', 'Mixture without motion': 'motion-no-motion',
    'Mixture without context': 'motion-no-context',
    'Personal hotspots': 'personal-hotspots', 'Personal routes': 'personal-routes',
}


def gaussian_volume(law):
    x = axis_mass(law.centers[:, :, 0], law.scales[:, :, 0])
    z = axis_mass(law.centers[:, :, 2], law.scales[:, :, 2])
    edge_y = HEIGHT_MIN + np.arange(0, HEIGHTS + 1, HEIGHT_STEP)
    y = np.diff(ndtr((edge_y - law.centers[:, :, 1, None]) / law.scales[:, :, 1, None]), axis=2)
    normalizer = (x.sum(axis=2) * z.sum(axis=2) * law.weights).sum()
    return np.einsum('bki,bkj,bkl,bk->bijl', x, z, y, law.weights).ravel() / normalizer


@torch.inference_mode()
def neural_heights(models, query, hi):
    features, xyz, velocity = (torch.tensor(query[k], dtype=torch.float32)
                               for k in ('features', 'xyz', 'velocity'))
    batch = features, xyz, velocity, torch.tensor([hi])
    result = {}
    for key, model in models.items():
        _, enc = model.coarse(batch)
        _, log_heights, log_weights = model.fine_components(enc.expand(CELLS, -1),
                                                            xyz.expand(CELLS, -1),
                                                            torch.arange(CELLS))
        height = (log_heights + log_weights[:, :, None]).exp().sum(dim=1)
        result[key] = height.reshape(CELLS, -1, HEIGHT_STEP).sum(dim=2).numpy()
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('artifact_dir', type=Path)
    parser.add_argument('out', type=Path)
    args = parser.parse_args()
    torch.set_num_threads(2)
    root = args.artifact_dir
    fixtures = json.loads((root / 'parity.json').read_text())
    protocol = json.loads(Path(__file__).with_name('heatmap_protocol.json').read_text())
    games = load_games(root / 'tracks.json.gz')
    data = data_for(games).raw
    eligible = np.isin(data['game'], protocol['train'] + protocol['development'])
    baselines = [PlayerModel(take(data, eligible & (data['hindex'] == hi)), h)
                 for hi, h in enumerate(HORIZONS)]
    weights = torch.load(root / 'heatmap-models.pt', map_location='cpu', weights_only=True)
    models = {key: Heatmap() for key in NETWORKS}
    for key, model in models.items():
        model.load_state_dict(weights[key])

    with gzip.open(args.out, 'wb', compresslevel=5) as stream:
        stream.write(b'BW3D')
        integers(stream, [len(fixtures)])
        for fixture in fixtures:
            h = fixture['horizon']
            hi = HORIZONS.index(h)
            query = {k: np.asarray([fixture[k]]) for k in ('features', 'xyz', 'velocity', 'yaw')}
            query['time'] = np.zeros(1)
            query['own'] = query['features'][:, 15] > 0
            support = np.asarray(fixture['support']).reshape(-1, 27)
            personal = PersonalModel({'xyz': support[:, :3], 'velocity': support[:, 3:6],
                                      'features': support[:, 6:23], 'future': support[:, 23:26]})
            predictions = baselines[hi].predict(query)
            predictions.update(personal.predict(query, predictions['Motion mixture'], h))
            volume = {GAUSSIAN[key]: gaussian_volume(law) for key, (law, _) in predictions.items()}
            heights = neural_heights(models, query, hi)
            for key, network in NEURAL.items():
                volume[key] = (np.asarray(fixture['expected'][key])[:, None] * heights[network]).ravel()
            integers(stream, [h])
            floats(stream, [*fixture['features'], *fixture['xyz'], *fixture['velocity'], fixture['yaw']])
            integers(stream, [len(support)])
            rows(stream, support)
            integers(stream, [len(volume)])
            for name, mass in volume.items():
                integers(stream, [MODELS.index(name)])
                floats(stream, mass)
    print(f'Wrote {len(fixtures)} fixtures for {len(MODELS)} model IDs')


if __name__ == '__main__':
    main()
