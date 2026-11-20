"""Evaluate frozen weights against registered opponents and matched W-tap controls."""
import argparse
import json
from pathlib import Path
import subprocess

from setup import SRC, registered_sim, jdk


def grade(protocol, weights, output):
    plan = json.loads(protocol.read_text())
    output.mkdir(parents=True, exist_ok=True)
    java = [jdk() / "java", "-Dsword.transport=lockstep", "-cp", registered_sim(plan)]
    for opponent in plan["opponents"]:
        rival = "wtap" if opponent == "wtap" else SRC / opponent
        for name, entry in [("grade", "Grade"), ("maintenance", "Maintenance")]:
            with (output / f"{name}-{Path(opponent).stem}.csv").open("w") as file:
                subprocess.run([*java, f"sword.{entry}", weights, str(plan["model_grade_seed"]),
                                str(plan["model_openings_per_latency"]), rival], stdout=file, check=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("protocol", type=Path)
    parser.add_argument("weights", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    grade(args.protocol, args.weights, args.output)
