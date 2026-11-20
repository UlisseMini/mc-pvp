"""Publish the frozen real-client comparison, including raw observations and paired scores."""
import csv
import gzip
import io
import json
from pathlib import Path
import statistics
import sys
import tarfile
from audit import audit


def export(run,dest,label):
    meta=json.loads((run/"metadata.json").read_text())
    data=audit(run)
    (dest/(label+"-audit.json")).write_text(json.dumps(data,indent=2)+"\n")
    pairs={}
    for m in data:
        key=(Path(m["rival"]).name,m["rtt"],m["pair"],m["seat"])
        pairs.setdefault(key,{})[m["policy"]]=m
    with (dest/(label+"-scores.csv")).open("w") as out:
        writer=csv.writer(out)
        writer.writerow(["opponent","added_rtt_ms","opening","seat","learned_hp","opponent_hp","wtap_hp","wtap_opponent_hp","learned_presses_per_second","wtap_presses_per_second","learned_critical","wtap_critical","learned_fresh","wtap_fresh","learned_upgrades","wtap_upgrades","learned_round","wtap_round"])
        for key,p in sorted(pairs.items()):
            a,b=p["learned"],p["wtap"]
            writer.writerow([*key,a["hp"],a["opponent_hp"],b["hp"],b["opponent_hp"],round(a["sampled_cps"],3),round(b["sampled_cps"],3),a["events"]["critical"],b["events"]["critical"],a["events"]["fresh"],b["events"]["fresh"],a["events"]["upgrade"],b["events"]["upgrade"],a["id"],b["id"]])
    with tarfile.open(dest/(label+"-traces.tar.gz"),"w:gz") as archive:
        for m in data:
            for f in sorted(run.glob(m["id"]+"*")):archive.add(f,arcname=f.name)
        for f in ["metadata.json","results.json"]:archive.add(run/f,arcname=f)
    return data


def record(rows):
    return f"{sum(r['opponent_hp']==0 for r in rows)}–{sum(r['hp']==0 for r in rows)}–{sum(r['hp']>0 and r['opponent_hp']>0 for r in rows)}"


def spread(xs):
    return f"{statistics.median(xs):+.2f} [{min(xs):+.2f}, {max(xs):+.2f}]"


def describe(data):
    lines=["| Opponent | Added RTT | Bot W–L–D | W-tap W–L–D | Bot HP margin, median [range] | W-tap HP margin, median [range] |","|---|---:|---:|---:|---:|---:|"]
    for opp,rtt in sorted({(Path(x["rival"]).name,x["rtt"]) for x in data}):
        sub=[r for r in data if Path(r["rival"]).name==opp and r["rtt"]==rtt]
        a=[r for r in sub if r["policy"]=="learned"];b=[r for r in sub if r["policy"]=="wtap"]
        lines.append(f"| {opp} | {rtt} ms | {record(a)} | {record(b)} | {spread([r['hp']-r['opponent_hp'] for r in a])} | {spread([r['hp']-r['opponent_hp'] for r in b])} |")
    return "\n".join(lines)


if __name__=="__main__":
    target=Path(__file__).resolve().parent/"evidence/frozen"
    all_rows=[]
    for i,arg in enumerate(sys.argv[1:]):
        all_rows+=export(Path(arg),target,["native","independent-native"][i])
    print(describe(all_rows))
