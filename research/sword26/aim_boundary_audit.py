"""Explain geometrically in-range native miss samples without changing the measured pick counts."""
import argparse
import csv
import json
import math
import os
import struct
import subprocess
from pathlib import Path

from audit import rows
from setup import BUILD, compile_agent, jdk


def audit(run, output):
    columns = ["match", "policy", "role", "seat", "rtt_ms", "tick", "pressed",
               "geometric_nearest", "recorded_ray_distance", "relaxed_lower_bound",
               "closest_tested_native_ray", "yaw", "pitch", "potential_headings", "native_pitch_states", "classification"]
    eye_height = struct.unpack("f", struct.pack("f", 1.62))[0]
    classpath = None
    with output.open("w") as result:
        writer = csv.DictWriter(result, fieldnames=columns, lineterminator="\n")
        writer.writeheader()
        for game in json.loads((run / "results.json").read_text()):
            for i, side in enumerate("AB"):
                stem = f"{game['id']}-{side}"
                inputs = rows(run / (stem + ".tsv"))
                picks = rows(run / (stem + "-aim.tsv"))
                for r, pick in zip(inputs, picks, strict=True):
                    if r["tick"] != pick["tick"]:
                        raise ValueError("Unaligned native sample")
                    if float(pick["distanceSq"]) >= 9 or pick["targetUnderCrosshair"] == "true":
                        continue
                    if classpath is None:
                        classpath = os.pathsep.join([str(compile_agent()), (BUILD/"classpath").read_text()])
                    args = [r["x"], str(float(r["y"])+eye_height), r["z"], r["targetX"],
                            r["targetY"], r["targetZ"], r["yaw"], r["pitch"]]
                    command = [jdk()/"java", "-XX:ActiveProcessorCount=1", "-cp", classpath, "sword.AimBoundary", *args]
                    output_text = subprocess.check_output(command, cwd=BUILD, text=True)
                    data = next(line.split(",")[1:] for line in output_text.splitlines() if line.startswith("AIM_DISTANCE,"))
                    geometric, recorded, lower, best, yaw, pitch = map(float, data[:6])
                    headings, pitches = map(int, data[6:])
                    if abs(geometric-math.sqrt(float(pick["distanceSq"]))) > 1e-9:
                        raise ValueError("Offline box differs from the native recorded hitbox")
                    classification = ("legal_native_ray_found" if best < 3 else
                                      "no_native_bearing_in_reach" if lower >= 3 else "no_native_ray_in_reach")
                    writer.writerow(dict(match=game["id"], policy=game["policy"], role="fighter" if i==game["seat"] else "opponent",
                                         seat=side, rtt_ms=game["rtt"], tick=r["tick"], pressed=r["press"],
                                         geometric_nearest=geometric, recorded_ray_distance=recorded, relaxed_lower_bound=lower,
                                         closest_tested_native_ray=best, yaw=yaw, pitch=pitch, potential_headings=headings,
                                         native_pitch_states=pitches, classification=classification))


if __name__ == "__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run",type=Path)
    parser.add_argument("output",type=Path)
    args=parser.parse_args()
    audit(args.run,args.output)
