"""Audit native boxing damage-upgrade opportunities per normal fresh hit."""

import argparse
import bisect
import collections
import gzip
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from trade_return_windows import attack_kind, link_clicks

SERVER = {"replication_server_input", "replication_server_clock", "science_server_attack", "hit"}
CLIENT = {"science_click", "engine_attack", "click", "reach_probe"}


def events(path, kinds, start, end):
    opener = gzip.open if path.suffix == ".gz" else open
    stems = tuple(f'"{kind}"' for kind in kinds)
    with opener(path, "rt") as f:
        for line in f:
            if not any(stem in line for stem in stems):
                continue
            event = json.loads(line)
            if event["type"] in kinds and start <= event["time"] <= end:
                yield event


def match_audit(mid, setup):
    game = json.loads((ROOT / f"runtime/results/{mid}.json").read_text())
    first, last = game["start"], game["finish"]
    files = ROOT / "runtime/pov" / mid
    server = list(events(ROOT / f"runtime/replays/{mid}.jsonl", SERVER, first, last + 100))
    players = [game["a"], game["b"]]
    links = {}
    probes = {}
    falling_clicks = {}
    for side in "ab":
        path, = files.glob(f"{side}-client-*-events.jsonl.gz")
        client = [e for e in events(path, CLIENT, first, last + 100) if e.get("match") == mid]
        clicks, attacks, checks = link_clicks(server, client, game, side)
        if checks["errors"] or checks["unreceived_tail"] > 2:
            raise ValueError((mid, side, checks))
        links.update(attacks)
        samples = sorted((e for e in client if e["type"] == "reach_probe" and not e["raycast_stale"]
                          and not e["own"]["ground"] and e["own"]["fallDistance"] > 0), key=lambda e: e["time"])
        probes[game[side]] = [e["time"] for e in samples], samples
        falling = {e["sequence"] for e in client if e["type"] == "science_click"
                   and not e["own"]["ground"] and e["own"]["fallDistance"] > 0}
        presses = sorted((e for e in clicks if e["sequence"] in falling), key=lambda e: e["consumption_ms"])
        falling_clicks[game[side]] = [e["consumption_ms"] for e in presses], presses
    score_log = {(h["hits_a"], h["hits_b"]): h for h in server if h["type"] == "hit"}
    if len(score_log) != game["hits_a"] + game["hits_b"]:
        raise ValueError(f"Incomplete scoreboard for {mid}")
    drains = {e["server_tick"]: e["time"] for e in server
              if e["type"] == "replication_server_clock" and e["phase"] == "drain_start"}

    latest_fresh = {}
    normal = {}
    pose = {}
    windows = []
    totals = collections.Counter()
    pose_agreement = collections.Counter()
    end_tick = max(e["server_tick"] for e in server if e["type"] == "hit")
    for i, e in enumerate(server):
        typ = e["type"]
        if typ == "replication_server_input" and "onGround" in e:
            player = e["player"]
            ground = e["onGround"]
            prior_y, prior_fall = pose.get(player, (e["y"], False))
            y = e["y"] if e["moving"] else prior_y
            falling = not ground and (prior_fall or y < prior_y)
            pose[player] = y, falling
            window = normal.get(player)
            if window and e["server_tick"] < min(end_tick, window["closes"]) and falling:
                window["falling_report_ticks"].add(e["server_tick"])
        if typ != "science_server_attack":
            continue
        attacker, target = e["attacker"], e["target"]
        kind = attack_kind(e, game)
        if kind == "after_winning_score":
            continue
        a, b = e["attacker_before"], e["target_before"]
        critical_pose = not a["ground"] and a["fallDistance"] > 0
        if attacker in pose:
            pose_agreement[pose[attacker][1] == critical_pose] += 1
        tick = e["server_tick"]
        now = e["monotonic_ns"]
        hit = score_log[tuple(e["score_after"])] if kind in ("fresh", "upgrade") else None
        if hit is not None and (hit["damage_upgrade"] != (kind == "upgrade") or hit["attacker"] != attacker):
            raise ValueError((mid, kind, hit))
        if kind == "fresh":
            recent_received = latest_fresh.get(target)
            latest_fresh[attacker] = e
            totals[attacker, "fresh"] += 1
            if hit["damage"] == 12:
                if not critical_pose:
                    raise ValueError(f"Critical without a falling server pose in {mid}")
                totals[attacker, "critical_fresh"] += 1
                normal.pop(attacker, None)
            elif hit["damage"] == 8:
                window = dict(player=attacker, start_ns=now, start_tick=tick,
                              closes=tick + int(b["maxHurtResistantTime"] / 2),
                              start_ms=e["time"]-first, start_pose="ground" if a["ground"] else "airborne",
                              falling_report_ticks=set(), contacts=0, descending_contacts=0,
                              first_received_before_ms=None if recent_received is None else
                              (now - recent_received["monotonic_ns"]) / 1e6,
                              upgrade=None)
                normal[attacker] = window
                windows.append(window)
            else:
                raise ValueError((mid, hit["damage"]))
        elif (window := normal.get(attacker)) is not None and tick < window["closes"]:
            if b["hurtResistantTime"] <= b["maxHurtResistantTime"] / 2:
                raise ValueError(f"Invalid immunity deadline in {mid}")
            window["contacts"] += 1
            window["descending_contacts"] += critical_pose
            if kind != "upgrade":
                continue
            if hit["damage"] != 12 or not critical_pose or b["lastDamage"] != 8 or window["upgrade"]:
                raise ValueError(f"Unreconciled upgrade in {mid}")
            incoming = latest_fresh.get(target)
            link = links[i]
            window["upgrade"] = dict(at_ms=e["time"] - first, tick=tick, relative_tick=tick - window["start_tick"],
                                     server_sprinting=a["sprint"], server_height=a["posY"]-4,
                                     native_ray=link["native_cached_ray_distance"], packet_id=e["packet_id"],
                                     age_since_received_ms=None if incoming is None else
                                     (now-incoming["monotonic_ns"])/1e6,
                                     received_after_window_start=incoming is not None
                                     and incoming["monotonic_ns"] > window["start_ns"])
            totals[attacker, "upgrade"] += 1

    for window in windows:
        window["falling_report_ticks"] = sorted(window["falling_report_ticks"])
        window["covered"] = window["closes"] <= end_tick
        if window["covered"]:
            times, samples = probes[window["player"]]
            deadline = drains[window["closes"]]
            last_immune_drain = drains[window["closes"] - 1]
            window["deadline_minus_last_immune_drain_ms"] = deadline - last_immune_drain
            start = bisect.bisect_left(times, first + window["start_ms"])
            stop = bisect.bisect_left(times, deadline)
            inside = samples[start:stop]
            ready = [e["time"] for e in inside if e["could_attempt"]]
            window["local_falling_samples"] = len(inside)
            window["local_falling_in_range"] = sum(e["in_range"] for e in inside)
            window["local_falling_ready"] = len(ready)
            if ready:
                window["first_ready_lead_ms"] = deadline - ready[0]
                window["last_ready_lead_ms"] = deadline - ready[-1]
            press_times, presses = falling_clicks[window["player"]]
            p0 = bisect.bisect_left(press_times, window["start_ms"])
            p1 = bisect.bisect_left(press_times, deadline - first)
            window["local_falling_clicks"] = [
                (deadline - first - press["consumption_ms"], press["outcome"], press["server_attack_tick"])
                for press in presses[p0:p1]
            ]
    assert [totals[player, "fresh"] + totals[player, "upgrade"] for player in players] == [game["hits_a"], game["hits_b"]]
    return dict(id=mid, arm=setup["arm"], nominated=game[setup["side"]], rtt=setup["rtt"],
                players=players, windows=windows, pose_agreement=pose_agreement,
                scores={p: {k: totals[p, k] for k in ("fresh", "critical_fresh", "upgrade")} for p in players})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path)
    parser.add_argument("manifests", type=Path, nargs="+")
    args = parser.parse_args()
    rows = []
    for path in args.manifests:
        manifest = json.loads(path.read_text())
        for request, mid in manifest["matches"].items():
            setup = manifest["plan"][request]
            if setup["match_format"] == "first-to-100":
                rows.append(match_audit(mid, setup))
                print(f"Verified {mid}", flush=True)
    args.destination.write_text(json.dumps(rows, indent=2) + "\n")


if __name__ == "__main__":
    main()
