"""Fixed 7-CPS RL Sumo League v2: episode-normalized PPO, entropy regularization,
mild discounting, live-HUD opponent-RTT masking, fresh Adam warm-starts, and
self-mirror-free tournament ranking against plain W-tap, the frozen archive, and
the prior remote-005 champion."""
import argparse
import hashlib
import json
import os
import subprocess
import sys
import time
from pathlib import Path

from development_panel import make_panel
from league_tournament import digest, historical, pin_model, stats, tournament
from learner import CLOCKS, EVIDENCE, FAMILIES, INCUMBENT, ROOT, Worker, atomic, write_json
from train import configure, fit

OUT = EVIDENCE / "fixed-v2"
FIGHTS = 65536


def read(p):
    return json.loads(p.read_text())


def base_field_and_families():
    field = historical()
    families = {k: list(v) for k, v in FAMILIES.items()}
    prior_net = OUT / "baselines" / "remote-005.net"
    if prior_net.is_file():
        raw = prior_net.read_bytes()
        field["remote-005"] = dict(
            reference=str(prior_net.relative_to(ROOT)),
            sha256=hashlib.sha256(raw).hexdigest(),
        )
        families["prior remote champion"] = ["remote-005"]
    return field, families


def train_role(gen, role, cfg_spec):
    home = OUT / f"g{gen:03d}" / role
    if (home / "result.json").exists():
        return read(home / "result.json")
    home.mkdir(parents=True, exist_ok=True)
    if not (home / "config.json").exists():
        configure(
            home,
            cfg_spec["kind"],
            cfg_spec["role"],
            cfg_spec["seed"],
            cfg_spec["draw_seed"],
            cfg_spec["incumbent"],
            cfg_spec["counter"],
            cfg_spec["families"],
            scores=cfg_spec.get("scores", {}),
            warm=cfg_spec.get("warm"),
            threats=cfg_spec.get("threats"),
        )
    fit(home, FIGHTS // 64)
    if read(home / "progress.json")["duels"] != FIGHTS:
        raise ValueError(f"Incomplete training budget for {role}")
    ref = pin_model(home)
    result = dict(policy=ref, fights=FIGHTS, role=role, generation=gen)
    if cfg_spec["role"] == "exploiter":
        panel = make_panel(CLOCKS["dev"], 8)
        worker = Worker(current_runtime=True)
        try:
            for label, actor in (("challenger", ref["reference"]), ("wtap", "wtap")):
                fixture = home / f"{label}-512.json"
                if not fixture.exists():
                    plan = [
                        (actor, cfg_spec["incumbent"], clock, seat, a, b, x, y)
                        for _, _, seat, a, b, clock, x, y in panel
                    ]
                    rows = []
                    for offset in range(0, len(plan), 32):
                        rows.extend(worker.run(plan[offset : offset + 32], False))
                    scored = [[*cell[:5], row[0], row[1]] for cell, row in zip(panel, rows, strict=True)]
                    write_json(fixture, dict(games=scored, aggregate=stats(scored)), fixed=True)
                result[label] = read(fixture)["aggregate"]
            result.update(
                target=cfg_spec["incumbent_name"],
                confirmed=result["challenger"]["wins"] >= 308,
                gate_fights=512,
            )
        finally:
            worker.close()
    write_json(home / "result.json", result, fixed=True)
    return result


def run_parallel(gen, specs):
    procs = []
    for role, spec in specs.items():
        spec_path = OUT / f"g{gen:03d}" / role / "spec.json"
        spec_path.parent.mkdir(parents=True, exist_ok=True)
        if not (spec_path.parent / "result.json").exists():
            write_json(spec_path, spec)
            procs.append(
                subprocess.Popen(
                    [sys.executable, "-u", __file__, "fit", str(gen), role, str(spec_path)]
                )
            )
    codes = [p.wait() for p in procs]
    if any(codes):
        raise RuntimeError(f"Generation {gen} failed with exit codes {codes}")


def run_generation(gen):
    home = OUT / f"g{gen:03d}"
    home.mkdir(parents=True, exist_ok=True)
    if (home / "result.json").exists():
        print(f"g{gen:03d} already completed: {read(home / 'result.json')['winner']}", flush=True)
        return

    if gen == 1:
        field, families = base_field_and_families()
        incumbent_name = "remote-005" if "remote-005" in field else INCUMBENT
        incumbent_ref = field[incumbent_name]["reference"]
        ref_families = {k: [field[n]["reference"] for n in v] for k, v in families.items()}
        threats = [incumbent_ref, field[INCUMBENT]["reference"], field["sumo-seven-first"]["reference"]]
        specs = {
            "main-mlp": dict(
                kind="mlp",
                role="main",
                seed=71011,
                draw_seed=81011,
                incumbent=incumbent_ref,
                incumbent_name=incumbent_name,
                counter=threats[0],
                families=ref_families,
                threats=threats,
                warm=None,
            ),
            "main-gru": dict(
                kind="gru",
                role="main",
                seed=71514,
                draw_seed=81514,
                incumbent=incumbent_ref,
                incumbent_name=incumbent_name,
                counter=threats[0],
                families=ref_families,
                threats=threats,
                warm=None,
            ),
        }
        run_parallel(gen, specs)
        trained_roles = ("main-mlp", "main-gru")
    else:
        prev = read(OUT / f"g{gen-1:03d}" / "tournament.json")
        field = dict(prev["field"])
        families = {k: list(v) for k, v in prev["families"].items()}
        incumbent_name = prev["winner"]
        incumbent_ref = field[incumbent_name]["reference"]
        ref_families = {k: [field[n]["reference"] for n in v] for k, v in families.items()}
        # Warm-start from best previous fixed-v2 MLP and GRU (fresh Adam in train.py)
        warm = {}
        for kind in ("mlp", "gru"):
            cand = OUT / f"g{gen-1:03d}" / f"main-{kind}" / "trainer.pt"
            if cand.is_file():
                warm[kind] = str(cand.relative_to(ROOT))
        # Stage 1: train counters against g{gen-1} winner
        counter_specs = {
            "counter-mlp": dict(
                kind="mlp",
                role="exploiter",
                seed=71011 + gen * 3001,
                draw_seed=81011 + gen * 3001,
                incumbent=incumbent_ref,
                incumbent_name=incumbent_name,
                counter=incumbent_ref,
                families=ref_families,
                warm=warm.get("mlp"),
            ),
            "counter-gru": dict(
                kind="gru",
                role="exploiter",
                seed=71514 + gen * 3001,
                draw_seed=81514 + gen * 3001,
                incumbent=incumbent_ref,
                incumbent_name=incumbent_name,
                counter=incumbent_ref,
                families=ref_families,
                warm=warm.get("gru"),
            ),
        }
        run_parallel(gen, counter_specs)
        # Stage 2: train main-mlp and main-gru against winner + confirmed counters + weakest
        weak = min(
            (n for n in field if n != "wtap" and field[n]["sha256"] != field[incumbent_name]["sha256"]),
            key=lambda n: (prev["matrix"][incumbent_name][n]["wins"], prev["matrix"][incumbent_name][n]["score"], n),
        )
        threats = [field[weak]["reference"]]
        confirmed = []
        for side in ("counter-mlp", "counter-gru"):
            cr = read(home / side / "result.json")
            if cr.get("confirmed"):
                threats.append(cr["policy"]["reference"])
                confirmed.append(cr["policy"]["reference"])
        main_families = dict(ref_families)
        if confirmed:
            main_families["current counters"] = confirmed
        main_specs = {
            "main-mlp": dict(
                kind="mlp",
                role="main",
                seed=72011 + gen * 3001,
                draw_seed=82011 + gen * 3001,
                incumbent=incumbent_ref,
                incumbent_name=incumbent_name,
                counter=threats[0],
                families=main_families,
                scores=prev["ranking"][0]["family"],
                threats=threats,
                warm=warm.get("mlp"),
            ),
            "main-gru": dict(
                kind="gru",
                role="main",
                seed=72514 + gen * 3001,
                draw_seed=82514 + gen * 3001,
                incumbent=incumbent_ref,
                incumbent_name=incumbent_name,
                counter=threats[0],
                families=main_families,
                scores=prev["ranking"][0]["family"],
                threats=threats,
                warm=warm.get("gru"),
            ),
        }
        run_parallel(gen, main_specs)
        trained_roles = ("counter-mlp", "counter-gru", "main-mlp", "main-gru")

    for role in trained_roles:
        ename = f"fixed-v2-g{gen:03d}-{role}"
        field[ename] = read(home / role / "result.json")["policy"]
        fam = "fixed-v2 counters" if role.startswith("counter") else "fixed-v2 generalists"
        grp = families.setdefault(fam, [])
        grp.append(ename)
        if len(grp) > 6:
            del grp[:-6]

    if not (home / "tournament.json").exists():
        worker = Worker(current_runtime=True, threads=int(os.environ.get("SUMO_TOURNAMENT_THREADS", "6")))
        try:
            tournament(worker, field, families, incumbent_name, home / "tournament.json")
        finally:
            worker.close()
    t = read(home / "tournament.json")
    winner = t["winner"]
    summary = dict(
        generation=gen,
        winner=winner,
        policy=field[winner],
        incumbent=incumbent_name,
        merit=t["ranking"][0]["merit"],
        field_mean=t["ranking"][0]["field_mean"],
        worst_family=t["ranking"][0]["worst_family"],
        worst_score=t["ranking"][0]["worst"],
        top5=[
            dict(
                name=r["name"],
                merit=round(r["merit"], 4),
                mean=round(r["field_mean"], 4),
                worst=round(r["worst"], 4),
                vs_wtap=round(r["vs_wtap"]["score"], 4),
                vs_remote005=round(t["matrix"][r["name"]]["remote-005"]["score"], 4) if "remote-005" in field else None,
            )
            for r in t["ranking"][:5]
        ],
        wtap_rank=next(
            (
                dict(rank=idx + 1, merit=round(r["merit"], 4), mean=round(r["field_mean"], 4), worst=round(r["worst"], 4))
                for idx, r in enumerate(t["ranking"])
                if r["name"] == "wtap"
            ),
            None,
        ),
    )
    write_json(home / "result.json", summary, fixed=True)
    print(f"=== fixed-v2 g{gen:03d} COMPLETE ===", json.dumps(summary, indent=2), flush=True)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["run", "fit"])
    ap.add_argument("gen", type=int, nargs="?", default=2)
    ap.add_argument("role", nargs="?")
    ap.add_argument("spec", type=Path, nargs="?")
    a = ap.parse_args()
    if a.mode == "fit":
        train_role(a.gen, a.role, read(a.spec))
    else:
        for g in range(1, a.gen + 1):
            run_generation(g)
