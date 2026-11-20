"""Separate the value of a counter, information about the opponent, and a router's use of it."""
import argparse
import hashlib
import io
import random

import numpy as np

from learner import CLOCKS, EVIDENCE, RTTS, Worker, atomic, write_json
from remote_league import read
from series_bank import CACHE, DEV_REPS, ROUNDS
from series_targeted import DEST as LIB, bank
from series_trace import CHANNELS, summarize

DEST = LIB / 'specialist-diagnosis'
DATA = CACHE / 'specialist-diagnosis'
TRAIN_REPS = 8


def register():
    library = read(LIB / 'protocol.json')
    opponents = {v['sha256']: k for k, v in library['field'].items()}
    responses = {v['sha256']: k for k, v in library['experts'].items()}
    dedicated = {}
    for path in sorted((EVIDENCE / 'fixed-adversaries').glob('*/result.json')):
        row = read(path)
        foe = opponents.get(row['target']['sha256'])
        response = responses.get(row['policy']['sha256'])
        if foe and response:
            dedicated[foe] = response
    write_json(DEST / 'protocol.json', dict(
        library=hashlib.sha256((LIB / 'protocol.json').read_bytes()).hexdigest(), dedicated=dedicated,
        primary='Outright ring-outs on the exposed 27-opponent development field, all ten games included. Report series separately. Seven equally capped CPS. Compare to W-tap, every fixed MLP, ping-only choice and the previous recurrent learner. All arms use identical physics fixtures. All controllers except standalone fixed controls share the robust game-one opener.',
        diagnosis='Measure dedicated MLPs, privileged ID from spawn and after game one, train-fitted payoff tables for outright wins versus points, posterior classification, and gains by game number. Count distinct histories. Fit only on training clocks. Include a true-ID action router with the same expert executor. Do not interpret a finite-library ID estimate as a universal upper bound.',
        observations='Record nine verified own-POV features. Use the previous 40-step representation and 24 time-local summaries of actual motion and hurt transitions. Condition on the played expert, displayed own and opponent pings, rounded duration and result. Clear memory on opponent change. Never provide the opponent ID during ordinary play.',
        collection='One deterministically rotated observer out of nine per training fixture, independent of opponent. Observe every counterfactual expert on the development fixtures, but a policy can update its memory only from the selected action. Recheck the ordinary unobserved game score and ending time in each branch. No weighting by wins or survival.',
        tests='Other-opponent history and same-opponent history from a different recording with identical seats and pings; erased memory; latest game only; outcome and duration only; all played motion; motion from only game one. Track opponent identification and actual payoff. Compare independent decoder fits and training-data budgets. Report paired uncertainty across timing recordings. Run real Minecraft against W-tap and hard counter-targets after the simulator test.',
        single_match='On opponent-specific fixtures branch the same complete game after a fixed strong opening. Compare keeping the opener with a dedicated switch, blind lookup, an identity-informed choice and a choice learned from the early visible prefix. Grade from spawn including matches that finish before the switch. Compare with dedicated and W-tap from spawn.'
    ), fixed=True)


def fixture_inputs(split, rep, clock_index):
    rng = random.Random(f'sumo-series-434811/{split}/{rep}/{clock_index}')
    for g in range(ROUNDS):
        seat = (rep + clock_index + g) % 2
        own, other = RTTS[(g + clock_index + rep // 2) % len(RTTS)]
        if split == 'train' and rng.random() < .5:
            own, other = rng.randrange(151), rng.randrange(151)
        yield seat, own, other, rng.getrandbits(60), rng.getrandbits(60)


def signal(rows):
    # All channels are inputs the controlling client received. The hurt ages jump on a new animation.
    delta = np.diff(rows, axis=0, prepend=rows[:1])
    events = np.column_stack((rows[:, 0] <= 0, delta[:, 7] > .25, delta[:, 8] > .25,
                              delta[:, 5] > 1, delta[:, 5] < -1, delta[:, 6] > 1))
    blocks = np.array_split(np.arange(len(rows)), 8)
    stats = []
    for block in blocks:
        block = block if len(block) else [-1]
        stats.extend(np.concatenate((rows[block].mean(0), rows[block].std(0), events[block].mean(0))))
    return np.concatenate((summarize(rows).ravel(), stats)).astype(np.float32)


def collect(split, shard, shards):
    spec = read(LIB / 'protocol.json')
    arms = list(spec['experts'])
    names = list(spec['field'])
    reps = TRAIN_REPS if split == 'train' else DEV_REPS
    world, contexts, _ = bank(split, reps)
    engine = Worker(current_runtime=True)
    try:
        for i, clock in enumerate(CLOCKS[split]):
            for r in range(reps):
                if (i * reps + r) % shards != shard:
                    continue
                path = DATA / split / f'{i:02}-{r:02}.npz'
                if path.is_file():
                    continue
                feats, choices = [], []
                for g, (seat, own, other, x, y) in enumerate(fixture_inputs(split, r, i)):
                    if contexts[i, r, g].tolist() != [seat, own, other]:
                        raise ValueError('Fixture reconstruction changed the context')
                    active = [(i * reps * ROUNDS + r * ROUNDS + g) % len(arms)] if split == 'train' else range(len(arms))
                    observations = []
                    for a in active:
                        plan = [[spec['experts'][arms[a]]['reference'], spec['field'][n]['reference'], clock,
                                 'ab'[seat], own, other, x, y] for n in names]
                        replay = engine.run(plan, True)
                        actual = np.asarray([[w, ms] for w, ms, _ in replay], np.float32)
                        if not np.array_equal(actual, world[i, r, g, :, a].numpy()):
                            raise ValueError(f'Observed fight diverged at {split}/{i}/{r}/{g}/{a}')
                        observations.append(np.stack([signal(v[:, CHANNELS]) for _, _, v in replay]))
                    feats.append(np.stack(observations, 1))
                    choices.append(list(active))
                buf = io.BytesIO()
                np.savez_compressed(buf, features=np.stack(feats), arms=np.asarray(choices))
                atomic(path, buf.getvalue())
    finally:
        engine.close()
    write_json(DEST / f'collection/{split}-{shard}-of-{shards}.json', dict(checked=True, shards=shards), fixed=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('task', choices=['register', 'train', 'dev'])
    parser.add_argument('shard', nargs='?', type=int, default=0)
    parser.add_argument('shards', nargs='?', type=int, default=1)
    args = parser.parse_args()
    if args.task == 'register':
        register()
    else:
        collect(args.task, args.shard, args.shards)
