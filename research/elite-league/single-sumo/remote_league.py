"""Portable, matched recurrent and feed-forward league challengers. All weights use the live actor format."""
import argparse
import fcntl
import hashlib
import json

from league_tournament import pin_model
from learner import CLOCKS, EVIDENCE, ROOT, Worker, write_json
from train import fit

OUT=EVIDENCE/'robustness-001'
ROLES=('mlp','gru','gru-aux')
EPISODES=65536


def read(p):return json.loads(p.read_text())


def setup():
    origin=EVIDENCE/'counter-trained-successors'
    panel=read(origin/'protocol.json')
    for role in ROLES:
        kind='mlp' if role=='mlp' else 'gru'
        source=read(origin/kind/'config.json')
        # Use repository-relative paths so the same frozen registry works on each host.
        source.update(seed=981731,action_seed=981731*101+1,draw_seed=638911,
                      warm=str((ROOT/panel['initial'][kind]['reference']).parent.relative_to(ROOT)/'trainer.pt'))
        if role=='gru-aux':
            source['auxiliary_opponents']=sorted({n for group in source['families'].values() for n in group})
        write_json(OUT/role/'config.json',source,fixed=True)
    write_json(OUT/'protocol.json',dict(roles=list(ROLES),episodes_each=EPISODES,cap=7,
               floor=137,initial=panel['initial'],entrants=panel['entrants'],training_mixture=panel['distribution'],
               seed=981731,fixture_seed=638911,opponent_id_in_play=False,
               auxiliary='0.10 cross-entropy to identify the training opponent from own observations at decisions 12, 24, 48, 96; equal total auxiliary weight for each eligible duel. Export the ordinary GRU movement and click heads only.',
               controls='GRUs share initialization, fixture order and actor random streams. The 180-unit MLP uses the same fixtures. Keep all three final weights.',
               test='Balanced broad development tournament against G2 and the three 65k responses, matched W-tap. Test history swaps at identical worlds against frozen best-sumo and Seven. Assess native W-tap, each distinct weakest opponent, and three counters. No elite human grade.'),fixed=True)


def parity(archive=False):
    s=read(OUT/'protocol.json')
    actors=[s['initial']['gru']['reference'],s['initial']['mlp']['reference'],s['entrants']['g1']['reference']]
    opponents=['wtap','hold','sumo-seven-first',s['entrants']['incumbent']['reference']]
    if archive:
        actors=actors[:1]
        opponents=list(dict.fromkeys(v['reference'] for v in read(EVIDENCE/'g002/generation.json')['field'].values()))
    w=Worker()
    try:
        summary=[]
        for i,actor in enumerate(actors):
            rows=[[actor,opp,c,seat,a,b,57101+i*91+j,93007+j] for j,(opp,c,seat,a,b) in enumerate(
                    (o,clock,seat,own,other) for o in opponents for clock in CLOCKS['train'][:2]
                    for seat in 'ab' for own,other in ((31,109),(109,31)))]
            for outcome,millis,heads in w.run(rows,True):
                summary.append(dict(wdl=outcome,millis=millis,heads=len(heads),
                                    trace_sha256=hashlib.sha256(heads.astype('>f4').tobytes()).hexdigest()))
        print(json.dumps(summary,separators=(',',':')))
    finally:w.close()


def train_role(role):
    d=OUT/role
    with (d/'fit.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        fit(d,EPISODES//64)
        if read(d/'progress.json')['duels']!=EPISODES:raise ValueError('Budget mismatch')
        write_json(d/'result.json',pin_model(d),fixed=True)


if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('task',choices=['setup','parity','archive-parity',*ROLES])
    task=p.parse_args().task
    if task=='setup':setup()
    elif task in ('parity','archive-parity'):parity(task=='archive-parity')
    else:train_role(task)
