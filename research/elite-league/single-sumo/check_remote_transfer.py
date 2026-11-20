"""End-to-end checkpoint transport check. These 128 infrastructure fixtures never enter a tournament."""
import tempfile
import torch
from pathlib import Path
from learner import Actor, BUILD, EVIDENCE, ROOT, write_json
from remote_fit import delegate, digest, read
from train import configure

if __name__=='__main__':
    p=EVIDENCE/'remote-checkpoint-check'
    configure(p,'mlp','portability',870321,903463,'wtap','hold',{'baselines':['wtap','hold']},
              warm=(EVIDENCE/'g001/mlp/trainer.pt').relative_to(ROOT))
    delegate(p,2)
    saved=torch.load(p/'trainer.pt',map_location='cpu',weights_only=False)
    actor=Actor('mlp');actor.load_state_dict(saved['net'])
    with tempfile.TemporaryDirectory(dir=BUILD) as tmp:
        out=Path(tmp)/'reexport.net';actor.export(out)
        if out.read_bytes()!=(p/'final.net').read_bytes():raise ValueError('Mac export disagrees with Linux')
    if saved['step']!=2 or read(p/'progress.json')['duels']!=128:raise ValueError('Wrong optimizer position')
    write_json(p/'local-verification.json',dict(updates=2,duels=128,local_reexport_sha256=digest(p/'final.net'),
                linux_checkpoint_sha256=digest(p/'trainer.pt'),scope='checkpoint transport and optimizer-load check only'),fixed=True)
    print('Linux optimizer loaded on Mac and re-exported identical physical controls')
