"""Measure when ordinary Java actually applies attacks relative to native input and server ticks."""
import argparse
import csv
import json
from pathlib import Path

from audit import rows


def measure(run, destination):
    meta = json.loads((run / "metadata.json").read_text())
    if meta["clock"] is not None or meta.get("aim_profile") != "perfect":
        raise ValueError("Use an ordinary Java recording with actual mouse-pick telemetry")
    pairs = {}
    for game in json.loads((run / "results.json").read_text()):
        stem = game["id"]
        contacts = rows(run / (stem + "-contacts.tsv"))
        server = {int(r["tick"]): int(r["epochMs"]) for r in rows(run / (stem + "-server.tsv"))}
        for index, side in enumerate("AB"):
            inputs = rows(run / f"{stem}-{side}.tsv")
            picks = rows(run / f"{stem}-{side}-aim.tsv")
            clicks = []
            for sample, pick in zip(inputs, picks, strict=True):
                if sample["tick"] != pick["tick"] or sample["press"] != pick["press"]:
                    raise ValueError(f"Misaligned client samples in {stem} {side}")
                if pick["press"] == "true" and pick["targetUnderCrosshair"] == "true":
                    clicks.append(int(sample["epochMs"]))
            own = [hit for hit in contacts if hit["attacker"] == "SwordLab" + side]
            tick_offsets = []
            excess_ms = []
            for hit in own:
                stamp = int(hit["epochMs"])
                tick_offsets.append(stamp - server[int(hit["tick"])])
                # Only attribute a hit to a click when the broad time window
                # contains exactly one eligible native pick. Retain the denominator.
                elapsed = [stamp - click - game["rtt"] / 2 for click in clicks]
                possible = [dt for dt in elapsed if -2 <= dt <= 160]
                if len(possible) == 1:
                    excess_ms.append(possible[0])
            role = "fighter" if game["seat"] == index else "opponent"
            key = game["rtt"], game["pair"], game["seat"], Path(game["rival"]).name, role
            arms = pairs.setdefault(key, {})
            arms[game["policy"]] = (len(own), sum(abs(v) <= 1 for v in tick_offsets),
                                    len(excess_ms), json.dumps(tick_offsets), json.dumps(excess_ms))
    labels = ("accepted", "within_1ms_of_server_tick", "unique_clicks",
              "contact_minus_server_poll_ms", "click_to_contact_minus_half_rtt_ms")
    with destination.open("w") as f:
        writer = csv.writer(f, lineterminator="\n")
        writer.writerow(["rtt_ms", "opening", "seat", "opponent", "role", "policy",
                         *[f"{arm}_{field}" for arm in ("learned", "wtap") for field in labels]])
        for key, arms in sorted(pairs.items()):
            for label, values in sorted(arms.items()):
                if label != "wtap":
                    writer.writerow([*key, label, *values, *arms["wtap"]])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    measure(args.run, args.output)
