"""Write the versioned local mod bundle and Java parity inputs from Python exports."""
import argparse
import gzip
import json
from pathlib import Path
import struct


NETWORKS = ('ce', 'heatmap', 'anil')
LAYERS = ('encoder.0', 'encoder.2', 'mix', 'field')
RESIDUALS = ('stay', 'velocity', 'drift')
ANALOGUES = ('Motion mixture', 'Mixture without motion', 'Mixture without context')
MODELS = ('stay', 'velocity', 'drift', 'motion', 'motion-no-motion', 'motion-no-context',
          'heatmap-ce', 'heatmap', 'head-tune', 'anil-prior', 'anil', 'history', 'meta-history',
          'personal-hotspots', 'personal-routes')


def integers(stream, values):
    stream.write(struct.pack(f'>{len(values)}i', *values))


def floats(stream, values):
    stream.write(struct.pack(f'>{len(values)}f', *values))


def rows(stream, values):
    for row in values:
        floats(stream, row)


def write_bundle(source, output, checkpoint=None, personal_gate=None):
    with gzip.open(source, 'rt') as stream:
        data = json.load(stream)
    if data['version'] != 1 or tuple(data['views']) != MODELS:
        raise ValueError('Unexpected Python forecast export version or model registry')
    horizons = data['horizons']
    if horizons != sorted(set(horizons)):
        raise ValueError('Forecast horizons must be increasing and unique')
    fine = None
    gate = None
    if checkpoint is not None:
        import torch
        fine = torch.load(checkpoint, map_location='cpu', weights_only=True)
    if personal_gate is not None:
        import torch
        if fine is None:
            raise ValueError('Personal gate v3 bundle requires heatmap fine checkpoint')
        gates = torch.load(personal_gate, map_location='cpu', weights_only=True)
        gate = gates['full personal gate']
    version = 3 if gate is not None else (2 if fine is not None else 1)
    with gzip.open(output, 'wb', compresslevel=5) as stream:
        stream.write(b'BWPM')
        integers(stream, [version, 16, len(horizons), *horizons])
        for model in NETWORKS:
            for layer in LAYERS:
                rows(stream, data[model][f'{layer}.weight'])
                floats(stream, data[model][f'{layer}.bias'])
            if fine is not None:
                for layer in ('fine.0', 'fine.2'):
                    rows(stream, fine[model][f'{layer}.weight'].tolist())
                    floats(stream, fine[model][f'{layer}.bias'].tolist())
        floats(stream, data['steps'])
        rows(stream, data['historyWeights'])
        for base in data['baseline']:
            for name in RESIDUALS:
                widths, weights = base[name]
                rows(stream, widths)
                floats(stream, weights)
            rows(stream, base['driftCoefficients'])
            floats(stream, base['driftIntercept'])
            for name in ANALOGUES:
                index = base['analogues'][name]
                columns = index['columns']
                integers(stream, [len(columns), *columns])
                floats(stream, index['center'])
                floats(stream, index['scale'])
                integers(stream, [len(index['features'])])
                rows(stream, index['features'])
                rows(stream, index['targets'])
        if gate is not None:
            rows(stream, gate['offset.weight'].tolist())
            floats(stream, gate['center'].tolist())
            floats(stream, gate['scale'].tolist())
            for layer in ('layers.0', 'layers.2'):
                rows(stream, gate[f'{layer}.weight'].tolist())
                floats(stream, gate[f'{layer}.bias'].tolist())


def refresh_fixtures(tracks, checkpoint, protocol_path, target):
    import numpy as np
    import torch

    from forecast import HORIZONS, PlayerModel, load_games, take
    from heatmap import Heatmap, MetaHistory, data_for, parity_out

    torch.set_num_threads(2)
    protocol = json.loads(protocol_path.read_text())
    games = load_games(tracks)
    data = data_for(games)
    saved = torch.load(checkpoint, map_location='cpu', weights_only=True)
    models = [Heatmap(), Heatmap(), Heatmap(), MetaHistory()]
    for key, model in zip(NETWORKS + ('meta-history',), models):
        model.load_state_dict(saved[key])

    training = tuple(protocol['train'] + protocol['development'])
    baselines = [PlayerModel(take(data.raw, ((data.raw['hindex'] == i) &
                                            np.isin(data.raw['game'], training))), horizon)
                 for i, horizon in enumerate(HORIZONS)]
    heldout = [game for game in games if game['id'] in protocol['test']]
    parity_out(target, data, heldout, models, baselines)


def write_fixtures(source, output):
    fixtures = json.loads(source.read_text())
    without_history = {'head-tune': 'heatmap', 'anil': 'anil-prior', 'history': 'heatmap',
                       'meta-history': 'heatmap', 'personal-hotspots': 'motion', 'personal-routes': 'motion'}
    empty_support = []
    for fixture in fixtures:
        if fixture['support']:
            expected = fixture['expected']
            empty_support.append({**fixture, 'support': [], 'expected': {
                model: expected[without_history.get(model, model)] for model in expected}})
    fixtures.extend(empty_support)
    with output.open('wb') as stream:
        stream.write(b'BWPF')
        integers(stream, [len(fixtures)])
        for fixture in fixtures:
            integers(stream, [fixture['horizon']])
            floats(stream, [*fixture['features'], *fixture['xyz'], *fixture['velocity'], fixture['yaw']])
            support = fixture['support']
            integers(stream, [len(support)])
            rows(stream, support)
            expected = fixture['expected']
            integers(stream, [len(expected)])
            for model, probability in expected.items():
                integers(stream, [MODELS.index(model)])
                floats(stream, probability)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('artifact_dir', type=Path)
    parser.add_argument('--refresh-fixtures', action='store_true')
    parser.add_argument('--tracks', type=Path)
    parser.add_argument('--personal-gate', type=Path, default=None)
    parser.add_argument('--protocol', type=Path, default=Path(__file__).with_name('heatmap_protocol.json'))
    args = parser.parse_args()
    root = args.artifact_dir
    model = root / 'bedwars-forecast.bin.gz'
    fixture_json = root / 'parity.json'
    fixtures = root / 'parity.bin'
    checkpoint = root / 'heatmap-models.pt'
    gate_path = args.personal_gate
    if gate_path is None and (root / 'context-gates.pt').is_file():
        gate_path = root / 'context-gates.pt'
    write_bundle(root / 'bedwars-forecast.json.gz', model, checkpoint if checkpoint.exists() else None, gate_path)
    if args.refresh_fixtures:
        refresh_fixtures(args.tracks or root / 'tracks.json.gz', root / 'heatmap-models.pt', args.protocol, fixture_json)
    if fixture_json.is_file():
        write_fixtures(fixture_json, fixtures)
        print(f'Java fixtures {fixtures}')
    print(f'Runtime bundle {model}, {model.stat().st_size:,} bytes')


if __name__ == '__main__':
    main()
