"""Compare NumPy and Rust on identical actions, then benchmark warmed in-process batches."""
import argparse
import json
from pathlib import Path
from statistics import median
import time

import numpy as np

from arena import KEYS, MOUSE, Arena
from rust_arena import Geometry, RustArena


def actions(env, tick):
    act = np.zeros((env.n, 2, len(KEYS) + 2), dtype=np.float32)
    act[:, :, 0] = 1 + ((env.rows[:, None] + tick // 23) % 3 - 1)
    act[:, 0, 1] = (env.rows + tick // 19) % 3
    act[:, 1, 1] = (env.rows + tick // 13) % 3
    act[:, :, 2] = (env.rows[:, None] + tick) % 55 == 0
    act[:, :, 3] = (tick // 26) % 2
    act[:, :, 4] = (tick // 17) % 5 == 0
    act[:, :, 5] = (tick % 21) < 20
    act[:, :, 6] = tick % 5 == 0
    if tick == 95:
        act[:, :, 7] = act[:, :, 5] = 1
    act[:, 0, MOUSE] = [.013 * np.sin(tick / 10), .004]
    act[:, 1, MOUSE] = [-.010 * np.cos(tick / 13), -.004]
    return act


def parity(geometry):
    error = 0.
    state_error = 0.
    cases = 0
    for evaluation in (False, True):
        a = Arena(64, 24581, evaluation=evaluation)
        b = RustArena(64, 24581, evaluation=evaluation, geometry=geometry)
        for t in range(800):
            act = actions(a, t)
            a.update_vision()
            b.update_vision()
            for seat in (0, 1, a.rows % 2):
                x, y = a.observe(seat), b.observe(seat)
                error = max(error, float(np.abs(x-y).max()))
                np.testing.assert_allclose(x, y, rtol=5e-6, atol=5e-6, err_msg=f'tick {t}, eval {evaluation}')
                cases += len(x)
            first, second = a.step(act)
            done, winner = b.step(act)
            np.testing.assert_array_equal((first, second), (done, winner))
            for field in ('pos', 'vel', 'hp', 'ap', 'av', 'age', 'hits', 'shots', 'ground'):
                x, y = getattr(a, field), getattr(b, field)
                if field in ('pos', 'vel', 'ap', 'av'):
                    state_error = max(state_error, float(np.abs(x-y).max()))
                    np.testing.assert_allclose(x, y, rtol=5e-6, atol=5e-6, err_msg=f'{field}, tick {t}')
                else:
                    np.testing.assert_array_equal(x, y, err_msg=f'{field}, tick {t}')
            a.reset(a.rows[done])
            b.reset(b.rows[done])
    return {'observations': cases, 'max_abs_feature_error': error,
            'max_abs_continuous_state_error': state_error, 'equal_discrete_outcomes': True}


def bench(factory, n, ticks, repeats):
    times = []
    for repeat in range(repeats + 1):
        env = factory(n, 11043)
        seat = env.rows % 2
        controls = [actions(env, t) for t in range(ticks)]
        start = time.perf_counter()
        for act in controls:
            env.update_vision()
            env.observe(seat)
            env.observe(1-seat)
            done, _ = env.step(act)
            env.reset(env.rows[done])
        if repeat:
            times.append(time.perf_counter() - start)
    return {'median_s': median(times), 'samples_s': times, 'arena_ticks_s': n*ticks/median(times)}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('library', type=Path)
    p.add_argument('--size', type=int, nargs='+', default=[24, 96, 384])
    p.add_argument('--ticks', type=int, default=320)
    p.add_argument('--repeats', type=int, default=3)
    p.add_argument('--skip-parity', action='store_true')
    p.add_argument('--out', type=Path)
    args = p.parse_args()
    geometry = Geometry(args.library.resolve())
    out = {}
    if not args.skip_parity:
        out['parity'] = parity(geometry)
    for n in args.size:
        numpy = bench(Arena, n, args.ticks, args.repeats)
        rust = bench(lambda *a: RustArena(*a, geometry=geometry), n, args.ticks, args.repeats)
        out[n] = {'numpy': numpy, 'rust': rust, 'speedup': numpy['median_s'] / rust['median_s']}
    result = json.dumps(out, indent=2) + '\n'
    if args.out:
        args.out.write_text(result)
    print(result, end='')


if __name__ == '__main__':
    main()
