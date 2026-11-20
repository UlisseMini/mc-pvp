"""Measure opening swing opportunities after the fighters and native results are frozen."""
import argparse
import csv
import json
from pathlib import Path

from audit import rows
from first_ko import correction
from technique_audit import aimed_and_ready


def opening(run, game):
    side = "AB"[game["seat"]]
    who = "SwordLab" + side
    inputs = rows(run / (game["id"] + "-" + side + ".tsv"))
    contacts = rows(run / (game["id"] + "-contacts.tsv"))
    revised = correction(run, game["id"], game["seat"], game["hp"], game["opponent_hp"], game["ticks"])
    if revised:
        game = game | revised
        end = revised["first_ko_epoch_ms"]
        inputs = [row for row in inputs if int(row["epochMs"]) <= end]
        knockout = next(i for i, e in enumerate(contacts) if float(e["defenderHP"]) <= 0)
        contacts = contacts[:knockout + 1]
    press = next((int(k["epochMs"]) for k in inputs if k["press"] == "true"), None)
    first = contacts[0] if contacts else None
    own = next((e for e in contacts if e["attacker"] == who), None)
    window_end = min(x for x in (int(first["epochMs"]) if first else None, press, int(inputs[-1]["epochMs"])) if x is not None)
    offered = [int(k["epochMs"]) for k in inputs if int(k["epochMs"]) < window_end and
               k["press"] == "false" and float(k["hp"]) == 20 and aimed_and_ready(k)]
    return dict(won=game["opponent_hp"] == 0, opportunities=len(offered),
                first_opportunity_to_contact_ms=int(first["epochMs"])-offered[0] if offered and first else None,
                landed_first=first["attacker"] == who if first else None,
                first_own_contact_ticks_after_opening_hit=int(own["tick"])-int(first["tick"]) if own else None,
                first_own_contact_critical=own["critical"] == "true" if own else None,
                total_critical=sum(e["attacker"] == who and e["critical"] == "true" for e in contacts),
                total_contacts=sum(e["attacker"] == who for e in contacts))


def export(run, output):
    games = json.loads((run / "results.json").read_text())
    paired = {}
    for game in games:
        key = (game["rival"], game["rtt"], game["pair"], game["seat"])
        arms = paired.setdefault(key, {})
        if game["policy"] in arms:
            raise ValueError("Repeated arm in opening")
        arms[game["policy"]] = opening(run, game)
    with output.open("w") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        fields = next(iter(paired.values()))["wtap"].keys()
        writer.writerow(["opponent","rtt","opening","seat","policy",*[arm+"_"+f for arm in ("bot","wtap") for f in fields]])
        for key, arms in sorted(paired.items()):
            for label, stats in sorted(arms.items()):
                if label != "wtap":
                    writer.writerow([*key,label,*stats.values(),*arms["wtap"].values()])


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("run", type=Path)
    p.add_argument("output", type=Path)
    args = p.parse_args()
    export(args.run, args.output)
