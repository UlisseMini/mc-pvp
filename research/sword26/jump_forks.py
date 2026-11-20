"""Pair validated one-tick jump interventions with the corresponding native W-tap arm."""
import argparse
import csv
import json
from pathlib import Path
import subprocess
from setup import compile_sim, jdk


def analyze(run,out):
    classes=compile_sim()
    meta=json.loads((run/"metadata.json").read_text())
    if meta.get("clock") not in ("fast","realtime"):
        raise ValueError("A recorded tick schedule is required")
    pairs={}
    for r in json.loads((run/"results.json").read_text()):
        key=(Path(r["rival"]).name,r["rtt"],r["pair"],r["seat"])
        cmd=[jdk()/"java","-cp",classes,"sword.JumpFork",run,r["id"],str(r["seat"]),str(r["rtt"]//100)]
        rows=[line.split(",") for line in subprocess.check_output(cmd,text=True).splitlines()]
        values=[float(step) for _,matched,step in rows if matched=="true"]
        pairs.setdefault(key,{})[r["policy"]]=(len(rows),len(values),sum(v>1e-8 for v in values),
                                              json.dumps(values,separators=(",",":")))
    with out.open("w") as f:
        w=csv.writer(f,lineterminator="\n")
        w.writerow(["rival","rtt","opening","seat",*[a+"_"+name for a in ("learned","wtap") for name in
                  ("grounded_edges_with_next_sample","qualified","less_outward_motion","reductions_blocks")]])
        for key,arms in sorted(pairs.items()):
            w.writerow([*key,*arms["learned"],*arms["wtap"]])


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("recording",type=Path)
    parser.add_argument("output",type=Path)
    args=parser.parse_args()
    analyze(args.recording,args.output)
