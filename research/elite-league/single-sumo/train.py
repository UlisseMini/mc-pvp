import argparse
import io
import time
from learner import *

def configure(path, kind, role, seed, draw_seed, incumbent, counter, families, scores=None, warm=None, threats=None):
    config=dict(kind=kind,role=role,seed=seed,draw_seed=draw_seed,
               action_seed=seed*101+1,incumbent=incumbent,counter=counter,families=families,
               scores=scores or {},warm=None if warm is None else str(warm))
    if threats:config["threats"]=list(dict.fromkeys(threats))
    write_json(path/"config.json",config,fixed=True)

def fit(path, total, schedule=training_schedule, warm_override=None):
    cfg=json.loads((path/"config.json").read_text());torch.manual_seed(cfg["seed"])
    net=Actor(cfg["kind"])
    labels=cfg.get("auxiliary_opponents",[])
    classifier=nn.Linear(32,len(labels)) if labels else None
    params=list(net.parameters())+(list(classifier.parameters()) if labels else [])
    opt=torch.optim.Adam(params,lr=.00035)
    state=path/"trainer.pt";step=0;logs=[]
    if state.is_file():
        old=torch.load(state,map_location="cpu",weights_only=False)
        net.load_state_dict(old["net"]); opt.load_state_dict(old["opt"]);step=old["step"];logs=old["logs"]
        if classifier is not None:classifier.load_state_dict(old["auxiliary"])
        if step!=logs[-1]["update"]:raise ValueError("Checkpoint and progress disagree")
        net.export(path/"current.net");write_json(path/"progress.json",logs[-1])
    elif cfg["warm"]:
        previous=torch.load(ROOT/(warm_override or cfg["warm"]),map_location="cpu",weights_only=False)
        net.load_state_dict(previous["net"])
        if cfg["role"] in ("main","mlp") and "opt" in previous and "logs" in previous and "auxiliary" not in previous:
            opt.load_state_dict(previous["opt"])
    roller=Worker()
    try:
        while step<total:
            t=time.monotonic(); weights=roller.folder/"current.net";net.export(weights)
            fixtures=schedule(cfg,weights,step)
            rows=roller.run(fixtures,True); rolled=time.monotonic()
            episodes=prepare(rows)
            if labels:
                for i,(episode,fixture) in enumerate(zip(episodes,fixtures,strict=True)):
                    # Supervision enters only the loss. The exported policy always sees 25 legal features.
                    heads=[n-1 for n in (12,24,48,96) if n<=len(episode)]
                    training=episode.new_zeros((len(episode),2))
                    training[:,0]=labels.index(fixture[1])
                    if heads:training[heads,1]=1/len(heads)
                    episodes[i]=torch.cat((episode,training),dim=1)
            stats=train_update(net,opt,episodes,cfg["seed"]+step,style_classifier=classifier)
            step+=1
            logs.append(dict(update=step,duels=step*64,heads=sum(len(e) for e in episodes),
                             score=np.mean([r[0] for r in rows]),rollout_seconds=rolled-t,seconds=time.monotonic()-t,**stats))
            snapshot=dict(step=step,net=net.state_dict(),opt=opt.state_dict(),logs=logs)
            if classifier is not None:snapshot["auxiliary"]=classifier.state_dict()
            buf=io.BytesIO();torch.save(snapshot,buf)
            atomic(state,buf.getvalue());net.export(path/"current.net")
            write_json(path/"progress.json",logs[-1])
            if step==1 or step%16==0:print(path.name,logs[-1],flush=True)
    finally:roller.close()
    return logs

if __name__=="__main__":
    p=argparse.ArgumentParser();p.add_argument("dir",type=Path);p.add_argument("until",type=int)
    args=p.parse_args();fit(args.dir.resolve(),args.until)
