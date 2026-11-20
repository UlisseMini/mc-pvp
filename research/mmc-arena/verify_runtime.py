"""Replay every scored development-map fixture after changing the terrain or replay adapter."""
from pathlib import Path

from validation import BUILD, CLOCKS, Worker, make_panel, read, register, trials, write_json


def verify():
    folder, protocol = register('baseline16k')
    runtime = Path(read(BUILD/'model-latest.json')['directory'])
    original = Path(read(BUILD/'model-runtime.json')['directory'])
    cells = make_panel(CLOCKS['dev'], protocol['model_repetitions'])
    counts = {}
    for name in protocol['maps']:
        worker = Worker(runtime_directory=runtime, map_name=None if name == 'default' else name)
        count = 0
        try:
            for _, opponent, actor, key in trials(folder, protocol, name):
                recorded = read(folder/'model'/name/(key+'.json'))['games']
                plans = [[actor, opponent, clock, side, a, b, x, y] for _, _, side, a, b, clock, x, y in cells]
                actual = [row for j in range(0, len(plans), 32) for row in worker.run(plans[j:j+32], False)]
                if len(actual) != len(recorded) or len(actual) != len(cells):
                    raise ValueError(f'Fixture count changed for {name}/{key}')
                for i, (observed, expected) in enumerate(zip(actual, recorded)):
                    if list(observed[:2]) != expected[-2:]:
                        raise ValueError(f'Outcome or terminal time changed in {name}/{key}, fixture {i}')
                count += len(actual)
        finally:
            worker.close()
        counts[name] = count
    write_json(folder/('parity-'+runtime.name+'.json'), dict(original=original.name, updated=runtime.name,
               compared=counts, outcome_and_terminal_time_identical=True), fixed=True)
    print(f'{sum(counts.values())} matches reproduced on {len(counts)} platforms', flush=True)


if __name__ == '__main__':
    verify()
