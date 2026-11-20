"""Compare scheduled and ordinary Java on the same opening geometry in both historical panels."""
import csv
import io
import json
import math
from pathlib import Path
import struct
import subprocess
import tarfile
import tempfile

from audit import rows
from parity_lab import FIELDS
from setup import SRC, compile_sim, jdk


def first_hit_order_fork(base, m, clients, hits, jar):
    if not hits:
        return {}
    origin = int(clients[0][0]["epochMs"])
    delta = int(hits[0]["epochMs"]) - origin - 15
    if delta < 0 or delta % 50 or int(clients[1][0]["epochMs"]) != origin:
        raise ValueError("Single-tick fork requires a scheduled recording")
    tick = delta // 50
    lineup = [SRC / "evidence/league26/fighter.net"] * 2
    lineup[m["seat"]] = SRC / "evidence/random26/fighter.net" if m["policy"] == "learned" else "wtap"
    cmd = ["-cp", jar, "sword.KernelReplay", base, m["id"], str(m["rtt"] // 100), *lineup]
    result = {"changed_uplink_order_tick": tick}
    for label, flags in (("factual", []), ("reversed", [f"-Dsword.reverseUplinkTick={tick}"])):
        output = subprocess.check_output([jdk() / "java", *flags, *cmd], text=True)
        row = dict(zip(FIELDS, output.strip().split(","), strict=True))
        result[label + "_contact_sequence_exact"] = row["ordered_contacts_exact"]
        result[label + "_final_health_exact"] = row["final_health_exact"]
        result[label + "_end_tick"] = row["predicted_end_tick"]
        result[label + "_terminal_tick_exact"] = row["predicted_end_tick"] == row["java_end_tick"]
        result[label + "_hp"] = row["predicted_hp_" + "ab"[m["seat"]]]
        result[label + "_enemy_hp"] = row["predicted_hp_" + "ba"[m["seat"]]]
    return result


def read_panel(archive, clock, protocol, jar):
    with tempfile.TemporaryDirectory() as temp:
        base = Path(temp)
        with tarfile.open(archive) as recordings:
            recordings.extractall(base, filter="data")
        meta = json.loads((base / "metadata.json").read_text())
        if (meta["clock"] or "ordinary") != clock or meta["speed"] != 1 or meta["landmark"]:
            raise ValueError(f"Wrong timing or acquisition in {archive}")
        if meta["weights_sha256"] != protocol["weights_sha256"]["option5"]:
            raise ValueError(f"Unexpected playing weights in {archive}")
        rounds = [m for m in json.loads((base / "results.json").read_text())
                  if meta["opponent_hashes"].get(m["rival"]) == protocol["weights_sha256"]["option4"]]
        paths = [base / (m["id"] + "-contacts.tsv") for m in rounds]
        check = subprocess.check_output([jdk() / "java", "-cp", jar, "sword.ContactParity", *paths], text=True)
        checked = {r["id"]: r for r in csv.DictReader(io.StringIO(check))}
        games = {}
        for m, path in zip(rounds, paths, strict=True):
            key = (m["rtt"], m["pair"], m["seat"], m["policy"])
            if key in games:
                raise ValueError(f"Duplicate matchup {key}")
            players = [rows(base / (m["id"] + f"-{seat}.tsv")) for seat in "AB"]
            hits = rows(path)
            order = first_hit_order_fork(base, m, players, hits, jar) if clock == "fast" else {}
            games[key] = m, players, hits, checked[m["id"]], order
        return games


def position(row, fields):
    return tuple(float(row[f]) for f in fields)


def damage_observations(geometry, clock, game):
    m, players, hits, _, _ = game
    first_poll = []
    for client in players:
        by_health = {}
        for row in client:
            # Server writes native floats. The observation logger widens them to doubles.
            by_health.setdefault(struct.pack(">f", float(row["hp"])), row)
        first_poll.append(by_health)
    for hit in hits:
        defender = "AB".index(hit["defender"][-1])
        hp = float(hit["defenderHP"])
        stamp = int(hit["epochMs"])
        # With regeneration disabled, each defender's decreasing HP identifies the hit.
        poll = first_poll[defender].get(struct.pack(">f", hp))
        delay = int(poll["epochMs"]) - stamp if poll else None
        yield dict(geometry_set=geometry, clock=clock, rtt=m["rtt"], opening=m["pair"],
                   seat=m["seat"], arm=m["policy"], round=m["id"], defender="AB"[defender],
                   server_tick=hit["tick"], defender_hp=hp, terminal=hp == 0,
                   first_observed_poll=poll["tick"] if poll else None, ms_to_observation=delay)


def compare_game(fixed, ordinary):
    a, fixed_keys, fixed_hits, _, order = fixed
    b, tcp_keys, tcp_hits, check, _ = ordinary
    if (a["angle"], a["gap"]) != (b["angle"], b["gap"]):
        raise ValueError("Opening coordinates requested by the referee changed")
    result = dict(scheduled_round=a["id"], ordinary_round=b["id"], gap=a["gap"], angle=a["angle"])
    for label, m, clients, hits in (("scheduled", a, fixed_keys, fixed_hits), ("ordinary", b, tcp_keys, tcp_hits)):
        result[label + "_hp"] = m["hp"]
        result[label + "_enemy_hp"] = m["opponent_hp"]
        result[label + "_first_attacker"] = hits[0]["attacker"] if hits else ""
        result[label + "_start_A_minus_B_ms"] = int(clients[0][0]["epochMs"]) - int(clients[1][0]["epochMs"])
        intervals = [int(n["epochMs"]) - int(p["epochMs"]) for side in clients for p, n in zip(side, side[1:])]
        result[label + "_min_poll_interval_ms"] = min(intervals)
        result[label + "_max_poll_interval_ms"] = max(intervals)

    xyz = ["x", "y", "z"]
    displayed = ["targetX", "targetY", "targetZ"]
    keys = ["forward", "side", "runKey", "jump", "press"]
    for label, left, right in zip("AB", fixed_keys, tcp_keys, strict=True):
        result[label + "_initial_own_error"] = math.dist(position(left[0], xyz), position(right[0], xyz))
        result[label + "_initial_display_error"] = math.dist(position(left[0], displayed), position(right[0], displayed))
        # Align each client's polls by index from release. This compares observations,
        # not physical simultaneity across the two independent native processes.
        for fields, suffix in [(displayed, "display"), (keys, "key")]:
            result[label + "_first_" + suffix + "_difference_poll"] = next(
                (i for i, (u, v) in enumerate(zip(left, right)) if any(u[f] != v[f] for f in fields)), "")
        for kind, tape in [("scheduled", left), ("ordinary", right)]:
            result[label + "_" + kind + "_first_press_poll"] = next(
                (i for i, r in enumerate(tape) if r["press"] == "true"), "")
    result.update({"ordinary_" + k: v for k, v in check.items() if k != "id"})
    result.update(order)
    return result


def main():
    original = SRC / "evidence/random26"
    dest = original / "clock-crossover"
    protocol = json.loads((dest / "protocol.json").read_text())
    jar = compile_sim()
    records = []
    observations = []
    for geometry, fast, tcp in [
        ("scheduled", [original / f"native-{n}-traces.tar.gz" for n in (0, 100)], dest / "ordinary-traces.tar.gz"),
        ("ordinary", [dest / f"scheduled-{n}-traces.tar.gz" for n in (0, 100)], original / "ordinary-traces.tar.gz")
    ]:
        scheduled = {}
        for archive in fast:
            scheduled.update(read_panel(archive, "fast", protocol, jar))
        ordinary = read_panel(tcp, "ordinary", protocol, jar)
        expected = {(rtt, p, seat, arm) for rtt in (0, 100) for p in (0, 1)
                    for seat in (0, 1) for arm in ("learned", "wtap")}
        if scheduled.keys() != expected or ordinary.keys() != expected:
            raise ValueError(f"Incomplete comparison for {geometry}")
        for key in sorted(expected):
            rtt, opening, seat, arm = key
            records.append(dict(geometry_set=geometry, rtt=rtt, opening=opening, seat=seat, arm=arm,
                                **compare_game(scheduled[key], ordinary[key])))
            for clock, game in (("scheduled", scheduled[key]), ("ordinary", ordinary[key])):
                observations.extend(damage_observations(geometry, clock, game))
    for name, data in (("paired-clocks.csv", records), ("damage-observations.csv", observations)):
        with (dest / name).open("w") as out:
            writer = csv.DictWriter(out, fieldnames=data[0], lineterminator="\n")
            writer.writeheader()
            writer.writerows(data)


if __name__ == "__main__":
    main()
