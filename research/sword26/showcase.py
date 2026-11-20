"""Record a short, separately identified daylight exhibition for the local match room."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time

from aim_audit import audit as audit_aim
from recordings import archive_panel
from report import export as export_traces
from season import ANCHORS,EVIDENCE,WORK,SRC,hash_policy,save


def record(index, actor, opponent, export_frames=False, hd=False, seed=None):
    if hd and not export_frames:raise ValueError("HD recording uses the native frame exporter")
    tag=f"showcase-{index:03d}"
    dest=EVIDENCE/tag;folder=WORK/tag
    dest.mkdir(exist_ok=True);folder.mkdir(exist_ok=True)
    seed=37184255+index if seed is None else seed
    rtts=[0] if export_frames else [0,100]
    reg=dict(actors=[str(actor.relative_to(SRC))],opponents=[str(opponent.relative_to(SRC))],
             actor_hashes=[hash_policy(actor)],opponent_hashes=[hash_policy(opponent)],seed=seed,pairs=1,
             clock="scheduled Java frame export" if export_frames else "ordinary TCP exhibition",rtts=rtts)
    if hd:reg["size"]=[1280,720]
    if (dest/"panel.json").is_file():
        existing=json.loads((dest/"registration.json").read_text())
        if any(existing.get(k)!=v for k,v in reg.items()):raise ValueError("Recorded exhibition differs from requested lineup")
        return
    registration=dest/"registration.json"
    if registration.exists():
        prior_reg=json.loads(registration.read_text())
        if any(prior_reg.get(k)!=v for k,v in reg.items()):
            raise ValueError("This exhibition already has a different registered lineup or clock")
        aim_profile=prior_reg.get("aim_profile")
    else:
        aim_profile="perfect"
        save(registration,reg|{"aim_profile":aim_profile})
    cmd=[sys.executable,SRC/"native.py","--weights",actor,"--opponents",opponent,"--rtts",*map(str,rtts),
         "--pairs","1","--seed",str(seed),"--showcase"]
    if export_frames:cmd+=["--clock","fast","--export-frames"]
    if hd:cmd+=["--hd"]
    prior=None
    for existing in sorted(folder.glob("native-*.log")):
        for line in existing.read_text().splitlines():
            if line.startswith("Round recordings: "):
                run=Path(line.split(": ",1)[1])
                if (run/"results.json").is_file():prior=run
    if prior is not None:cmd+=["--resume",prior]
    if aim_profile:cmd+=["--aim-profile",aim_profile]
    elif prior is None:cmd+=["--aim-profile","tournament"]
    log=folder/f"native-{time.time_ns()}.log"
    with log.open("w") as out:subprocess.run(cmd,stdout=out,stderr=out,check=True)
    run=Path(next(l.split(": ",1)[1] for l in log.read_text().splitlines() if l.startswith("Round recordings: ")))
    results=json.loads((run/"results.json").read_text())
    if len(results)!=4*len(rtts) or any(not (run/"videos"/(r["id"]+".mp4")).is_file() for r in results):
        raise RuntimeError("Exhibition recordings incomplete")
    archive_panel(tag,run,results)
    if export_frames:export_traces(run,dest,"scheduled")
    if json.loads((run/"metadata.json").read_text()).get("aim_profile")=="perfect":audit_aim(run,dest/"native-aim.csv")
    save(dest/"panel.json",dict(run=run.name,bouts=len(results),purpose="Exhibition, excluded from promotion results"))
    print(f"Saved {len(results)} exhibition videos to {run/'videos'}",flush=True)


if __name__=="__main__":
    p=argparse.ArgumentParser()
    p.add_argument("--index",type=int,default=1);p.add_argument("--actor",type=Path);p.add_argument("--opponent",type=Path)
    p.add_argument("--export-frames",action="store_true")
    p.add_argument("--seed",type=int,help="Reuse an exhibition opening and matchup order")
    p.add_argument("--hd",action="store_true")
    args=p.parse_args();state=json.loads((EVIDENCE/"state.json").read_text())
    record(args.index,(args.actor or SRC/state["incumbent"]).resolve(),(args.opponent or ANCHORS[0]).resolve(),args.export_frames,args.hd,args.seed)
