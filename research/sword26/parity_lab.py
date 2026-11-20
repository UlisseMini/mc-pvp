"""Compare the in-memory duel with frozen recordings from both 26.3 clients and the server."""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import subprocess

from setup import SRC, compile_sim, jdk

FIELDS = ("id lag steps median_position p95_position p95_display first_quarter_block first_hp_difference "
          "equal_hp_observations equal_keys predicted_hp_a predicted_hp_b java_hp_a java_hp_b "
          "exact_motion_observations observations predicted_contacts java_contacts predicted_end_tick "
          "ordered_contacts_exact java_end_tick final_health_exact max_position max_display").split()


def frozen_weights(run, meta):
    weights = {}
    for path in SRC.rglob("*.net"):
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        weights[digest] = path
    primary = weights.get(meta["weights_sha256"])
    if primary is None:
        raise ValueError(f"Missing frozen candidate for {run}")
    rivals = {p: weights[h] for p,h in meta["opponent_hashes"].items()}
    return primary, rivals


def compare(run, destination, jar=None):
    meta = json.loads((run/"metadata.json").read_text())
    if meta.get("clock") not in ("fast", "realtime"):
        raise ValueError("Trajectory replay requires a recorded tick-indexed packet schedule")
    primary, rivals = frozen_weights(run, meta)
    jar = jar or compile_sim()
    matches = json.loads((run/"results.json").read_text())
    if len(matches) != 4 * meta["pairs"] * len(meta["opponents"]):
        raise ValueError("Incomplete native panel")
    pairs = {}
    for match in matches:
        arm, seat, stem = match["policy"], match["seat"], match["id"]
        enemy = rivals.get(match["rival"], match["rival"])
        controls = [enemy, enemy]
        controls[seat] = primary if arm == "learned" else "wtap"
        key = (Path(match["rival"]).name, match["rtt"], match["pair"], seat)
        for mode in ("keys_and_look", "policy"):
            cmd = [jdk()/"java", "-cp", jar, "sword.KernelReplay", run, stem, str(match["rtt"]//100)]
            if mode == "policy":
                cmd.extend(controls)
            output = subprocess.check_output(cmd, text=True).strip().split(",")
            values = dict(zip(FIELDS, output, strict=True))
            result = pairs.setdefault((*key, mode), {})
            if arm in result:
                raise ValueError("Repeated reference arm")
            result[arm] = values
    with destination.open("w") as file:
        w = csv.writer(file,lineterminator="\n")
        w.writerow(["rival","rtt","opening","seat","mode",*[f"{a}_{f}" for a in ("learned","wtap") for f in FIELDS]])
        for key, arms in sorted(pairs.items()):
            w.writerow([*key,*[arms[a][f] for a in ("learned","wtap") for f in FIELDS]])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path)
    parser.add_argument("output_csv", type=Path)
    version = parser.add_mutually_exclusive_group()
    version.add_argument("--jar", type=Path, help="Replay a frozen simulator build")
    version.add_argument("--revision", help="Build the standalone simulator from this Git revision")
    args = parser.parse_args()
    compare(args.run, args.output_csv, compile_sim(args.revision) if args.revision else args.jar)
