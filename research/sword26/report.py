"""Publish the frozen real-client comparison, including raw observations and paired scores."""
import csv
import gzip
import json
from pathlib import Path
import statistics
import sys
import tarfile
from audit import audit


def export(run,dest,label):
    meta=json.loads((run/"metadata.json").read_text())
    data=audit(run)
    arms_expected={"wtap","learned",*(f"learned-{i+1}" for i in range(len(meta.get("extra_weights",[]))))}
    human=meta["names"][1]!="SwordLabB"
    opponents=["human"] if human else meta["opponents"]
    openings={(rival,rtt,i,seat) for rival in opponents for rtt in meta["rtts"]
              for i in range(meta["pairs"]) for seat in range(1 if human else 2)}
    pairs={}
    for m in data:
        key=(m["rival"],m["rtt"],m["pair"],m["seat"])
        arms=pairs.setdefault(key,{})
        if m["policy"] in arms:raise ValueError(f"Duplicate {m['policy']} arm in {key}")
        arms[m["policy"]]=m
    if pairs.keys()!=openings or any(arms.keys()!=arms_expected for arms in pairs.values()):
        raise ValueError("A complete paired panel with a W-tap control is required")
    (dest/(label+"-audit.json")).write_text(json.dumps(data,indent=2)+"\n")
    with (dest/(label+"-scores.csv")).open("w") as out:
        writer=csv.writer(out,lineterminator="\n")
        writer.writerow(["opponent","added_rtt_ms","opening","seat","policy","learned_hp","opponent_hp","wtap_hp","wtap_opponent_hp","learned_presses_per_second","wtap_presses_per_second","learned_critical","wtap_critical","learned_fresh","wtap_fresh","learned_upgrades","wtap_upgrades","learned_round","wtap_round"])
        for key,arms in sorted(pairs.items()):
            b=arms["wtap"]
            for policy,a in sorted(arms.items()):
                if policy=="wtap":continue
                writer.writerow([*key,policy,a["hp"],a["opponent_hp"],b["hp"],b["opponent_hp"],round(a["sampled_cps"],3),round(b["sampled_cps"],3),a["events"]["critical"],b["events"]["critical"],a["events"]["fresh"],b["events"]["fresh"],a["events"]["upgrade"],b["events"]["upgrade"],a["id"],b["id"]])
    with (dest/(label+"-traces.tar.gz")).open("wb") as raw, \
         gzip.GzipFile(filename="",fileobj=raw,mode="wb",mtime=0) as packed, \
         tarfile.open(fileobj=packed,mode="w") as archive:
        for m in data:
            for f in sorted(run.glob(m["id"]+"*")):archive.add(f,arcname=f.name)
        for f in ["metadata.json","results.json"]:archive.add(run/f,arcname=f)
    return data


def record(rows):
    return f"{sum(r['opponent_hp']==0 for r in rows)}–{sum(r['hp']==0 for r in rows)}–{sum(r['hp']>0 and r['opponent_hp']>0 for r in rows)}"


def spread(xs):
    return f"{statistics.median(xs):+.2f} [{min(xs):+.2f}, {max(xs):+.2f}]"


def describe(data):
    lines=["| Opponent | Added RTT | Policy | Bot W–L–D | W-tap W–L–D | Bot HP margin, median [range] | W-tap HP margin, median [range] |","|---|---:|---|---:|---:|---:|---:|"]
    for opp,rtt,policy in sorted({(x["rival"],x["rtt"],x["policy"]) for x in data if x["policy"]!="wtap"}):
        sub=[r for r in data if r["rival"]==opp and r["rtt"]==rtt]
        a=[r for r in sub if r["policy"]==policy];b=[r for r in sub if r["policy"]=="wtap"]
        lines.append(f"| {Path(opp).name} | {rtt} ms | {policy} | {record(a)} | {record(b)} | {spread([r['hp']-r['opponent_hp'] for r in a])} | {spread([r['hp']-r['opponent_hp'] for r in b])} |")
    return "\n".join(lines)


if __name__=="__main__":
    root=Path(__file__).resolve().parent/"evidence"
    panels=[("frozen","native"),("frozen","independent-native"),("response","native"),("movement","native"),("movement","cooldown-check")]
    for arg,(folder,label) in zip(sys.argv[1:],panels):
        print(folder,label)
        print(describe(export(Path(arg),root/folder,label)))
