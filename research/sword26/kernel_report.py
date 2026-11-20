"""Summarize whole-duel agreement and compare independent paced and accelerated Java runs."""
import argparse
import csv
import json
from pathlib import Path

from audit import rows


def outcome(a, b):
    return (float(a) > 0) - (float(b) > 0)


def summarize(files):
    groups = {}
    for path in files:
        with path.open() as f:
            for row in csv.DictReader(f):
                for arm in ("learned", "wtap"):
                    r = {k.removeprefix(arm+"_"): v for k,v in row.items() if k.startswith(arm+"_")}
                    group = groups.setdefault((row["rtt"],row["mode"],arm), [])
                    completed = int(r["predicted_end_tick"]) >= 0
                    group.append(dict(
                        winner=completed and outcome(r["predicted_hp_a"],r["predicted_hp_b"])==outcome(r["java_hp_a"],r["java_hp_b"]),
                        health=completed and r["final_health_exact"]=="1",
                        events=completed and r["ordered_contacts_exact"]=="true" and r["predicted_end_tick"]==r["java_end_tick"],
                        every_observation=r["exact_motion_observations"]==r["observations"] and float(r["max_display"])==0,
                        observed=int(r["observations"]), equal=int(r["exact_motion_observations"]),
                        max_position=float(r["max_position"]), max_display=float(r["max_display"])))
    for (lag, mode, arm), games in sorted(groups.items()):
        n=len(games)
        print(f"{lag}ms {mode} {arm}: {n} games, "+" ".join(f"{k}={sum(g[k] for g in games)}" for k in
              ("winner","health","events","every_observation","equal","observed"))+
              f" max_position={max(g['max_position'] for g in games):.8g} max_display={max(g['max_display'] for g in games):.8g}")


def compare_clocks(fast, paced, output):
    def reference(run):
        return {(m["pair"],m["seat"],m["rival"],m["policy"]):m for m in json.loads((run/"results.json").read_text())}
    def signature(run, game):
        stem=game["id"]
        clients=[]; through_end=[]
        end=int(rows(run/f"{stem}-server.tsv")[-1]["epochMs"])
        for side in "AB":
            tape=rows(run/f"{stem}-{side}.tsv")
            samples=[[v for k,v in tick.items() if k!="epochMs"] for tick in tape]
            clients.append(samples)
            through_end.append([s for s,tick in zip(samples,tape) if int(tick["epochMs"])<=end])
        contacts=[[v for k,v in hit.items() if k!="epochMs"] for hit in rows(run/f"{stem}-contacts.tsv")]
        return (game["hp"],game["opponent_hp"]),game["ticks"],clients,contacts,through_end
    a,b=reference(fast),reference(paced)
    records={}
    for (pair,seat,rival,arm), game in b.items():
        other=a[pair,seat,rival,arm]
        if (game["angle"],game["gap"])!=(other["angle"],other["gap"]):
            raise ValueError("Paced and accelerated openings differ")
        x=signature(fast,other);y=signature(paced,game)
        checks=[outcome(*x[0])==outcome(*y[0]),x[0]==y[0],x[1]==y[1],x[2]==y[2],x[3]==y[3],x[4]==y[4]]
        records.setdefault((game["rtt"],Path(rival).name,pair,seat),{})[arm]=checks
    with output.open("w") as f:
        w=csv.writer(f,lineterminator="\n")
        w.writerow(["rtt","opponent","opening","seat",*[a+"_"+v for a in ("learned","wtap") for v in
                    ("winner","health","terminal_tick","all_client_samples","all_contacts","client_samples_through_termination")]])
        for key,arms in sorted(records.items()):
            w.writerow([*key,*arms["learned"],*arms["wtap"]])


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    sub=parser.add_subparsers(dest="operation",required=True)
    stats=sub.add_parser("summary")
    stats.add_argument("csv",nargs="+",type=Path)
    clocks=sub.add_parser("clocks")
    for name in ("fast","paced","output"):
        clocks.add_argument(name,type=Path)
    args=parser.parse_args()
    if args.operation=="summary":
        summarize(args.csv)
    else:
        compare_clocks(args.fast,args.paced,args.output)
