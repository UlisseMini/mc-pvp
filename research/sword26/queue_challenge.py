"""An independently seeded one-core league challenge using the ordinary server packet queue."""
import csv
import json
import random
import shutil
import subprocess
import time
from collections import defaultdict
from statistics import mean

from season import EVIDENCE, WORK, SRC, checkpoints, hash_policy, lines, save, unique, write_fighter
from setup import compile_sim, jdk


def run():
    plan = json.loads((EVIDENCE / "perfect-aim/queued-challenge.json").read_text())
    output = EVIDENCE / "queue-challenge-028"
    work = WORK / output.name
    for folder in (work, output):
        folder.mkdir(exist_ok=True)
    if (output / "finalists.json").exists():
        return
    jar = compile_sim()
    main = SRC / plan["champion"]
    population = json.loads((SRC / plan["population"]).read_text())["opponent_mix"]
    archive = unique([main, *(SRC / p for p in population)])
    rng = random.Random(plan["seed"])
    weights = list(population.values())

    def stratify(count):
        thresholds = [(i + rng.random()) / count for i in range(count)]
        selected = []
        cumulative = 0.
        for p, weight in zip(population, weights, strict=True):
            cumulative += weight
            while len(selected) < count and thresholds[len(selected)] <= cumulative + 1e-12:
                selected.append(SRC / p)
        assert len(selected) == count
        return selected

    general_field = [main] * 5 + stratify(48)
    response_field = [main] * 22 + stratify(5)
    fresh_field = [main] * 12 + stratify(12)
    screen_field = unique([main, *stratify(8)])
    # Fill any repeated quantiles from the seeded archive for eight distinct opponents.
    random_order = list(archive)
    rng.shuffle(random_order)
    screen_field = unique([*screen_field, *random_order])[:9]
    parents = [p for p in random_order if p.read_bytes()[:4] == b"SWT1"]
    parent_file = lines(work / "parents.txt", parents)
    common = ["nice", "-n", "18", jdk() / "java", "-Xmx384m", "-XX:ActiveProcessorCount=1",
              "-XX:+UseSerialGC", "-Dsword.transport=lockstep", "-Dsword.aimProfile=perfect",
              "-Dsword.serverPacketBatch=true", "-cp", jar]

    def launch(args, filepath):
        tmp = filepath.with_suffix(".running")
        with tmp.open("w") as stream:
            subprocess.run([*common, *args], stdout=stream, check=True)
        tmp.replace(filepath)

    versions = []
    for n, (role, field) in enumerate((("general", general_field), ("response", response_field), ("fresh", fresh_field))):
        generations = plan["training_generations"][role]
        folder = work / role
        if not (folder / f"gen-{generations-1:04d}.net").exists():
            if folder.exists():
                folder.rename(work / f"{role}-partial-{time.time_ns()}")
            args = (["sword.League", folder, str(generations), str(plan["seed"]+n), "1"] if role == "fresh"
                    else ["-Dsword.parentFile=" + str(parent_file), "sword.Refine", folder, str(generations), str(plan["seed"]+n)])
            launch(["-Dsword.phaseClock=on", *args, *field], work / (role + ".log"))
        versions.extend(checkpoints(folder, 20))
        print(role, generations, flush=True)
    incumbent_digest = hash_policy(main)
    versions = [p for p in unique(versions) if hash_policy(p) != incumbent_digest]

    def matrix(name, actors, opponents, seed, pairs, clock):
        target = work / (name + ".tsv")
        if not target.exists():
            launch(["-Dsword.phaseClock=" + clock, "sword.CrossPlay", lines(work / (name+"-actors.txt"), actors),
                    lines(work / (name+"-rivals.txt"), opponents), str(seed), str(pairs), "1"], target)
        values = defaultdict(list)
        with target.open() as f:
            for row in csv.DictReader(f, delimiter="\t"):
                points = (int(row["wins"]) + .5*int(row["draws"])) / (pairs*2)
                assert sum(int(row[k]) for k in ("wins","draws","losses")) == pairs*2
                values[row["actor"],row["rival"]].append(points)
        assert len(values) == len(actors)*len(opponents) and all(len(c)==2 for c in values.values())
        return values

    def direct(data, p):
        return mean(data[str(p),str(main)])

    def breadth(data, p, field):
        points = sorted(mean(data[str(p), str(q)]) for q in field)
        tail = points[:(len(points)+3)//4]
        return .75*mean(points) + .25*mean(tail)

    screen = matrix("selection", [*versions,main,"wtap"], screen_field, plan["seed"]+20,48,"on")
    nominations = unique([*sorted(versions,key=lambda p:direct(screen,p),reverse=True)[:4],
                          *sorted(versions,key=lambda p:breadth(screen,p,screen_field),reverse=True)[:4]])
    field = [*nominations,main,"wtap"]
    results = []
    for clock in ("off","on"):
        name = "confirmation-"+clock
        results.append(matrix(name, field, archive, plan["seed"]+40,128,clock))
        shutil.copyfile(work/(name+".tsv"), output/(name+".tsv"))
    merged = {k:results[0][k]+results[1][k] for k in results[0]}
    best = max(nominations,key=lambda p:breadth(merged,p,archive))
    counter = max(nominations,key=lambda p:direct(merged,p))
    finalists = unique([best,counter])
    rows = []
    for p in [*finalists,main,"wtap"]:
        arc = write_fighter(p) if p!="wtap" else None
        rows.append(dict(fighter=str(arc.relative_to(SRC)) if arc else "wtap", direct=direct(merged,p),
                         breadth=breadth(merged,p,archive), fixed_direct=direct(results[0],p),
                         queued_direct=direct(results[1],p)))
    save(output/"finalists.json",dict(seed=plan["seed"],champion=plan["champion"],proposed=[str(write_fighter(p).relative_to(SRC)) for p in finalists],
                                     stages=plan["training_generations"],scores=rows))
    print([(r["fighter"][-20:],r["direct"],r["breadth"]) for r in rows],flush=True)


if __name__ == "__main__":
    run()
