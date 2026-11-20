"""Distill referee-selected teachers into an observation-only student, then remove the teachers for PPO."""
from bootstrap import fit
from league_tournament import *
from train import configure
from run_training import train
import sys

def teachers(g):
    folder=EVIDENCE/f'g{g:03d}';path=folder/'mentor-selection.json'
    if path.exists():return json.loads(path.read_text())
    # Use only the previously exposed development tournament; rollout demonstrations use train clocks.
    rank=json.loads((folder/'tournament.json').read_text());field=rank['field']
    options=[n for n in FIELD if n!='legacy-wtap-clickphase']
    overall={n:np.mean([rank['matrix'][n][f]['score'] for f in FIELD]) for n in options}
    selected={};report={}
    for foe in FIELD:
        teacher=max(options,key=lambda n:(.8*rank['matrix'][n][foe]['score']+.2*overall[n],overall[n],n))
        selected[field[foe]['reference']]=field[teacher]['reference']
        report[foe]=dict(teacher=teacher,development_score=rank['matrix'][teacher][foe]['score'],
                         wtap=rank['matrix']['wtap'][foe]['score'])
    result=dict(selected=selected,by_opponent=report,selection_scope='development; privileged teacher assignment only',
        distillation_updates=128,ppo_updates=256,duels_per_ppo_update=64)
    write_json(path,result,fixed=True);return result

def run(g,kind,until):
    folder=EVIDENCE/f'g{g:03d}';d=folder/('mentor-'+kind);table=teachers(g)
    if not (d/'init/trainer.pt').exists():
        fit(d/'init',kind,'field teacher',table['distillation_updates'],table['selected'],until)
    if not (d/'init/trainer.pt').exists():return
    cfg=json.loads((folder/('main' if kind=='gru' else 'mlp')/'config.json').read_text())
    configure(d/'ppo',kind,kind,cfg['seed']+31415,cfg['draw_seed']+31415,cfg['incumbent'],cfg['counter'],
              cfg['families'],cfg['scores'],d/'init/trainer.pt')
if __name__=='__main__':
    g=int(sys.argv[1]);kind=sys.argv[2]
    if len(sys.argv)>3 and sys.argv[3]=='init':run(g,kind,int(sys.argv[4]))
    else:
        d=EVIDENCE/f'g{g:03d}'/('mentor-'+kind)/'init'
        while not (d/'trainer.pt').exists():
            step=json.loads((d/'progress.json').read_text())['step'] if (d/'progress.json').exists() else 0
            subprocess.run([sys.executable,HERE.parent/'cpu_limit.py','nice','-n','8',sys.executable,__file__,str(g),kind,'init',str(step+8)],check=True)
        p=EVIDENCE/f'g{g:03d}'/('mentor-'+kind)/'ppo'
        train(p,256);pin_model(p)
