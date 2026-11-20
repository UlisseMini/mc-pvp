"""Audit the inputs, first launches, and unanswered follow-ups in native opening drills."""

import argparse
from collections import defaultdict
import gzip
import itertools
import json
import math
from pathlib import Path
from statistics import mean

from run import ROOT


def events(path, kinds):
    opener = gzip.open if path.suffix == ".gz" else open
    fragments = tuple(f'"{kind}"' for kind in kinds)
    with opener(path, "rt") as stream:
        for line in stream:
            if any(part in line for part in fragments):
                row = json.loads(line)
                if row["type"] in kinds:
                    yield row


def distance_to_hitbox(click):
    own, target = click["own"], click["target"]
    if not target:
        return None
    # An eye-to-expanded-box distance greater than reach rules out a hit
    # regardless of aim. A shorter distance alone does not prove ray contact.
    eye = (own["posX"], own["posY"] + 1.620000004768372, own["posZ"])
    box = target["box"]
    return math.sqrt(sum(max(0, box[i] - .10000000149 - p, p - box[i + 3] - .10000000149) ** 2
                         for i, p in enumerate(eye)))


def opening_pov(path, mid, start, finish, cps, fresh_incoming):
    kinds = {"capture_end", "click", "engine_attack", "fresh_hurt_status", "science_click", "science_move", "velocity_packet"}
    presses, cues, clicks = [], [], []
    launch, last_airborne, ground, impulse, footer = None, None, None, None, None
    recoil_step = None
    early_brakes = []
    overlapping_impulse = False
    accepted = fresh_incoming[0]["time"] if fresh_incoming else float("inf")
    next_hit = fresh_incoming[1]["time"] if len(fresh_incoming) > 1 else float("inf")
    for row in events(path, kinds):
        kind = row["type"]
        if kind == "capture_end":
            footer = row
        elif row.get("match") == mid and start <= row["time"] <= finish:
            t = row["time"] - start
            if kind == "click":
                cap = (1_000_000_000 + cps - 1) // cps
                if row["click_cps"] != cps or row["minimum_click_interval_ns"] != cap:
                    raise ValueError(f"{mid}: configured CPS mismatch")
                presses.append((t, row["monotonic_ns"]))
            elif kind == "fresh_hurt_status":
                cues.append(t)
            elif kind == "science_click" and t <= 2200:
                clicks.append(dict(at=t, selected=row["selected_target"], cooldown_clear=row["miss_cooldown_before"] <= 0,
                                   nearest=distance_to_hitbox(row), grounded=row["own"]["ground"]))
            elif kind == "velocity_packet" and row["own_entity"] and accepted <= t < next_hit:
                if impulse is None:
                    impulse = dict(at=t, horizontal=math.hypot(row["vx"], row["vz"]), vertical=row["vy"])
                elif recoil_step is None:
                    overlapping_impulse = True
            elif kind == "engine_attack" and impulse and recoil_step is None and t < next_hit:
                before = math.hypot(row["vx_before"], row["vz_before"])
                after = math.hypot(row["vx_after"], row["vz_after"])
                if row["sprint_before"] and before > 0 and math.isclose(after, before * .6, abs_tol=1e-9):
                    early_brakes.append(dict(at=t, horizontal_before=before, horizontal_after=after))
            elif kind == "science_move" and impulse and impulse["at"] <= t < next_hit and ground is None:
                body = row["after"]
                if recoil_step is None:
                    before = row["before"]
                    speed = math.hypot(before["motionX"], before["motionZ"])
                    recoil_step = dict(at=t, horizontal_before=speed, early_brakes=early_brakes, classification="unknown")
                    if impulse["horizontal"] and not overlapping_impulse:
                        scale = speed / impulse["horizontal"]
                        if not early_brakes and math.isclose(scale, 1, abs_tol=1e-9):
                            recoil_step["classification"] = "full"
                        elif len(early_brakes) == 1 and math.isclose(scale, .6, abs_tol=1e-9):
                            recoil_step["classification"] = "braked"
                    recoil_step["horizontal_displacement"] = math.hypot(body["posX"] - before["posX"],
                                                                        body["posZ"] - before["posZ"])
                if not body["ground"]:
                    if launch is None:
                        launch = t
                    last_airborne = t
                elif launch is not None and body["ground"]:
                    ground = t
    if not footer or not footer["writer_complete"] or any(footer[k] for k in ("dropped", "size_capped", "continues")):
        raise ValueError(f"{mid}: incomplete client archive")
    if not presses:
        raise ValueError(f"{mid}: missing click trace")
    cap_ns = (1_000_000_000 + cps - 1) // cps
    if any(b[1] - a[1] < cap_ns for a, b in itertools.pairwise(presses)):
        raise ValueError(f"{mid}: physical click rate exceeded cap")
    return dict(first_press=presses[0][0], first_hurt_cue=cues[0] if cues else None, presses=len(presses),
                impulse=impulse, first_recoil_step=recoil_step, airborne_at=launch, last_airborne_at=last_airborne,
                landed_at=ground, clicks=clicks)


def audit(mid, setup):
    game = json.loads((ROOT / "runtime/results" / f"{mid}.json").read_text())
    side = setup["side"]
    other = "b" if side == "a" else "a"
    if (game["reason"], game["mode"], game["practice_preset"]) != ("scenario_completed", "boxing", "mmc"):
        raise ValueError(f"{mid}: unexpected scenario")
    if game["combat_profile"]["knockback_model"] != "mmc_pre_attack_sprint_v2" or game.get("ping_changes"):
        raise ValueError(f"{mid}: unexpected combat profile or latency change")
    start, finish = game["start"], game["finish"]
    hits = [dict(time=r["time"]-start, tick=r["server_tick"], attacker=r["attacker"], fresh=not r["damage_upgrade"])
            for r in events(ROOT / "runtime/replays" / f"{mid}.jsonl", {"hit"}) if not r["rod"]]
    accepted, fresh, pov = [], [], []
    for seat, policy in ((side, setup["arm"]), (other, setup["opponent"])):
        params = game[f"parameters_{seat}"]
        expected = setup.get("opening_hurt_delay_ms") if seat == side else None
        actual = int(params["opening_hurt_delay_ms"]) if "opening_hurt_delay_ms" in params else None
        if (game[f"policy_{seat}"], game[f"initial_ping_{seat}"], game[f"attack_reach_{seat}"],
                game[f"reaction_ms_{seat}"], game[f"click_cps_{seat}"], params.get("aim_input"), actual) != (
                policy, setup["rtt"], 3, 0, setup["cps"], "mouse", expected):
            raise ValueError(f"{mid}/{seat}: fixture settings differ from plan")
        if any(float(params[k]) != 0 for k in ("aim_delay_ms", "aim_turn_rate_dps", "aim_noise_deg")):
            raise ValueError(f"{mid}/{seat}: mismatched aim settings")
        if (params["kind"], params["click_mode"]) != ("wtap", "fixed"):
            raise ValueError(f"{mid}/{seat}: expected W-tap movement and fixed click pacing")
        if int(params.get("click_start_ms", 0)) != (setup["first_click_delay_ms"] if seat == side else 0):
            raise ValueError(f"{mid}/{seat}: mismatched static click delay")
        dealt = [h for h in hits if h["attacker"] == game[seat]]
        if len(dealt) != game[f"hits_{seat}"]:
            raise ValueError(f"{mid}/{seat}: accepted-hit trace does not match score")
        accepted.append(len(dealt))
        fresh.append([h for h in dealt if h["fresh"]])
        incoming = [h for h in hits if h["attacker"] != game[seat] and h["fresh"]]
        path, = (ROOT / "runtime/pov" / mid).glob(f"{seat}-client-*-events.jsonl.gz")
        samples = opening_pov(path, mid, start, finish, setup["cps"], incoming)
        if expected is not None:
            cue = samples["first_hurt_cue"]
            earliest = min(1100, cue + expected) if cue is not None else 1100
            if samples["first_press"] < earliest:
                raise ValueError(f"{mid}/{seat}: click issued before the cue gate opened")
            samples["first_press_after_gate_ms"] = samples["first_press"] - earliest
        pov.append(samples)

    first_own, first_their = (stream[0] if stream else None for stream in fresh)
    valid = bool(first_own and first_their and first_own["tick"] > first_their["tick"]
                 and first_own["time"] - first_their["time"] <= 275)
    follows, last = 0, first_own
    first_return = None
    if valid:
        suffix = hits[hits.index(first_own) + 1:]
        first_return = next((h for h in suffix if h["attacker"] == game[other]), None)
        for tick, attacks in itertools.groupby(suffix, lambda h: h["tick"]):
            group = list(attacks)
            if (tick == first_own["tick"] or group[0]["time"] - last["time"] > 1000
                    or any(h["attacker"] == game[other] for h in group)):
                break
            for attack in group:
                if attack["fresh"]:
                    follows += 1
                    last = attack
    our_land, their_land = [p["landed_at"] for p in pov]
    their_airborne = pov[1]["last_airborne_at"]
    landed_first = bool(valid and our_land is not None and (
        (their_land is not None and our_land < their_land)
        or (their_airborne is not None and our_land < their_airborne)))
    return dict(id=mid, arm=setup["arm"], side=side, cps=setup["cps"], rtt=setup["rtt"], score=accepted,
                fresh=[len(s) for s in fresh], first=[first_own, first_their], first_return=first_return,
                valid_counter=valid, follow_ups=follows, landed_first=landed_first, pov=pov)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("out", type=Path)
    args = parser.parse_args()
    data = json.loads(args.manifest.read_text())
    if data["matches"].keys() != data["plan"].keys():
        raise ValueError("Native cohort is incomplete")
    rows = [audit(mid, data["plan"][req]) for req, mid in data["matches"].items()]
    args.out.write_text(json.dumps(rows, indent=2) + "\n")
    groups = defaultdict(list)
    for row in rows:
        groups[row["cps"], row["rtt"], row["arm"]].append(row)
    for (cps, rtt, arm), group in sorted(groups.items()):
        margins = [r["score"][0] - r["score"][1] for r in group]
        print(cps, rtt, arm, "margins", margins, "mean", mean(margins), "counter",
              sum(r["valid_counter"] for r in group), "land_first", sum(r["landed_first"] for r in group),
              "double", sum(r["follow_ups"] >= 1 for r in group), "combo", sum(r["follow_ups"] >= 2 for r in group),
              "matches", [r["id"] for r in group])


if __name__ == "__main__":
    main()
