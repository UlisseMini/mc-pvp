"""Recover exact policy inputs and freeze whole-match splits before fitting."""

from collections import Counter
from concurrent.futures import ProcessPoolExecutor
import gzip
import hashlib
import json
from pathlib import Path
import random
import struct
import subprocess

import numpy as np
import orjson

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
BUILD = ROOT / "build/elite-league/single-sumo"
OUT = BUILD / HERE.name
RESULTS = ROOT / "runtime/results"
POV = ROOT / "runtime/pov"
TEACHER = ROOT / "research/elite-league/sumo-confidence/frozen/v4-ppo-g0496.net"
CONFIG = json.loads((HERE / "protocol.json").read_text())


def write_json(path, value):
    temp = path.with_suffix(".tmp")
    temp.write_text(json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n")
    temp.replace(path)


def net_id(encoded):
    return hashlib.sha256(encoded.encode()).hexdigest()


def inventory():
    controls = {}
    for path in (ROOT / "policies").glob("*.properties"):
        for line in path.read_text().splitlines():
            if line.startswith("duel_network="):
                controls[path.stem] = net_id(line.split("=", 1)[1].strip())
                break
    leader = net_id(TEACHER.read_text().strip())
    aliases = {name for name, key in controls.items() if key == leader}
    counts, teacher_counts = Counter(), Counter()
    fixtures = []
    for path in sorted(RESULTS.glob("m*.json"), key=lambda p: p.name):
        if not path.stem[1:].isdigit() or int(path.stem[1:]) > CONFIG["max_native_match"]:
            continue
        d = orjson.loads(path.read_bytes())
        for side, other in ("ab", "ba"):
            name = d.get(f"policy_{side}")
            if not name:
                continue
            paths = sorted((POV / path.stem).glob(f"{side}-client-*-events.jsonl.gz"))
            if not paths:
                continue
            counts[name] += 1
            if name not in aliases:
                continue
            teacher_counts[name] += 1
            if (len(paths) != 1 or d.get("mode") != "sumo" or
                    d.get("reason") not in ("ring_out", "simultaneous_ring_out", "timeout")):
                continue
            foe = d[f"policy_{other}"]
            fixtures.append(dict(match=path.stem, side=side, policy=name, opponent=foe,
                                 opponent_group=controls.get(foe, foe),
                                 cps=d.get(f"click_cps_{side}"), start=d["start"], finish=d["finish"],
                                 path=str(paths[0].relative_to(ROOT))))
    return fixtures, dict(named_povs=dict(counts), alias_povs=dict(teacher_counts),
                          candidate_povs=len(fixtures))


def read(fixture):
    records = []
    try:
        with gzip.open(ROOT / fixture["path"], "rb") as stream:
            for line in stream:
                if b'"duel_network"' not in line[:60]:
                    continue
                row = orjson.loads(line)
                if row.get("type") != "duel_network" or row.get("match") != fixture["match"]:
                    continue
                records.append(row)
    except (OSError, EOFError, ValueError):
        return None, "Unreadable gzip stream"
    if len(records) < 8:
        return None, "Fewer than eight recorded input decisions"
    ticks = np.array([r["tick"] for r in records])
    if not (np.diff(ticks) == 1).all():
        return None, "Nonconsecutive policy ticks"
    try:
        values = np.array([r["observation"] for r in records], dtype=np.float64)
        applied = np.array([r["action"] for r in records], dtype=np.int64)
        proposed = np.array([r.get("proposed", r["action"]) for r in records], dtype=np.int64)
        active = np.array([fixture["start"] <= r["time"] <= fixture["finish"] for r in records])
    except (KeyError, ValueError):
        return None, "Malformed decisions"
    if (values.shape != (len(ticks), 14) or not np.isfinite(values).all() or
            np.any((applied < 0) | (applied >= 48)) or np.any((proposed < 0) | (proposed >= 48))):
        return None, "Invalid observation or action schema"
    if active.sum() < 8:
        return None, "Fewer than eight in-match inputs"
    return (values, applied, proposed, active), None


def extract(fixtures):
    rows, rejected = [], Counter()
    with ProcessPoolExecutor(max_workers=5) as pool:
        for fixture, (row, reason) in zip(fixtures, pool.map(read, fixtures, chunksize=4)):
            if reason:
                rejected[reason] += 1
            else:
                rows.append((fixture, *row))
    print("Decoded", len(rows), "native perspectives", flush=True)
    inputs, outputs = OUT / "inputs.wire", OUT / "verified.wire"
    wire = np.dtype([("x", ">f8", (14,)), ("action", "u1")])
    with inputs.open("wb") as f:
        f.write(struct.pack(">i", len(rows)))
        for _, x, action, _, _ in rows:
            f.write(struct.pack(">i", len(x)))
            packed = np.empty(len(x), dtype=wire)
            packed["x"], packed["action"] = x, action
            f.write(packed.tobytes())
    classes = OUT / "classes"
    classes.mkdir(exist_ok=True)
    subprocess.run(["javac", "-proc:none", "-sourcepath", ROOT / "src", "-d", classes,
                    HERE / "ReplayInputs.java"], check=True, cwd=ROOT)
    subprocess.run(["java", "-XX:ActiveProcessorCount=2", "-cp", classes,
                    "ReplayInputs", TEACHER, inputs, outputs], check=True, cwd=ROOT)
    full = np.fromfile(outputs, dtype=">f4").reshape(-1, 28).astype(np.float32)
    features, scores, labels, fixtures, cuts = [], [], [], [], [0]
    start = 0
    for fixture, x, _, proposed, active in rows:
        chunk = full[start:start + len(x)]
        start += len(x)
        if np.any(chunk[:, 0] != proposed):
            rejected["Native proposal disagrees with replayed teacher"] += 1
            continue
        features.append(chunk[active, 1:23])
        scores.append(chunk[active, 23:])
        labels.append(proposed[active])
        fixtures.append(fixture)
        cuts.append(cuts[-1] + int(active.sum()))
    if start != len(full):
        raise ValueError("Reconstructed head count changed")
    inputs.unlink()
    outputs.unlink()
    return (np.concatenate(features), np.concatenate(scores),
            np.concatenate(labels).astype(np.uint8), np.array(cuts), fixtures, rejected)


def split(fixtures):
    match = {}
    for f in fixtures:
        match.setdefault(f["match"], set()).add(f["opponent_group"])
    groups = Counter(g for names in match.values() for g in names)
    rng = random.Random(CONFIG["split_seed"])
    unseen = {g for g in sorted(groups) if rng.random() < .2}
    # Duplicate perspectives of the same fight always go to the same split.
    for names in match.values():
        if names & unseen:
            unseen.update(names)
    roles = {}
    for m, names in sorted(match.items()):
        r = rng.random()
        roles[m] = ("opponent_test" if names & unseen else
                    "train" if r < .70 else "development" if r < .85 else "test")
    return roles


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    if (OUT / "manifest.json").exists() and (OUT / "native.npz").exists():
        print("Using the frozen native panel in", OUT, flush=True)
        return
    if hashlib.sha256(TEACHER.read_bytes()).hexdigest() != CONFIG["teacher_sha256"]:
        raise ValueError("Frozen teacher changed")
    fixtures, report = inventory()
    print("Inventory", report["alias_povs"], flush=True)
    x, scores, y, cuts, fixtures, rejected = extract(fixtures)
    roles = split(fixtures)
    for f in fixtures:
        f["split"] = roles[f["match"]]
    np.savez_compressed(OUT / "native.npz", x=x, scores=scores, y=y, cuts=cuts)
    report.update(rejected=dict(rejected), fixtures=fixtures,
                  splits=dict(Counter(roles.values())), total_heads=len(x))
    write_json(OUT / "manifest.json", report)
    print(report["splits"], "total heads", len(x), "rejected", rejected, flush=True)


if __name__ == "__main__":
    main()
