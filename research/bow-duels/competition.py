"""Counter searches, archive-weighted generalists and defended titles for Bow Duels."""
import argparse
import fcntl
import hashlib
import math
import os
from pathlib import Path
import time

import numpy as np
import torch

from league import MODELS, SCRIPTS, SEQ, Policy, evaluate, optimize, rollout, save

COUNTERS = ('mlp', 'gru')
COUNTER_ROLES = tuple(f'counter-{kind}' for kind in COUNTERS)
DEV_SEED = 7832023
TITLE_SEED = 2378203
ACTIVE_ARCHIVE = 16


def frozen(policy):
    return dict(kind=policy.kind, conditioned=policy.conditioned, state=policy.state_dict())


def thaw(ref):
    policy = Policy(ref['kind'], ref.get('conditioned', False))
    policy.load_state_dict(ref['state'])
    return policy


def learner(kind, saved=None):
    policy = thaw(saved['policy']) if saved else Policy(kind, conditioned=True)
    opt = torch.optim.Adam(policy.parameters(), lr=.00010, eps=1e-5)
    if saved:
        opt.load_state_dict(saved['optimizer'])
    return policy, opt


def win_score(match):
    return (match['wins'] + .5 * match['draws']) / sum(match[k] for k in ('wins', 'draws', 'losses'))


def hardness(score):
    return (1 - score) ** 2 + .05


def versus(actor, foe, count, limit, seed, paired=False):
    return evaluate(actor, None if isinstance(foe, int) else foe, foe if isinstance(foe, int) else 0,
                    count, limit, seed, descriptive=True, paired_results=paired)


def sign_probability(pair_margins):
    wins = sum(m > 0 for m in pair_margins)
    n = wins + sum(m < 0 for m in pair_margins)
    return sum(math.comb(n, i) for i in range(wins, n + 1)) / 2**n


def family_merit(matches):
    groups = {'scripts': [], 'archive': []}
    for name, result in matches.items():
        groups['scripts' if name in SCRIPTS else 'archive'].append(win_score(result))
    scores = {family: float(np.mean(values)) for family, values in groups.items() if values}
    mean = sum(scores.values()) / len(scores)
    return dict(mean=mean, families=scores, merit=(mean + min(scores.values())) / 2)


def plans_for(policy, archive, threats, panel, limit):
    names = sorted(archive)
    scores = {name: win_score(versus(policy, archive[name], panel, limit, DEV_SEED + i))
              for i, name in enumerate(names)}
    weights = {name: hardness(score) for name, score in scores.items()}
    total = sum(weights.values())
    worst = min(scores, key=lambda name: (scores[name], name))
    return dict(scores=scores, worst=worst, threats=[worst, *threats],
                probabilities={name: weight / total for name, weight in weights.items()})


def sampled_names(plan, incumbent, batch, seed):
    rng = np.random.default_rng(seed)
    pairs = batch // 2
    quarter = pairs // 4
    p = plan['probabilities']
    names = [incumbent] * quarter
    names.extend(rng.choice(plan['threats'], size=quarter).tolist())
    names.extend(rng.choice(list(p), size=pairs-2*quarter, p=list(p.values())).tolist())
    rng.shuffle(names)
    return [name for name in names for _ in range(2)]


class Competition:
    def __init__(self, options):
        self.args = options
        self.out = options.out
        self.archive_dir = self.out / 'frozen'
        self.archive_dir.mkdir(exist_ok=True)
        self.checkpoint = self.out / 'state.pt'
        self.scripts = dict(zip(SCRIPTS, range(len(SCRIPTS)), strict=True))
        self.manifest = self.protocol()
        self.models = {}
        self.read_frozen = {}
        if self.checkpoint.exists():
            self.state = torch.load(self.checkpoint, map_location='cpu', weights_only=True)
            if self.state['protocol'] != self.manifest:
                raise ValueError('League settings or code changed. Start a new output directory.')
            for name, record in self.state['learners'].items():
                self.models[name] = learner(record['policy']['kind'], record)
            torch.random.set_rng_state(self.state['rng'])
        else:
            self.start()

    def protocol(self):
        args = self.args
        files = ('arena.py', 'geometry.rs', 'rust_arena.py', 'league.py', 'competition.py')
        source = {n: hashlib.sha256(Path(__file__).with_name(n).read_bytes()).hexdigest() for n in files}
        lib = os.environ.get('BOW_GEOMETRY_LIB')
        return dict(batch=args.batch, ticks=args.ticks, limit=args.limit, panel=args.panel,
                    counter_games=args.counter_panel, title_games=args.title_panel,
                    counter_steps=args.counter_steps, generalist_steps=args.generalist_steps,
                    counter_minimum_wins=math.ceil(.60 * args.counter_panel),
                    archive_weight='(1 - win_and_half_draw_score)^2 + 0.05',
                    active_learned_archive=ACTIVE_ARCHIVE, title_alpha=.05,
                    source=source, rust_sha256=hashlib.sha256(Path(lib).read_bytes()).hexdigest() if lib else None)

    def start(self):
        if self.args.seed_from_run is None:
            raise ValueError('The first run requires the frozen previous champion')
        source = sorted(self.args.seed_from_run.glob('g[0-9][0-9][0-9].pt'))
        if not source:
            raise ValueError('The previous run has no completed generation')
        seed = source[-1]
        snapshot = torch.load(seed, map_location='cpu', weights_only=True)
        initial = snapshot['champion']
        if not initial:
            raise ValueError('Seed checkpoint has no incumbent')
        save(self.archive_dir / 'previous-incumbent.pt', initial)
        old = []
        for path in source[:-1]:
            prior = torch.load(path, map_location='cpu', weights_only=True)['champion']
            name = f'previous-{path.stem}'
            if prior:
                save(self.archive_dir / (name + '.pt'), prior)
                old.append(name)
        self.state = dict(protocol=self.manifest, generation=1, phase='counters', step=0,
                          incumbent='previous-incumbent', archive=old, plans={}, counter_tests={})
        self.models = {f'generalist-{kind}': learner(kind) for kind in MODELS}
        self.prepare_counters()
        save(self.out / 'protocol.json', {**self.manifest, 'incumbent_file': str(seed),
                                          'incumbent_sha256': hashlib.sha256(seed.read_bytes()).hexdigest()})
        self.persist()

    def prepare_counters(self):
        for kind in COUNTERS:
            parent = self.models[f'generalist-{kind}'][0]
            self.models[f'counter-{kind}'] = learner(kind)
            self.models[f'counter-{kind}'][0].load_state_dict(parent.state_dict())

    def persist(self):
        self.state['learners'] = {name: dict(policy=frozen(policy), optimizer=opt.state_dict())
                                  for name, (policy, opt) in self.models.items()}
        self.state['rng'] = torch.random.get_rng_state()
        save(self.checkpoint, self.state)

    def load_named(self, name):
        if name not in self.read_frozen:
            item = torch.load(self.archive_dir / (name + '.pt'), map_location='cpu', weights_only=True)
            self.read_frozen[name] = thaw(item)
        return self.read_frozen[name]

    def archive(self):
        s = self.state
        return {**self.scripts, **{name: self.load_named(name) for name in s['archive'][-ACTIVE_ARCHIVE:]
                                  if name != s['incumbent']}}

    def train_update(self):
        s, args = self.state, self.args
        champion = self.load_named(s['incumbent'])
        opponents = self.archive() if s['phase'] == 'generalists' else {}
        opponents[s['incumbent']] = champion
        opponents.update({name: self.models[name][0] for name, result in s['counter_tests'].items()
                          if result['qualified']})
        step = s['step'] + 1
        generation = s['generation']
        seed = 66000 + 92149 * generation + step * 2969 + (4433 if s['phase'] == 'generalists' else 0)
        report = {}
        start = time.monotonic()
        for name, (policy, opt) in self.models.items():
            if name.startswith('counter') != (s['phase'] == 'counters'):
                continue
            if s['phase'] == 'counters':
                names = [s['incumbent']] * args.batch
            else:
                names = sampled_names(s['plans'][name], s['incumbent'], args.batch, seed)
            data, record = rollout(policy, None, args.batch, args.ticks, args.limit, seed,
                                   schedule=[opponents[n] for n in names])
            optimize(policy, opt, data, args.ticks, args.batch)
            record['arenas_by_opponent'] = {n: names.count(n) for n in sorted(set(names))}
            report[name] = record
            print(generation, s['phase'], step, name, record, flush=True)
        s['step'] = step
        self.persist()
        save(self.out / 'progress.json', dict(generation=generation, phase=s['phase'], step=step,
                                             incumbent=s['incumbent'], metrics=report,
                                             seconds=round(time.monotonic()-start, 2)))

    def qualify_counters(self):
        s, a = self.state, self.args
        champion = self.load_named(s['incumbent'])
        seed = DEV_SEED + 991 * s['generation']
        floor = self.manifest['counter_minimum_wins']
        tests = {}
        for name in COUNTER_ROLES:
            result = versus(self.models[name][0], champion, a.counter_panel, a.limit, seed)
            tests[name] = dict(result, qualified=result['wins'] >= floor)
        tests['reactive-reference'] = dict(versus(self.scripts['reactive'], champion, a.counter_panel, a.limit, seed),
                                           qualified=False)
        s['counter_tests'] = tests
        threats = [name for name, match in tests.items() if match['qualified']]
        archive = self.archive()
        s['plans'] = {name: plans_for(policy, archive, threats, a.panel, a.limit)
                      for name, (policy, _) in self.models.items() if name.startswith('generalist')}
        s.update(phase='generalists', step=0)
        self.persist()
        print(s['generation'], 'counter tests', {k: (v['wins'], v['draws'], v['losses'], v['qualified'])
                                                  for k, v in tests.items()}, flush=True)
        save(self.out / f'g{s["generation"]:03d}-sampling.json', s['plans'])

    def finish(self):
        s, a = self.state, self.args
        g, incumbent = s['generation'], s['incumbent']
        archive = self.archive()
        defending = self.load_named(incumbent)
        actors = {incumbent: defending, **{n: model[0] for n, model in self.models.items()}}
        receipts = {}
        for name, policy in actors.items():
            panel = {foe: versus(policy, archive[foe], a.panel, a.limit, DEV_SEED + i)
                     for i, foe in enumerate(sorted(archive))}
            receipts[name] = dict(family_merit(panel), field=panel)
        for name, policy in actors.items():
            if name == incumbent:
                continue
            defense = versus(policy, defending, a.title_panel, a.limit, TITLE_SEED + 8963*g, paired=True)
            p = sign_probability(defense['pair_margins'])
            qualified = (defense['wins'] > defense['losses'] and p <= .05 / (len(actors)-1)
                         and receipts[name]['merit'] > receipts[incumbent]['merit'])
            receipts[name].update(title=defense, paired_p=p, eligible=qualified)
        best = sorted((n for n in actors if receipts[n].get('eligible')),
                      key=lambda n: (-receipts[n]['merit'], -win_score(receipts[n]['title']), n))
        winner = incumbent
        entrants = {}
        for role, (policy, _) in self.models.items():
            name = f'g{g:03d}-{role}'
            save(self.archive_dir / (name + '.pt'), frozen(policy))
            entrants[role] = name
            if s['counter_tests'].get(role, {}).get('qualified'):
                s['archive'].append(name)
        if best:
            winner = entrants[best[0]]
            s['archive'].append(incumbent)
        s['archive'] = list(dict.fromkeys(n for n in s['archive'] if n != winner))
        results = dict(generation=g, incumbent=incumbent, winner=winner, promoted=best[0] if best else None,
                       counter_tests=s['counter_tests'], sampling=s['plans'], entrants=entrants,
                       field=receipts, title_alpha=.05/(len(actors)-1), archive=s['archive'])
        save(self.out / f'g{g:03d}.json', results)
        print(g, 'title:', winner, 'promoted' if best else 'retained', flush=True)
        s.update(generation=g+1, phase='counters', step=0, incumbent=winner, counter_tests={}, plans={})
        self.prepare_counters()
        self.persist()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--seed-from-run', type=Path)
    p.add_argument('--batch', type=int, default=96)
    p.add_argument('--ticks', type=int, default=640)
    p.add_argument('--limit', type=int, default=640)
    p.add_argument('--counter-steps', type=int, default=3)
    p.add_argument('--generalist-steps', type=int, default=4)
    p.add_argument('--panel', type=int, default=24)
    p.add_argument('--counter-panel', type=int, default=128)
    p.add_argument('--title-panel', type=int, default=128)
    p.add_argument('--generations', type=int)
    a = p.parse_args()
    if a.batch < 8 or a.batch % 8 or a.ticks < a.limit or a.ticks % SEQ or a.limit < 2:
        p.error('Use a batch divisible by 8 and a rollout of whole 32-step windows covering a duel')
    if min(a.panel, a.counter_panel, a.title_panel) < 2 or any(n % 2 for n in (a.panel, a.counter_panel, a.title_panel)):
        p.error('Evaluation panels must be positive and even')
    if min(a.counter_steps, a.generalist_steps) < 1:
        p.error('Both phases need at least one update')
    torch.set_num_threads(1)
    torch.set_num_interop_threads(1)
    torch.manual_seed(19856)
    a.out.mkdir(parents=True, exist_ok=True)
    with (a.out / 'runner.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        league = Competition(a)
        while a.generations is None or league.state['generation'] <= a.generations:
            s = league.state
            if s['phase'] == 'counters' and s['step'] == a.counter_steps:
                league.qualify_counters()
            elif s['phase'] == 'generalists' and s['step'] == a.generalist_steps:
                league.finish()
            else:
                league.train_update()


if __name__ == '__main__':
    main()
