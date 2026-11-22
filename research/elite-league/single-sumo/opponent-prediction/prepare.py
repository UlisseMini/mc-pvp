"""Freeze fixtures, collect simulator trajectories, and materialize labeled arrays."""

from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import random
import struct
import subprocess

import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
LEAGUE = HERE.parent
BUILD = ROOT/'build/elite-league/single-sumo'
OUT = Path(os.environ['OPPONENT_PRED_OUT']) if 'OPPONENT_PRED_OUT' in os.environ else BUILD/'opponent-prediction'
ACTORS = ('wtap','hold','sumo-cap-ramp','v4-ppo-g0496')
UNSEEN = ('7 CPS responses','recurrent archive','style switches')
RECENT = (0,1,3,7)
HORIZON = 100
MAX_HEADS = 320
SEEDS = (8039,17231)
EPOCHS = 8
TARGETS = ('forward/sprint', 'strafe', 'physical attack', 'jump request')


def load(path):
    return json.loads(path.read_text())


def hash_file(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value,indent=2,sort_keys=True,allow_nan=False)+'\n')
    temporary.replace(path)


def register():
    field = HERE.parents[1]/'sumo-inputs/archive-field.tsv'
    settings = load(LEAGUE/'evidence/protocol.json')
    families = {a:f for f,members in settings['families'].items() for a in members}
    foes = [r.split('\t')[0] for r in field.read_text().splitlines()]
    if set(families)!=set(foes):
        raise ValueError('Archive and family declarations disagree')
    clockfile = HERE.parents[1]/'ping-ppo/clocks.tsv'
    clocks = {s:[] for s in ('train','dev','test')}
    for row in clockfile.read_text().splitlines():
        name,path = row.split('\t')
        clocks[name].append(path)
    # Reuse never-example schedules from a separate investigation, so this is
    # retrospective clock transfer rather than a prospective hardware claim.
    donor_dir = LEAGUE/'evidence/id-oracle/recognition/payoff/replication/spaced'
    others = sorted(donor_dir.glob('m*.txt.gz'),key=lambda p:int(p.name.split('.')[0][1:]))[:12]
    test = [str(p.relative_to(ROOT)) for p in others]
    if len(test)<8 or set(test)&set(clocks['train']+clocks['dev']):
        raise ValueError('Distinct timing donor panel required')
    policy_settings = {
        'foes':foes, 'families':families, 'unseen':list(UNSEEN),'actors':list(ACTORS),
        'rtt_pairs':settings['rtt_cells'], 'cps':7,'arena_cells':137,
        'source':load(BUILD/'sim-current.json')['directory'],
        'archive_sha256':hash_file(field),
        'clocks':{'support':clocks['train'], 'validation':clocks['dev'], 'test':test},
        'repeats':{'support':2,'validation':1,'test':1},
        'seeds':list(SEEDS),'epochs':EPOCHS,
        'horizon_ms':HORIZON,'maximum_heads':MAX_HEADS,'recent_offsets':list(RECENT),
        'primary':'unseen family GRU versus short-context generic MLP, family-equal per-action log loss',
        'controls':'matched all-foe GRU, generic MLP, ID MLP, and independent per-foe MLP on identical support worlds',
        'hard_slice':'first 350 ms after a locally seen own hurt cue with displayed nearest distance at most 4.5 blocks',
    }
    policy_settings['clock_hashes'] = {p:hash_file(ROOT/p) for rows in policy_settings['clocks'].values() for p in rows}
    return policy_settings


def fixtures(setup, split):
    seed = {'support':625037,'validation':503985,'test':419079}[split]
    rng = random.Random(seed)
    worlds = []
    for i,clock in enumerate(setup['clocks'][split]):
        for rep in range(setup['repeats'][split]):
            for seat in ('a','b'):
                for j,(a,b) in enumerate(setup['rtt_pairs']):
                    actor = setup['actors'][(i+j+rep+(2 if seat=='b' else 0)) % len(setup['actors'])]
                    seeds = rng.getrandbits(60),rng.getrandbits(60)
                    worlds.extend((actor,foe,clock,seat,a,b,*seeds) for foe in setup['foes'])
    return worlds


def decode(source, worlds, output, setup, split):
    count, width, labels = struct.unpack_from('>iii',source)
    if count!=len(worlds) or labels!=len(TARGETS):
        raise ValueError('Trace header does not match frozen fixtures')
    dtype = np.dtype([('clock','>f4'),('x','>f4',(width,)),('y','i1',(labels,))])
    offset = 12
    trace, label, time = [], [], []
    cuts = [0]
    ids, donors, actors, scores = [], [], [], []
    foe_index = {name:i for i,name in enumerate(setup['foes'])}
    for actor,foe,clock,*_ in worlds:
        n,score,end = struct.unpack_from('>iff',source,offset)
        offset += 12
        rows = np.frombuffer(source,dtype=dtype,count=n,offset=offset)
        offset += dtype.itemsize*n
        if not 0<n<=MAX_HEADS or rows['clock'][-1]>end or np.any(np.diff(rows['clock'])<0):
            raise ValueError('Invalid decision times')
        x = rows['x'].astype(np.float32)
        y = rows['y'].copy()
        valid = y[:,0]>=0
        if (not np.isfinite(x).all() or np.any(y[valid]<0)
                or np.any(y[valid]>=np.array([4,3,2,2]))
                or np.any(y[~valid]!=-1)
                or np.any(rows['clock'][valid]+HORIZON>end+.01)):
            raise ValueError('Invalid features or action labels')
        trace.append(x); label.append(y); time.append(rows['clock'].astype(np.float32))
        cuts.append(cuts[-1]+n)
        ids.append(foe_index[foe]); donors.append(setup['clocks'][split].index(clock))
        actors.append(setup['actors'].index(actor)); scores.append(score)
    if offset!=len(source) or max(Counter(ids).values())!=min(Counter(ids).values()):
        raise ValueError('Trace frame count or opponent balance changed')
    temp = output.with_suffix('.tmp')
    with temp.open('wb') as stream:
        np.savez_compressed(stream,x=np.concatenate(trace),y=np.concatenate(label),time=np.concatenate(time),
                            cuts=np.array(cuts),foe=np.array(ids),donor=np.array(donors),
                            actor=np.array(actors),scores=np.array(scores))
    temp.replace(output)
    print(split,count,'duels',int(sum((y[:,0]>=0).sum() for y in label)),'scored decisions',flush=True)


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    registration = OUT/'protocol.json'
    frozen_path = HERE/'protocol.json'
    if not registration.exists():
        proto = load(frozen_path) if frozen_path.exists() else register()
        if os.environ.get('OPPONENT_PRED_USE_CURRENT_SIM'):
            proto['source'] = load(BUILD/'sim-current.json')['directory']
        save(registration,proto)
    setup = load(registration)
    if frozen_path.exists() and not os.environ.get('OPPONENT_PRED_USE_CURRENT_SIM') and load(frozen_path)!=setup:
        raise ValueError('Cached data uses a different experiment registration')
    if any(hash_file(ROOT/path)!=digest for path,digest in setup['clock_hashes'].items()):
        raise ValueError('Frozen timing recording changed')
    if hash_file(HERE.parents[1]/'sumo-inputs/archive-field.tsv')!=setup['archive_sha256']:
        raise ValueError('Frozen opponent archive changed')
    source = BUILD/setup['source']
    classes = OUT/'classes'
    classes.mkdir(exist_ok=True)
    subprocess.run(['javac','-J-XX:ActiveProcessorCount=1','-proc:none','-cp',source,
                    '-d',classes,HERE/'PredictionTrace.java'],check=True,cwd=ROOT)
    for split in setup['clocks']:
        dest = OUT/(split+'.npz')
        if dest.exists():
            continue
        settings = fixtures(setup,split)
        schedule = OUT/(split+'.tsv')
        schedule.write_text(''.join('\t'.join(map(str,row))+'\n' for row in settings))
        raw = OUT/(split+'.wire')
        subprocess.run(['java','-XX:ActiveProcessorCount=1','-Xmx768m','-cp',f'{classes}:{source}',
                        'PredictionTrace',schedule,raw],cwd=ROOT,check=True)
        decode(raw.read_bytes(),settings,dest,setup,split)
        raw.unlink()


if __name__ == '__main__':
    main()
