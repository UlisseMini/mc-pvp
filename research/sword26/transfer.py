"""Breed a population in simulation and select a policy using native duel outcomes."""
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import random
import shutil
import subprocess
import sys

from setup import SRC, compile_sim, jdk


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def propose(dest, parents, field, seeds, generations, selection_seed, engine="historical"):
    dest.mkdir(parents=True, exist_ok=False)
    parents = [p.resolve() for p in parents]
    field = [p.resolve() for p in field]
    classes = compile_sim()
    rivals = dest / "field.txt"
    rivals.write_text("".join(str(p) + "\n" for p in field))
    shortlist = []
    for i, seed in enumerate(seeds):
        island = dest / f"island-{i}"
        starting = parents.copy()
        random.Random(seed).shuffle(starting)
        starter_file = dest / f"parents-{i}.txt"
        starter_file.write_text("".join(str(p) + "\n" for p in starting))
        java = [jdk() / "java", "-Xmx1g", "-cp", classes]
        if engine == "flat26":
            java.append("-Dsword.transport=lockstep")
        elif i % 2:
            java.append("-Dsword.transport=measured")
        subprocess.run([*java, "-Dsword.parentFile=" + str(starter_file), "sword.Refine",
                        island, str(generations), str(seed), *field], check=True)
        catalog = island / "candidates.txt"
        catalog.write_text("".join(str(p) + "\n" for p in sorted(island.glob("gen-*.net"))))
        scores = island / "selection.tsv"
        with scores.open("w") as out:
            subprocess.run([*java, "sword.Cup", catalog, rivals, str(selection_seed)],
                           stdout=out, check=True)
        rows = [line.split("\t") for line in scores.read_text().splitlines()]
        best = max(rows, key=lambda row: (float(row[0]) + float(row[1])) / 2)
        proposal = dest / f"proposal-{i}.net"
        shutil.copyfile(best[3], proposal)
        shortlist.append({"file": proposal.name, "sha256": digest(proposal),
                          "source": str(Path(best[3]).relative_to(dest)),
                          "model_mean": float(best[0]), "model_worst": float(best[1])})
    manifest = {"engine": engine, "parents": {str(p): digest(p) for p in parents},
                "field": {str(p): digest(p) for p in field}, "proposals": shortlist}
    (dest / "proposals.json").write_text(json.dumps(manifest, indent=2) + "\n")


def select(dest, run, opponents, seed, pairs, rtts, speed):
    manifest = json.loads((dest / "proposals.json").read_text())
    proposals = [dest / row["file"] for row in manifest["proposals"]]
    meta = json.loads((run / "metadata.json").read_text())
    if (meta["seed"], meta["pairs"], meta["rtts"], meta["speed"]) != (seed, pairs, rtts, speed):
        raise ValueError("The native results belong to a different training schedule")
    hashes = [meta["weights_sha256"], *[row["sha256"] for row in meta["extra_weights"]]]
    if hashes != [row["sha256"] for row in manifest["proposals"]] or hashes != [digest(p) for p in proposals]:
        raise ValueError("Native weights disagree with the simulation shortlist")
    rivals = {str(p.resolve()): digest(p) for p in opponents}
    if rivals != meta["opponent_hashes"] or set(meta["opponents"]) != set(rivals):
        raise ValueError("The native opponents differ from the training panel")
    arms = ["learned" if i == 0 else f"learned-{i}" for i in range(len(proposals))]
    payoffs = {arm: defaultdict(list) for arm in arms}
    expected = {(arm, rival, rtt, pair, seat) for arm in arms for rival in rivals
                for rtt in rtts for pair in range(pairs) for seat in (0, 1)}
    for row in json.loads((run / "results.json").read_text()):
        arm = row["policy"]
        if arm not in payoffs:
            continue
        slot = (arm, row["rival"], row["rtt"], row["pair"], row["seat"])
        if slot not in expected:
            raise ValueError(f"Duplicate or unexpected native game {slot}")
        expected.remove(slot)
        hp, enemy = row["hp"], row["opponent_hp"]
        outcome = int(enemy == 0) - int(hp == 0)
        payoffs[arm][row["rival"]].append(outcome + (hp - enemy) / 40)
    if expected:
        raise ValueError(f"The training panel is missing {len(expected)} games")
    scores = []
    for arm, proposal in zip(arms, proposals):
        means = {Path(k).name: sum(v) / len(v) for k, v in payoffs[arm].items()}
        if len(means) != len(rivals):
            raise ValueError("Give each opponent a unique file name")
        total = .5 * (sum(means.values()) / len(means) + min(means.values()))
        scores.append({"file": proposal.name, "opponent_means": means, "score": total})
    winner = max(scores, key=lambda row: row["score"])
    playing = dest / "selected.net"
    shutil.copyfile(dest / winner["file"], playing)
    record = {"selected": winner["file"], "sha256": digest(playing), "scores": scores,
              "source_run": run.name, "seed": seed}
    (dest / "selection.json").write_text(json.dumps(record, indent=2) + "\n")
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("propose")
    p.add_argument("dest", type=Path)
    p.add_argument("--engine", choices=["flat26","historical"], default="flat26")
    p.add_argument("--parents", nargs="+", type=Path, required=True)
    p.add_argument("--field", nargs="+", type=Path, required=True)
    p.add_argument("--seeds", nargs="+", type=int, required=True)
    p.add_argument("--generations", type=int, default=112)
    p.add_argument("--selection-seed", type=int, required=True)
    n = sub.add_parser("native")
    n.add_argument("dest", type=Path)
    n.add_argument("--opponents", nargs="+", type=Path, required=True)
    n.add_argument("--seed", type=int, required=True)
    n.add_argument("--pairs", type=int, default=1)
    n.add_argument("--rtts", nargs="+", type=int, default=[0, 100])
    n.add_argument("--speed", type=int, default=3)
    n.add_argument("--completed-run", type=Path)
    args = parser.parse_args()
    dest = args.dest.resolve()
    if args.command == "propose":
        propose(dest, args.parents, args.field, args.seeds, args.generations, args.selection_seed, args.engine)
        return
    run = args.completed_run
    if run is None:
        files = [dest / row["file"] for row in json.loads((dest / "proposals.json").read_text())["proposals"]]
        cmd = [sys.executable, SRC / "native.py", "--weights", files[0], "--extra-weights", *files[1:],
               "--opponents", *args.opponents, "--seed", str(args.seed), "--pairs", str(args.pairs),
               "--rtts", *map(str, args.rtts), "--speed", str(args.speed)]
        with subprocess.Popen(cmd, stdout=subprocess.PIPE, text=True) as proc:
            for line in proc.stdout:
                print(line, end="", flush=True)
                if line.startswith("Round recordings: "):
                    run = Path(line.split(": ", 1)[1].strip())
            if proc.wait() or run is None:
                raise SystemExit("The native development panel did not finish")
    print(json.dumps(select(dest, run, args.opponents, args.seed, args.pairs, args.rtts, args.speed), indent=2))


if __name__ == "__main__":
    main()
