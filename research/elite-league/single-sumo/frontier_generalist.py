"""Train a generalist from the frozen champion and its learned response on the same hard-opponent mixture."""
import fcntl
import json
import subprocess
import sys
from learner import EVIDENCE, ROOT, HERE, write_json
from generation import refs
from train import configure
from run_training import train
from league_tournament import pin_model

def run(g):
    generation=EVIDENCE/f'g{g:03d}'
    p=generation/'frontier-generalists';p.mkdir(parents=True,exist_ok=True)
    with (p/'fit.lock').open('a') as guard:
        fcntl.flock(guard,fcntl.LOCK_EX)
        if (p/'weights.json').exists():return
        result=json.loads((generation/'winner-response/result.json').read_text())
        champ=result['target'];opponent=result['name'];weak=result['weakest']
        t=json.loads((generation/'tournament.json').read_text()); field=t['field'].copy()
        field[opponent]=result['policy']; families={n:members[:] for n,members in t['families'].items()}
        families['fresh responses']=[opponent]
        initial=json.loads((generation/'winner-response/protocol.json').read_text())['initial']
        # The frontier response always starts from the generation's memoryless generalist.
        starts={'generalist':generation/'mlp/trainer.pt','response':generation/'winner-response/trainer.pt'}
        receipt=generation/'winner-response/budget-result.json'
        if receipt.is_file():starts['response']=ROOT/json.loads(receipt.read_text())['checkpoint']
        cfg=json.loads((generation/'mlp/config.json').read_text()); rankings={r['name']:r for r in t['ranking']}
        scores=rankings[champ]['family']
        spec=dict(generation=g,starting_generalist=initial,frozen_champion=champ,response=opponent,
                  response_weakest=weak,winner_reference=field[champ],response_reference=field[opponent],
                  training=f'25% against {opponent}, 25% against {weak}, 50% even/weakness-weighted families including previous champions',
                  updates=256,duels_per_update=64,final_checkpoint=True,seed=923916+g*997,
                  controls='same next 16384 bouts, both RTT draws, physical action seeds, fresh Adam state and model shape; only starting policy differs',
                  enrollment='both graphically identical MLP architectures enter the following tournament as fixed legal opponents; compare W-tap, explicit worst and native transfer')
        write_json(p/'protocol.json',spec,fixed=True)
        for role,origin in starts.items():
            # The named role starts fresh Adam and keeps the ordinary mixed-opponent PPO schedule.
            configure(p/role,'mlp','frontier-generalist',spec['seed'],731097+g*919,field[opponent]['reference'],
                      field[weak]['reference'],refs(field,families),scores,origin)
        for stop in range(16,257,16):
            for role in starts:train(p/role,stop)
        write_json(p/'weights.json',{role:pin_model(p/role) for role in starts},fixed=True)

if __name__=='__main__':run(int(sys.argv[1]))
