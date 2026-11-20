"""Repeat the fixed-opponent PPO experiment with only its initialization changed."""
from train import configure
from league_tournament import *
from run_training import train
import sys
def run(g,teacher):
    folder=EVIDENCE/f'g{g:03d}';d=folder/('rescue-'+teacher);field=historical()
    bootstrap=EVIDENCE/('bootstrap-'+teacher)
    name='sg%03d-init-%s'%(g,teacher);weights=bootstrap/'initial.net'
    field[name]=dict(reference=str(weights.relative_to(ROOT)),sha256=hashlib.sha256(weights.read_bytes()).hexdigest())
    config=json.loads((folder/'exploiter/config.json').read_text())
    configure(d,'gru','exploiter',config['seed'],config['draw_seed'],config['incumbent'],config['counter'],config['families'],config['scores'],bootstrap/'trainer.pt')
    if not (d/'before.json').exists():
        w=Worker()
        try:write_json(d/'before.json',counter_gate(w,field,name,INCUMBENT),fixed=True)
        finally:w.close()
    train(d,256)
    field[name+'-ppo']=pin_model(d);w=Worker()
    try:write_json(d/'result.json',counter_gate(w,field,name+'-ppo',INCUMBENT),fixed=True)
    finally:w.close()
if __name__=='__main__':run(int(sys.argv[1]),sys.argv[2])
