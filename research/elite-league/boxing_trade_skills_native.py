"""Check isolated delayed openings and impact jumps on the main MMC boxing lab."""

import argparse
import itertools
import json
import random
from pathlib import Path
from zipfile import ZipFile

from native_trials import run_batch
from run import ROOT


BASELINE = "research-wtap20"
JUMP = "boxing-study-jump"
OPENINGS = {10: ("boxing-study-open650", 650), 20: ("boxing-study-open500", 500)}
CUE_ARM = "boxing-study-hurt150"
CUE_DELAY_MS = 150


def install(name, overrides):
    source = ROOT / "policies" / f"{BASELINE}.properties"
    settings = dict(line.split("=", 1) for line in source.read_text().splitlines() if line and not line.startswith("#"))
    settings.update(overrides)
    text = "".join(f"{key}={value}\n" for key, value in settings.items())
    dest = ROOT / "policies" / f"{name}.properties"
    if dest.exists():
        if dest.read_text() != text:
            raise ValueError(f"Different diagnostic policy exists: {name}")
    else:
        with dest.open("x") as stream:
            stream.write(text)


def plan(rates, repeats):
    fixtures = []
    for cps in rates:
        opening, wait = OPENINGS[cps]
        for arm, rtt, seat, repeat in itertools.product((BASELINE, JUMP, opening), (0, 80), "ab", range(repeats)):
            a, b = (arm, BASELINE) if seat == "a" else (BASELINE, arm)
            req = f"boxing-skill-{cps}-{arm}-{rtt}-{seat}-{repeat}"
            command = (
                f"lab scenario {a} {b} {rtt} {rtt} 12000 {req} "
                f"preset=mmc cps={cps} reach=3 tapdelay=0 aimdelay=0 aimnoise=0 aimrate=0 aiminput=mouse"
            )
            fixtures.append((req, dict(
                mode="boxing", profile="mmc", arm=arm, opponent=BASELINE, rtt=rtt, cps=cps,
                side=seat, rep=repeat, duration_ms=12000, aim_input="mouse",
                first_click_delay_ms=wait if arm == opening else 0, command=command,
            )))
    random.Random(387251).shuffle(fixtures)
    return dict(fixtures)


def hurt_plan(repeats):
    fixtures = []
    for arm, rtt, seat, repeat in itertools.product((BASELINE, CUE_ARM), (0, 20, 80), "ab", range(repeats)):
        a, b = (arm, BASELINE) if seat == "a" else (BASELINE, arm)
        req = f"boxing-hurt150-{arm}-{rtt}-{seat}-{repeat}"
        command = (
            f"lab scenario {a} {b} {rtt} {rtt} 12000 {req} "
            "preset=mmc cps=10 reach=3 tapdelay=0 aimdelay=0 aimnoise=0 aimrate=0 aiminput=mouse"
        )
        fixtures.append((req, dict(
            mode="boxing", profile="mmc", arm=arm, opponent=BASELINE, rtt=rtt, cps=10,
            side=seat, rep=repeat, duration_ms=12000, aim_input="mouse", first_click_delay_ms=0,
            opening_hurt_delay_ms=CUE_DELAY_MS if arm == CUE_ARM else None, command=command,
        )))
    random.Random(324557).shuffle(fixtures)
    return dict(fixtures)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("out", type=Path)
    parser.add_argument("--rates", nargs="+", type=int, choices=(10, 20), default=[10, 20])
    parser.add_argument("--repeats", type=int, default=2)
    parser.add_argument("--hurt-cue", action="store_true", help="Compare a 150 ms local hurt-cue opening to W-tap at 10 CPS")
    parser.add_argument("--freeze-only", action="store_true", help="Save the fixture plan without installing or playing")
    args = parser.parse_args()
    args.out.parent.mkdir(parents=True, exist_ok=True)
    fixtures = hurt_plan(args.repeats) if args.hurt_cue else plan(args.rates, args.repeats)
    frozen = args.out.with_suffix(".plan.json")
    if frozen.exists():
        if json.loads(frozen.read_text()) != fixtures:
            raise ValueError("Diagnostic manifest exists with a different schedule")
    else:
        with frozen.open("x") as stream:
            json.dump(fixtures, stream, indent=2)
    if args.freeze_only:
        return
    if args.hurt_cue:
        with ZipFile(ROOT / "runtime/bots/0/mods/practice-bridge.jar") as bridge:
            if b"opening_hurt_delay_ms" not in bridge.read("lab/Client.class"):
                raise RuntimeError("Main lab needs a bridge with the local hurt-cue opening diagnostic")
        install(CUE_ARM, dict(opening_hurt_delay_ms=str(CUE_DELAY_MS),
                             description="W-tap diagnostic with first clicks delayed after the local hurt cue."))
    else:
        install(JUMP, dict(jump_reset="true", description="Boxing diagnostic with W-tap and impact jump."))
        for cps in args.rates:
            name, wait = OPENINGS[cps]
            install(name, dict(click_start_ms=str(wait), description="Boxing diagnostic with delayed opening clicks."))
    run_batch(fixtures, args.out)


if __name__ == "__main__":
    main()
