import argparse
import io
import time
from learner import *

def configure(path, kind, role, seed, draw_seed, incumbent, counter, families, scores=None, warm=None):
    write_json(path/"config.json",dict(kind=kind,role=role,seed=seed,draw_seed=draw_seed,
               action_seed=seed*101+1,incumbent=incumbent,counter=counter,families=families,
               scores=scores or {},warm=None if warm is None else str(warm)),fixed=True)

def fit(path, total):
    cfg=json.loads((path/"config.json").read_text());torch.manual_seed(cfg["seed"])
    net=Actor(cfg["kind"]);opt=torch.optim.Adam(net.parameters(),lr=.00035)
    state=path/"trainer.pt";step=0;logs=[]
    if state.is_file():
        old=torch.load(state,map_location="cpu",weights_only=False)
        net.load_state_dict(old["net"]); opt.load_state_dict(old["opt"]);step=old["step"];logs=old["logs"]
    elif cfg["warm"]:
        net.load_state_dict(torch.load(cfg["warm"],map_location="cpu",weights_only=False)["net"])
    roller=Worker()
    try:
        while step<total:
            t=time.monotonic(); weights=roller.folder/"current.net";net.export(weights)
            rows=roller.run(training_schedule(cfg,weights,step),True); rolled=time.monotonic()
            episodes=prepare(rows);stats=train_update(net,opt,episodes,cfg["seed"]+step)
            step+=1
            logs.append(dict(update=step,duels=step*64,heads=sum(len(e) for e in episodes),
                             score=np.mean([r[0] for r in rows]),rollout_seconds=rolled-t,seconds=time.monotonic()-t,**stats))
            buf=io.BytesIO();torch.save(dict(step=step,net=net.state_dict(),opt=opt.state_dict(),logs=logs),buf)
            atomic(state,buf.getvalue());net.export(path/"current.net")
            write_json(path/"progress.json",logs[-1])
            if step==1 or step%16==0:print(path.name,logs[-1],flush=True)
    finally:roller.close()
    return logs

if __name__=="__main__":
    p=argparse.ArgumentParser();p.add_argument("dir",type=Path);p.add_argument("until",type=int)
    args=p.parse_args();fit(args.dir.resolve(),args.until)
