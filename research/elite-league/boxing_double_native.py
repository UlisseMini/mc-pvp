"""Freeze and test a fixed-click boxing specialist and the mirrored W-tap control."""

import argparse
import hashlib
import itertools
import json
import random
from pathlib import Path

from native_trials import run_batch
from run import ROOT


def install(name, weights, cps, gate):
    template = (ROOT / "policies/research-wtap20.properties").read_text().splitlines()
    settings = dict(row.split("=", 1) for row in template if row and not row.startswith("#"))
    settings.update(
        click_cps=str(cps),
        duel_network=weights,
        duel_network_landmark=str(gate),
        description=f"{cps} CPS MMC boxing movement experiment, fixed click pacing.",
    )
    text = "".join(f"{key}={value}\n" for key, value in settings.items())
    path = ROOT / "policies" / f"{name}.properties"
    if path.exists():
        if path.read_text() != text:
            raise ValueError(f"Frozen policy name already in use: {name}")
    else:
        with path.open("x") as out:
            out.write(text)


def plan(name, cps, repeats, rtts=(0, 80)):
    control = "research-wtap20"
    rows = []
    for mode, rep, rtt, side, candidate in itertools.product(
        ("regulation", "maintenance"), range(repeats), rtts, "ab", (False, True)
    ):
        arm = (name if mode == "regulation" else f"{name}-k3") if candidate else control
        opponent = control if mode == "regulation" else "combo-wake-ref3"
        a, b = (arm, opponent) if side == "a" else (opponent, arm)
        req = f"{name}-{mode}-{rep}-{rtt}-{side}-{'candidate' if candidate else 'mirror'}"
        verb = "match" if mode == "regulation" else "scenario"
        duration = "" if mode == "regulation" else " 20000"
        cmd = (
            f"lab {verb} {a} {b} {rtt} {rtt}{duration} {req} "
            f"preset=mmc cps={cps} reach=3 tapdelay=0 aimdelay=0 aimnoise=0 aimrate=0 aiminput=mouse"
        )
        rows.append((req, dict(
            mode="boxing", match_format="first-to-100" if mode == "regulation" else "maintenance",
            arm=arm, opponent=opponent, profile="mmc", rtt=rtt, rep=rep, side=side, cps=cps,
            aim_input="mouse", command=cmd
        )))
    # Interleave trials and start both stages while the lab has space.
    random.Random(774130 + cps).shuffle(rows)
    return dict(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("weights", type=Path)
    parser.add_argument("out", type=Path)
    parser.add_argument("--name", required=True)
    parser.add_argument("--cps", type=int, choices=(10, 20), default=10)
    parser.add_argument("--repeats", type=int, default=2)
    parser.add_argument("--rtts", type=int, nargs="+", default=(0, 80))
    args = parser.parse_args()
    if len(set(args.rtts)) != len(args.rtts) or any(rtt < 0 for rtt in args.rtts):
        parser.error("Provide distinct, nonnegative RTTs")
    text = args.weights.read_text().strip()
    args.out.parent.mkdir(parents=True, exist_ok=True)
    freeze = args.out.with_suffix(".frozen.json")
    record = dict(weight_sha256=hashlib.sha256(text.encode()).hexdigest(), name=args.name, cps=args.cps)
    if freeze.exists():
        if json.loads(freeze.read_text()) != record:
            raise ValueError("Native cohort was frozen with different weights or settings")
    else:
        with freeze.open("x") as out:
            out.write(json.dumps(record) + "\n")
    install(args.name, text, args.cps, 0)
    install(f"{args.name}-k3", text, args.cps, 3)
    run_batch(plan(args.name, args.cps, args.repeats, args.rtts), args.out)


if __name__ == "__main__":
    main()
