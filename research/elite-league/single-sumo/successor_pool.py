"""Start the first counter-trained successor pair while the frozen generation-two tournament finishes."""
import fcntl
import hashlib
import json

from league_tournament import pin_model
from learner import EVIDENCE, ROOT, write_json
from long_specialist import RUN
from run_training import train
from train import configure

OUT = EVIDENCE / 'counter-trained-successors'
KEYS = ('incumbent', 'seven', 'g1')
ROLES = ('mlp', 'gru')
UPDATES = 512


def read(path):
    return json.loads(path.read_text())


def model_ref(path):
    return dict(reference=str(path.relative_to(ROOT)), sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def prepare():
    first = EVIDENCE / 'g001'
    field = read(EVIDENCE / 'g002/generation.json')
    counters = {k: model_ref(RUN/k/'n65536.net') for k in KEYS}
    anchors = ['wtap', 'hold', field['incumbent'], 'sumo-seven-first', read(first/'decision.json')['tournament_winner']]
    archive = sorted({n for ns in field['families'].values() for n in ns} - set(anchors))
    starts = {'mlp': first/'mlp', 'gru': first/'mentor-gru/ppo'}
    groups = dict(counters=[r['reference'] for r in counters.values()],
                  anchors=[field['field'][n]['reference'] for n in anchors],
                  archive=[field['field'][n]['reference'] for n in archive])
    spec = dict(source_generation=2, entrants=counters, anchors=anchors, archive=archive,
                initial={k: model_ref(v/'final.net') for k,v in starts.items()},
                episodes_per_update=64, updates=UPDATES, cps=7, floor=137,
                distribution='32 counter bouts, 16 baseline and champion bouts, 16 other archive bouts per update; rotate evenly within each group',
                controls='Same opponents, seats, timing calendars, action seeds and independently sampled RTTs for both architectures. Different trained initial policies. No ID or map input.',
                scoring='Enroll both final actors in generation three irrespective of performance. Compare against each counter, W-tap, worst distinct opponent, and each parent in simulation and native Minecraft.')
    write_json(OUT/'protocol.json', spec, fixed=True)
    for role, source in starts.items():
        configure(OUT/role, role, 'counter-mixture', 757229, 734219, groups['anchors'][2],
                  groups['counters'][0], groups, warm=source/'trainer.pt')


def run():
    prepare()
    with (OUT/'pair.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if (OUT/'weights.json').exists():
            return
        for stop in range(16, UPDATES+1, 16):
            for role in ROLES:
                train(OUT/role, stop)
        write_json(OUT/'weights.json', {role: pin_model(OUT/role) for role in ROLES}, fixed=True)


if __name__ == '__main__':
    run()
