"""Replay recorded native inputs and condition projectile checks on observed launches."""
import argparse
from collections import Counter
import csv
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

import numpy as np

import arena

MOVEMENT = dict(enumerate(('walk', 'strafe', 'diagonal', 'sprint', 'sneak', 'jump', 'draw_walk')))
XYZ, VEL = ('x', 'y', 'z'), ('vx', 'vy', 'vz')
ORIGIN = np.array([.5, 150, 0])


def load(path):
    with path.open() as f:
        return list(csv.DictReader(f, delimiter='\t'))


def vec(row, cols):
    return np.array([float(row[k]) for k in cols])


def fresh(module, first, second=None):
    env = module.Arena(1, 76)
    env.active_box[:] = False
    for i, row in enumerate([first, second]):
        env.pos[0, i] = vec(row, XYZ) - ORIGIN if row else [0, 0, 10.5]
        env.vel[0, i] = vec(row, VEL) if row else [0, -.0784, 0]
        env.ground[0, i] = row['ground'] == 'true' if row else True
        if row:
            env.yaw[0, i] = float(row['yaw'])
            env.pitch[0, i] = float(row['pitch'])
    return env


def movement(module, rows):
    reports = {}
    schedule = MOVEMENT.copy()
    if any(row['case'] == '13' for row in rows):
        schedule.update({12: 'wall_walk', 13: 'wall_jump'})
    for i, name in schedule.items():
        inputs = [r for r in rows if r['event'] == 'input' and int(r['case']) == i][:40]
        after = {r['tick']:r for r in rows if r['event'] == 'after' and int(r['case']) == i}
        if len(inputs) < 39 or any(r['tick'] not in after for r in inputs):
            raise ValueError(f'Incomplete movement fixture {name}')
        if float(inputs[0]['hp']) < 20:
            raise ValueError(f'{name} started before the native player finished resetting')
        env = fresh(module, inputs[0])
        if name.startswith('wall_'):
            env.active_box[0, 0] = True
            env.boxes[0, 0] = [[-.5, 0, -7], [.5, 1 if i == 13 else 3, -6]]
        pred, native = [], []
        # In draw_walk the action ends at tick 29. Stop before its unrelated shot arrives.
        for row in inputs:
            buttons = row['detail'].split(',')
            action = np.zeros((1, 2, len(module.KEYS)+2), np.float32)
            action[0, 0, :len(module.KEYS)] = [int(s) for s in buttons[:-1]]
            action[0, 1, :2] = 1
            env.step(action)
            pred.append(env.pos[0, 0].copy())
            native.append(vec(after[row['tick']], XYZ) - ORIGIN)
        error = np.linalg.norm(np.array(pred)-np.array(native), axis=1)
        start = vec(inputs[0], XYZ) - ORIGIN
        reports[name] = dict(rmse=float(np.sqrt(np.square(error).mean())), max=float(error.max()),
                             native_at_30=(native[29]-start).tolist(), sim_at_30=(pred[29]-start).tolist())
    return reports


def free_projectile_frames(spawn, frames):
    p = vec(spawn, XYZ) - ORIGIN
    v = vec(spawn, VEL)
    for frame in frames:
        observed = vec(frame, XYZ) - ORIGIN
        if np.linalg.norm(observed - p) < 1e-8:
            continue  # The spawn may also appear in the end-of-tick entity list.
        if np.linalg.norm(observed - p - v) > 1e-6:
            break  # Block clipping is a different fixture.
        yield frame
        p, v = observed, vec(frame, VEL)


def projectile(module, rows):
    checks, samples = [], 0
    for spawn in (r for r in rows if r['event'] == 'spawn' and r['entity'] in ('EntityArrow','EntityEnderPearl')):
        case = int(spawn['case'])
        owner = int(float(spawn['z']) > 0)
        victim = 'ParityA' if owner else 'ParityB'
        pearl = spawn['entity'] == 'EntityEnderPearl'
        same_case = [r for r in rows if int(r['case']) == case]
        player_rows = [next(r for r in same_case if r['entity'] == name) for name in ('ParityA', 'ParityB')]
        env = fresh(module, *player_rows)
        if case == 9:
            env.active_box[0, 0] = True
            env.boxes[0, 0] = [[-.5, 0, 4], [.5, 3, 5]]
        damages = [r for r in same_case if r['event'] == 'damage' and r['entity'] ==
                   (('ParityB' if owner else 'ParityA') if pearl else victim)]
        if damages and not pearl:
            env.vel[0, 1-owner] = vec(damages[0], VEL)
        ix = owner * (module.ARROWS // 2)
        env.ap[0, ix] = vec(spawn, XYZ) - ORIGIN
        env.av[0, ix] = vec(spawn, VEL)
        env.age[0, ix] = 1
        env.apearl[0, ix] = pearl
        frames = [r for r in same_case if r['id'] == spawn['id'] and r['event'] == 'tick']
        count = 0
        error = 0.
        for frame in free_projectile_frames(spawn, frames):
            env.move_projectiles(np.ones(1, bool))
            if not env.age[0, ix]:
                break
            error = max(error, np.linalg.norm(env.ap[0, ix] - (vec(frame, XYZ)-ORIGIN)))
            count += 1
        for _ in range(80):
            if not env.age[0, ix]:
                break
            env.move_projectiles(np.ones(1, bool))
        row = dict(case=case, owner=owner, native_hit=None if pearl else bool(damages),
                   sim_hit=None if pearl else bool(env.hits[0, owner]),
                   free_ticks=count, max_air_path_error=float(error), pearl=pearl)
        if damages and pearl:
            row['pearl_destination_error'] = float(np.linalg.norm(env.pos[0, owner] - (vec(damages[0], XYZ)-ORIGIN)))
            row['sim_pearl_cost'] = float(20 - env.hp[0, owner])
            row['native_pearl_cost'] = float(damages[0]['detail'])
        if damages and not pearl and env.hits[0, owner]:
            observed = next(r for r in same_case if r['entity'] == victim and r['event'] == 'tick' and
                            int(r['tick']) >= int(damages[0]['tick']) and float(r['hp']) < 20)
            row['native_raw_damage'] = float(damages[0]['detail'])
            row['native_hp_loss'] = 20 - float(observed['hp'])
            row['punch_velocity_error'] = float(np.linalg.norm(env.vel[0, 1-owner] - vec(observed, VEL)))
            row['native_punch_v'] = vec(observed, VEL).tolist()
            row['sim_punch_v'] = env.vel[0, 1-owner].tolist()
        samples += count
        checks.append(row)
    return dict(projectiles=len(checks), air_ticks=samples, cases=checks)


def packets(path):
    import gzip
    out = {}
    for client in ('ParityA', 'ParityB'):
        files = sorted((path/client/'config/pvp-practice/captures').glob('*.jsonl.gz'))
        events = Counter()
        for f in files:
            with gzip.open(f, 'rt') as stream:
                events.update(json.loads(line).get('type') for line in stream)
        out[client] = dict(files=len(files), events=sum(events.values()), kinds=dict(events))
    return out


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('recording', type=Path)
    p.add_argument('--original', type=Path)
    p.add_argument('--geometry', type=Path)
    p.add_argument('--verify', action='store_true')
    p.add_argument('--out', type=Path)
    args = p.parse_args()
    src = args.recording
    client = load(src/'client-a.tsv')
    server = load(src/'server.tsv')
    implementation = SimpleNamespace(**vars(arena))
    if args.geometry:
        from rust_arena import Geometry, RustArena
        geometry = Geometry(args.geometry.resolve())
        implementation.Arena = lambda *a, **kw: RustArena(*a, **kw, geometry=geometry)
    out = {'movement': movement(implementation, client), 'projectiles': projectile(implementation, server), 'packets': packets(src),
           'trace_rows': {'client-a': len(client), 'client-b':len(load(src/'client-b.tsv')), 'server':len(server)}}
    if args.original:
        spec = importlib.util.spec_from_file_location('original', args.original)
        original = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(original)
        out['original_movement'] = movement(original, client)
        out['original_projectiles'] = projectile(original, server)
    if args.verify:
        if not all(client['events'] for client in out['packets'].values()):
            raise RuntimeError('Missing packet stream')
        if max(case['max'] for case in out['movement'].values()) > .001:
            raise RuntimeError('Movement exceeded the 0.001-block fixture tolerance')
        if out['projectiles']['projectiles'] < 7 or out['projectiles']['air_ticks'] < 30:
            raise RuntimeError('Incomplete native projectile fixture')
        for case in out['projectiles']['cases']:
            if case['max_air_path_error'] > .001:
                raise RuntimeError('Dry-air projectile path diverged')
            if case['pearl']:
                if case['pearl_destination_error'] > .001 or case['sim_pearl_cost'] != case['native_pearl_cost']:
                    raise RuntimeError('Pearl impact diverged')
            elif case['sim_hit'] != case['native_hit'] or case.get('punch_velocity_error', 0) > .001:
                raise RuntimeError('Arrow hit or Punch impulse diverged')
    result = json.dumps(out, indent=2) + '\n'
    if args.out:
        args.out.write_text(result)
    else:
        print(result, end='')


if __name__ == '__main__':
    main()
