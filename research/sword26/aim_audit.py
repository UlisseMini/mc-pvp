"""Count actual Java target picks with the opponent's visible hitbox within sword reach."""
import argparse
import csv
import json
from pathlib import Path


def audit(run,destination):
    matches=json.loads((run/"results.json").read_text())
    metadata=json.loads((run/"metadata.json").read_text())
    if metadata.get("aim_profile")!="perfect":raise ValueError("These checks require native perfect-aim telemetry")
    paired={}
    missed=False
    for match in matches:
        stem=match["id"];actor=match["policy"]
        for side in "AB":
            with (run/(stem+"-"+side+"-aim.tsv")).open() as f: picks=list(csv.DictReader(f,delimiter="\t"))
            with (run/(stem+"-"+side+".tsv")).open() as f: inputs=list(csv.DictReader(f,delimiter="\t"))
            if not picks or [p["tick"] for p in picks]!=[r["tick"] for r in inputs] or any(p["press"]!=r["press"] for p,r in zip(picks,inputs)):
                raise ValueError(f"Missing native pick or mismatched click on {stem} {side}")
            reachable=[p for p in picks if float(p["distanceSq"])<9]
            missed|=any(p["targetUnderCrosshair"]!="true" for p in reachable)
            clicked=[p for p in reachable if p["press"]=="true"]
            role="actor" if side=="AB"[match["seat"]] else "opponent"
            key=(match["rtt"],match["pair"],match["seat"],Path(match["rival"]).name,role)
            data=paired.setdefault(key,{})
            for label,value in (("ticks",len(picks)),("reachable",len(reachable)),("targeted",sum(p["targetUnderCrosshair"]=="true" for p in reachable)),("reachable_clicks",len(clicked)),("targeted_clicks",sum(p["targetUnderCrosshair"]=="true" for p in clicked))):
                data[actor+"_"+label]=value
    fields=["rtt","opening","seat","rival","role"]
    columns=sorted(set.union(*(set(r) for r in paired.values())),key=lambda k:(k.startswith("wtap_"),k))
    with destination.open("w") as f:
        w=csv.writer(f,lineterminator="\n");w.writerow([*fields,*columns])
        for key,vals in sorted(paired.items()):w.writerow([*key,*[vals.get(k,"") for k in columns]])
    if missed:
        from aim_boundary_audit import audit as audit_boundary
        name="aim-boundary.csv" if destination.name=="native-aim.csv" else destination.stem+"-boundary.csv"
        audit_boundary(run,destination.with_name(name))


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run",type=Path);parser.add_argument("output",type=Path)
    args=parser.parse_args();audit(args.run,args.output)
