"""Run population self-play and freeze the champion selected by duel outcomes."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

from setup import SRC, registered_sim, jdk
from transfer import propose


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def train(protocol_path, work, output):
    p = json.loads(protocol_path.read_text())
    classes = registered_sim(p)
    for name, expected in p["weight_hashes"].items():
        if digest(SRC / name) != expected:
            raise ValueError(f"Training weight changed: {name}")
    work = work.resolve()
    output.mkdir(parents=True, exist_ok=True)
    parents = [SRC / name for name in p["parent_files"]]
    field = [SRC / name for name in p["field_files"]]
    propose(work, parents, field, p["seeds"], p["generations"], p["shortlist_seed"], "flat26", classes)
    names = sorted(work.glob("proposal-*.net"))
    if p.get("rank_all_checkpoints"):
        names = sorted(work.glob("island-*/gen-*.net")) + parents
    catalog = work / "shortlist.txt"
    catalog.write_text("".join(f"{path}\n" for path in names))
    java = [jdk() / "java", "-Dsword.transport=lockstep", "-cp", classes]
    if p.get("rank_all_checkpoints"):
        java.insert(1, "-Dsword.equalLatency=true")
    scoring = subprocess.check_output([*java, "sword.Cup", catalog, work / "field.txt",
                                      str(p["selection_seed"]), str(p["selection_openings"])], text=True)
    scores = [line.split("\t") for line in scoring.splitlines()]
    winner = Path(max(scores, key=lambda row: float(row[0]) + float(row[1]))[3])
    chosen = output / "fighter.net"
    shutil.copyfile(winner, chosen)
    def label(path):
        return str(path.relative_to(work)) if path.is_relative_to(work) else str(path.relative_to(SRC))
    selection = {"sha256": digest(chosen), "selected": label(winner),
                 "selection": [{"file": label(Path(row[3])), "mean": float(row[0]),
                                "worst": float(row[1]), "duels": int(row[2])} for row in scores],
                 "training_duels": sum(int(line.split("\t")[2]) for log in work.glob("island-*/training.tsv")
                                       for line in log.read_text().splitlines()[1:])}
    (output / "selection.json").write_text(json.dumps(selection, indent=2) + "\n")
    shutil.copyfile(work / "proposals.json", output / "proposals.json")
    return selection


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("protocol", type=Path)
    parser.add_argument("new_work_directory", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    print(json.dumps(train(args.protocol, args.new_work_directory, args.output), indent=2))
