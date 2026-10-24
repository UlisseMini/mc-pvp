#!/usr/bin/env python3
"""Independent m0362 click-order ablation; prints JSON, never changes native state.

Run from any directory: python3 research/click-order-reproduction.py
Uses archived traces, temporary Java compilation, and the current ComboModel.
Unlike clock_replay_check.py, assigns clicks by enumerating the first later
movement event rather than by subtracting two bisect results.
"""
import gzip
import hashlib
import json
import math
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from lab import JAVA, JAVAC, RUNTIME


def read_trace(side):
    with gzip.open(ROOT / f"research/traces/m0362-{side}.jsonl.gz", "rt") as source:
        return [json.loads(line) for line in source]


def main():
    records = {side: read_trace(side) for side in ("a", "b", "server")}
    moves = {side: [e for e in records[side] if e["type"] == "science_move"]
             for side in ("a", "b")}
    base = min(moves[side][0]["time"] for side in moves)
    initial = {side: moves[side][0]["before"] for side in moves}
    offset = initial["a"]["posX"]

    def position(body):
        return [body["posX"] - offset, body["posY"] - 4, body["posZ"]]

    views = {side: next(e["before"] for e in records[side]
                       if e["type"] == "science_interpolation") for side in moves}
    header = ["INIT", 0, 0, 0, 0, *position(initial["a"]), *position(initial["b"]),
              *position(views["a"]), *position(views["b"])]
    events, reassigned = [], []
    for side in ("a", "b"):
        rows = [{"time": e["time"] - base, "kind": side.upper(), "native": e,
                 "action": "SPRINT" if e["forward"] > .01 else "RELEASE",
                 "wall": 0, "monotonic": 0}
                for e in moves[side] if 0 <= e["time"] - base <= 10000]
        for click in (e for e in records[side] if e["type"] == "click"):
            if click["time"] < base - 50:
                continue
            assigned = {}
            for mode, field in (("wall", "time"), ("monotonic", "monotonic_ns")):
                # The former wall-ms interval excludes all equal-ms clicks.
                # Monotonic order resolves which side of travel the click used.
                target = next((r for r in rows if r["native"][field] > click[field]), None)
                if target is not None:
                    target[mode] += 1
                    assigned[mode] = target["time"]
            if assigned.get("wall") != assigned.get("monotonic"):
                reassigned.append({"side": side, "injection_ms": click["time"] - base,
                                   "injection_monotonic_ns": click["monotonic_ns"], **assigned})
        assert all(r[mode] <= 1 for r in rows for mode in ("wall", "monotonic"))
        events.extend(rows)
    events.extend({"time": e["time"] - base, "kind": "SERVER", "action": "-",
                   "wall": 0, "monotonic": 0, "native": e}
                  for e in records["server"] if e["type"] == "tick"
                  and 0 <= e["time"] - base <= 10000)
    events.sort(key=lambda e: (e["time"], {"SERVER": 0, "A": 1, "B": 2}[e["kind"]]))
    report = {"match": "m0362", "base_ms": base, "head_yaw": True, "fall_damage": True,
              "server_timestamp_shift_ms": 0, "reassigned_clicks": reassigned,
              "model_sha256": hashlib.sha256((ROOT / "src/lab/ComboModel.java").read_bytes()).hexdigest(),
              "native_hits_ms": [e["time"] - base for e in records["server"]
                                 if e["type"] == "hit" and 0 <= e["time"] - base <= 10000]}
    report["causal_native_events"] = {
        "a_click_engine_and_travel": [e for e in records["a"]
                                     if e.get("time") == base + 5610
                                     and e["type"] in ("click", "engine_attack", "science_move")],
        "server_fresh_hit": [e for e in records["server"]
                             if e.get("time") == base + 5640 and e["type"] == "hit"],
        "b_velocity_packet_and_travel": [e for e in records["b"]
                                         if (e.get("time") == base + 5648 and e["type"] == "velocity_packet")
                                         or (e.get("time") == base + 5660 and e["type"] == "science_move")]}
    with tempfile.TemporaryDirectory(prefix="click-order-", dir=RUNTIME / "tmp") as classes:
        subprocess.run([str(JAVAC), "-proc:none", "-d", classes,
                        str(ROOT / "src/lab/ComboModel.java"),
                        str(ROOT / "tests/NativeClockReplay.java")], check=True)
        for mode in ("wall", "monotonic"):
            payload = "\t".join(map(str, header)) + "\n" + "\n".join(
                f"{e['time']}\t{e['kind']}\t{e['action']}\t{e[mode]}" for e in events) + "\n"
            run = subprocess.run([str(JAVA), "-Dlab.model_head_yaw=true",
                                  "-Dlab.model_fall_damage=true", "-cp", classes, "NativeClockReplay"],
                                 input=payload, capture_output=True, text=True, check=True)
            lines = run.stdout.splitlines()
            outputs = [dict(zip(lines[0].split("\t"), line.split("\t"))) for line in lines[1:]]
            assert len(outputs) == len(events)
            rows, hits, previous_hits = [], [], 0
            for event, output in zip(events, outputs):
                count = int(float(output["accepted_fresh_hits"]))
                if count > previous_hits:
                    hits.append(event["time"])
                    previous_hits = count
                if event["kind"] == "SERVER":
                    continue
                side = event["kind"].lower()
                error = math.dist(position(event["native"]["after"]),
                                  [float(output[f"{side}_{axis}"]) for axis in "xyz"])
                rows.append({"time_ms": event["time"], "side": side, "error_m": error})
            report[mode] = {
                "predicted_fresh_hits_ms": hits,
                "horizons": {h: {s: min((r for r in rows if r["side"] == s),
                                        key=lambda r: abs(r["time_ms"] - h))["error_m"]
                                  for s in ("a", "b")}
                             for h in (150, 500, 1000, 2000, 5000, 5660, 6000, 7000, 9000, 9950)},
                "first_error_above_0_1m": next((r for r in rows if r["error_m"] > .1), None),
                "first_error_above_0_25m": next((r for r in rows if r["error_m"] > .25), None),
                "max_error_m": {s: max(r["error_m"] for r in rows if r["side"] == s)
                                for s in ("a", "b")}}
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
