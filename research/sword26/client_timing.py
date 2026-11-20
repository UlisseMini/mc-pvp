"""Compare a registered active-client crossover with its original ordinary-Java fights."""
import argparse
import csv
import json
from pathlib import Path

from audit import pacing, rows
from first_ko import correction


def panel(run):
    meta = json.loads((run / "metadata.json").read_text())
    if meta["clock"] is not None or meta["speed"] != 1:
        raise ValueError("Expected ordinary Java at normal speed")
    actors = {"learned": meta["weights_sha256"], "wtap": "wtap"}
    actors.update({f"learned-{i}": a["sha256"] for i, a in enumerate(meta["extra_weights"], 1)})
    bouts = {}
    for r in json.loads((run / "results.json").read_text()):
        key = (actors[r["policy"]], meta["opponent_hashes"][r["rival"]], r["rtt"], r["pair"], r["seat"])
        if key in bouts:
            raise ValueError(f"Duplicate comparison cell {key}")
        bouts[key] = r
    return bouts


def measure(run, r):
    r = r | correction(run, r["id"], r["seat"], r["hp"], r["opponent_hp"], r["ticks"])
    data = dict(round=r["id"], score=1 if r["opponent_hp"] == 0 else 0 if r["hp"] == 0 else .5,
                hp=r["hp"], enemy_hp=r["opponent_hp"])
    for label, seat in (("actor", r["seat"]), ("rival", 1 - r["seat"])):
        keys = rows(run / (r["id"] + "-" + "AB"[seat] + ".tsv"))
        if "first_ko_epoch_ms" in r:
            keys = [k for k in keys if int(k["epochMs"]) <= r["first_ko_epoch_ms"]]
        for name, value in pacing(keys).items():
            data[label + "_" + name] = value
    return data


def compare(old, active, dest):
    before, after = panel(old), panel(active)
    output = []
    for key, new in sorted(after.items()):
        previous = before[key]
        if (previous["gap"], previous["angle"]) != (new["gap"], new["angle"]):
            raise ValueError(f"Opening geometry changed for {key}")
        record = dict(zip(("actor", "rival", "rtt", "opening", "seat"), key, strict=True))
        record.update(angle=new["angle"], gap=new["gap"])
        for phase, run, bout in (("old", old, previous), ("active", active, new)):
            record.update({phase + "_" + k: v for k, v in measure(run, bout).items()})
        output.append(record)
    with dest.open("w") as f:
        writer = csv.DictWriter(f, fieldnames=output[0], lineterminator="\n")
        writer.writeheader()
        writer.writerows(output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    for name in ("old", "active", "destination"):
        parser.add_argument(name, type=Path)
    args = parser.parse_args()
    compare(args.old, args.active, args.destination)
