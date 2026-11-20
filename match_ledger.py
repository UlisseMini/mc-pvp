#!/usr/bin/env python3
"""Inspect saved practice matches and MMC Sumo clips without connecting to Minecraft."""

import argparse
import base64
from collections import Counter, defaultdict
import gzip
import json
import math
from pathlib import Path
import re
import struct
import tempfile

ROOT = Path(__file__).resolve().parent
RUNTIME = ROOT / "runtime"
GAME = Path.home() / "Library/Application Support/minecraft"
PRACTICE_ID = re.compile(r"m[0-9]{4,9}")
SUMO_ID = re.compile(r"[0-9a-f]{32}")
CAPTURE_NAME = re.compile(r"a-client-[0-9a-fA-F-]{16,160}-events\.jsonl\.gz")
LINKED_PART = re.compile(r"[^/\\]+\.jsonl\.gz")
TRADE_MS = 120
CHAIN_MS = 1100


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def rows(path, warnings):
    opener = gzip.open if path.suffix == ".gz" else open
    try:
        with opener(path, "rt", encoding="utf-8") as stream:
            for number, line in enumerate(stream, 1):
                try:
                    yield json.loads(line), {"file": str(path), "line": number}
                except json.JSONDecodeError:
                    warnings.append(f"{path.name} has malformed JSON at line {number}")
    except (OSError, EOFError, UnicodeError) as error:
        warnings.append(f"{path.name}: {error}")


def capture_rows(first, end, warnings):
    """Follow the writer's own segment links. Stop after the requested time range."""
    visited = set()
    path = first
    while path is not None and len(visited) < 256:
        if path in visited:
            warnings.append("Capture segment cycle")
            return
        visited.add(path)
        footer = None
        past_end = False
        for row, ref in rows(path, warnings):
            if row.get("type") == "capture_end":
                footer = row
            if row.get("time_ms", 0) > end + 1500:
                past_end = True
            if not past_end:
                yield row, ref
        if footer and (not footer.get("writer_complete") or footer.get("dropped") or footer.get("size_capped")):
            warnings.append(f"Writer reported gaps in {path.name}")
        if past_end:
            if footer is None:
                warnings.append(f"No capture footer in {path.name}")
            return
        if footer is None:
            warnings.append(f"No capture footer in {path.name}")
            return
        linked = footer.get("continues")
        if not linked:
            return
        if not LINKED_PART.fullmatch(linked):
            warnings.append("Invalid capture segment link")
            return
        path = path.with_name(linked)
    if path is not None:
        warnings.append("Capture segment read limit reached")


def varint(data, offset):
    value = 0
    for shift in range(0, 35, 7):
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, offset
    raise ValueError("Invalid packet VarInt")


def packet_details(data):
    packet = data.get("packet", data)
    name = packet.get("kind")
    if name not in {"S12PacketEntityVelocity", "C02PacketUseEntity", "S19PacketEntityStatus",
                    "S0BPacketAnimation", "S08PacketPlayerPosLook", "C0BPacketEntityAction"}:
        return None
    blob = base64.b64decode(data.get("base64", packet.get("base64", "")), validate=True)
    protocol, offset = varint(blob, 0)
    direction = packet.get("direction")
    result = {"packet_id": packet.get("packet_id"), "direction": direction, "protocol": name}
    if protocol == 0x08 and direction == "in":
        return "position_reset", result
    if protocol == 0x1A and direction == "in":
        if len(blob) != offset + 5 or blob[-1] != 2:
            return None
        result["entity_id"] = struct.unpack_from(">i", blob, offset)[0]
        return "hurt_cue", result
    entity, offset = varint(blob, offset)
    result["entity_id"] = entity
    if protocol == 0x12 and direction == "in":
        result["raw_components"] = list(struct.unpack_from(">hhh", blob, offset))
        result["velocity"] = [component / 8000 for component in result["raw_components"]]
        return "velocity", result
    if protocol == 0x0B and direction == "in" and blob[offset] == 1:
        return "hurt_cue", result
    if protocol == 0x02 and direction == "out" and varint(blob, offset)[0] == 1:
        return "attack_packet", result
    if protocol == 0x0B and direction == "out":
        action = varint(blob, offset)[0]
        if action in (3, 4):
            result["sprint"] = action == 3
            return "sprint_packet", result
    return None


def pose(state):
    return {key: state[key] for key in ("pos", "motion", "box", "ground", "sprinting", "yaw", "pitch",
                                        "hurtTime", "movement_input", "id", "eye_height", "border") if key in state}


def reach_distance(own, target):
    if not own or not target or not all(key in own for key in ("pos", "eye_height")) or "box" not in target:
        return None
    eye = list(own["pos"])
    eye[1] += own["eye_height"]
    box = target["box"]
    border = target.get("border", 0)
    return math.sqrt(sum(max(box[a] - border - eye[a], eye[a] - box[a + 3] - border, 0)**2 for a in range(3)))


def event(kind, source, side, row, ref, start, details, *other_refs):
    when = row.get("time_ms", row.get("time"))
    clock = row.get("monotonic_ns")
    return {"kind": kind, "source": source, "side": side, "ms": when - start,
            "clock": str(clock) if isinstance(clock, int) else None,
            "tick": row.get("client_tick", row.get("server_tick")), "details": details,
            "refs": [ref, *other_refs]}


def event_order(row):
    return (int(row["clock"] or 0), row["ms"])


def contact_details(before, after):
    src, dst = before.get("self") or {}, after.get("self") or {}
    ray = before.get("ray") or {}
    left, right = src.get("motion"), dst.get("motion")
    scale = None
    if (left and right and ray.get("type") == "ENTITY" and before.get("left_click_counter") == 0
            and ray.get("entity") == (after.get("ray") or {}).get("entity")
            and src.get("id") == dst.get("id") and src.get("pos") == dst.get("pos")
            and math.hypot(left[0], left[2]) > .005 and abs(left[1] - right[1]) < 1e-7
            and all(abs(left[axis] * .6 - right[axis]) < 1e-7 for axis in (0, 2))):
        scale = .6
    return {"ray": ray.get("type"), "target_id": ray.get("entity"), "miss_counter": before.get("left_click_counter"),
            "ground": src.get("ground"), "local_sprint": src.get("sprinting"), "velocity_before": left,
            "velocity_after": right, "horizontal_scale": scale}


def read_client(first, start, end, side, other_name, warnings):
    source = f"{side}-client"
    events, samples = [], []
    wire, applications, starts, presses, clicks, travels = [], defaultdict(list), {}, [], {}, []
    wire_counts = Counter()
    identities = defaultdict(set)
    for row, ref in capture_rows(first, end, warnings):
        t = row.get("time_ms", 0)
        if not start <= t <= end:
            continue
        kind, data, tick = row.get("type"), row.get("data") or {}, row.get("client_tick")
        own = data.get("self") or {}
        if own.get("id") is not None:
            identities[side].add(own["id"])
        if kind in ("tick_start", "tick_end") and own:
            target = next((actor for actor in data.get("entities") or []
                           if actor.get("name", "").casefold() == other_name.casefold()), None)
            if target:
                identities["b" if side == "a" else "a"].add(target["id"])
            if kind == "tick_start":
                starts[tick] = (row, target, ref)
            elif own.get("pos"):
                samples.append({"ms": t - start, side: pose(own), "observed_target": pose(target) if target else None,
                                "ref": ref})
        elif kind == "attack_click_before":
            clicks[tick] = (row, ref)
        elif kind == "attack_click_after" and tick in clicks:
            previous, click_ref = clicks.pop(tick)
            before = previous["data"]
            details = contact_details(before, data)
            if isinstance(row.get("monotonic_ns"), int):
                details["after_clock"] = str(row["monotonic_ns"])
            snapshot = starts.get(tick)
            refs = [ref]
            if snapshot and 0 <= previous["time_ms"] - snapshot[0]["time_ms"] <= 15:
                distance = reach_distance(before.get("self"), snapshot[1])
                if distance is not None:
                    details["tick_start_box_distance"] = distance
                    refs.append(snapshot[2])
            events.append(event("click", source, side, previous, click_ref, start, details, *refs))
        elif kind == "motion_travel_before" and own:
            travels.append(event("travel", source, side, row, ref, start, pose(own)))
        elif kind == "packet_wire":
            try:
                decoded = packet_details(data)
            except (IndexError, ValueError, struct.error):
                warnings.append(f"Malformed combat packet in {Path(ref['file']).name}:{ref['line']}")
                continue
            if decoded:
                key = decoded[1]["packet_id"]
                if key is not None:
                    wire_counts[key] += 1
                wire.append((row, ref, *decoded))
        elif kind == "packet_handler_return" and data.get("main_thread") and data.get("packet_id") is not None:
            applications[data["packet_id"]].append((row, ref))
        elif (kind == "key" and not data.get("repeat") and data.get("key") in (17, 30, 31, 32, 57)) or (
                kind == "mouse" and data.get("button") in (0, 1)):
            presses.append(event("input", source, side, row, ref, start,
                                 {k: data[k] for k in ("key", "button", "down", "event_ns") if k in data}))
    for packet_id, count in wire_counts.items():
        if count > 1:
            warnings.append(f"Duplicate packet ID {packet_id} in {source}")
    for row, ref, kind, detail in wire:
        packet_id = detail["packet_id"]
        candidates = applications.get(packet_id, [])
        applied = (candidates[0] if wire_counts[packet_id] == len(candidates) == 1
                   and candidates[0][0]["time_ms"] >= row["time_ms"] else None)
        subject = next((actor for actor, ids in identities.items()
                        if detail.get("entity_id") in ids and sum(detail.get("entity_id") in i for i in identities.values()) == 1), None)
        detail.update(entity_side=subject, timing="handler_return" if applied else "wire", wire_ms=row["time_ms"] - start)
        if applied:
            events.append(event(kind, source, side, *applied, start, detail, ref))
        else:
            events.append(event(kind, source, side, row, ref, start, detail))
    for row, ref in clicks.values():
        details = contact_details(row["data"], {})
        details["paired_after_missing"] = True
        events.append(event("click", source, side, row, ref, start, details))
    local_velocities = sorted((e for e in events if e["kind"] == "velocity" and e["details"]["entity_side"] == side
                               and e["details"]["timing"] == "handler_return" and e["clock"]), key=event_order)
    travel_index = 0
    travels = sorted((e for e in travels if e["clock"]), key=event_order)
    for index, velocity in enumerate(local_velocities):
        while travel_index < len(travels) and event_order(travels[travel_index]) <= event_order(velocity):
            travel_index += 1
        if travel_index == len(travels):
            break
        travel = travels[travel_index]
        if index + 1 < len(local_velocities) and event_order(local_velocities[index + 1]) < event_order(travel):
            continue
        if travel["ms"] - velocity["ms"] <= 175:
            velocity["details"]["first_travel"] = {"ms": travel["ms"], "tick": travel["tick"],
                                                    "clock": travel["clock"], "state": travel["details"], "refs": travel["refs"]}
    return events + presses, samples


def practice(identifier, runtime, warnings):
    result_file = runtime / "results" / f"{identifier}.json"
    result = read_json(result_file)
    names = {side: result[side] for side in ("a", "b")}
    by_name = {name: side for side, name in names.items()}
    start, end = result["start"], result["finish"]
    server_file = runtime / "replays" / f"{identifier}.jsonl"
    archive = runtime / "pov" / identifier
    events, trajectory = [], []
    server_hits, server_attacks = [], defaultdict(list)
    for row, ref in rows(server_file, warnings):
        kind, time = row.get("type"), row.get("time", 0)
        if not start <= time <= end:
            continue
        if kind == "tick":
            sample = {"ms": time - start, "ref": ref}
            for side in names:
                state = row.get(side) or {}
                if all("pos" + axis in state for axis in "XYZ"):
                    sample[side] = {"pos": [state["pos" + axis] for axis in "XYZ"], "ground": state.get("ground")}
            if any(side in sample for side in names):
                trajectory.append(sample)
        elif kind == "hit" and row.get("attacker") in by_name:
            server_hits.append((row, ref))
        elif kind == "science_server_attack" and row.get("attacker") in by_name:
            key = (row.get("attacker"), row.get("target"), row.get("server_tick"), tuple(row.get("score_after") or []))
            server_attacks[key].append((row, ref))
    for row, ref in server_hits:
        key = (row.get("attacker"), row.get("target"), row.get("server_tick"), (row.get("hits_a"), row.get("hits_b")))
        linked = server_attacks[key]
        detail = {"target": by_name.get(row.get("target")), "score": [row.get("hits_a"), row.get("hits_b")],
                  "result": "upgrade" if row.get("damage_upgrade") else "fresh" if "damage_upgrade" in row else "accepted",
                  "server_sprint": row.get("attacker_sprint"), "rod": bool(row.get("rod"))}
        refs = []
        if len(linked) == 1 and linked[0][0].get("score_before") != linked[0][0].get("score_after"):
            attack, attack_ref = linked[0]
            detail.update(server_packet_id=attack.get("packet_id"), velocity_packet=attack.get("velocity_packet"))
            refs.append(attack_ref)
        events.append(event("server_hit", "server", by_name[row["attacker"]], row, ref, start, detail, *refs))
    samples = {}
    for side, other in (("a", "b"), ("b", "a")):
        first = next((p for p in sorted(archive.glob(f"{side}-client-*-events*.jsonl.gz"))
                      if not re.search(r"-part[0-9]+\.jsonl\.gz$", p.name)), None)
        if first is None:
            warnings.append(f"No {side}-client event archive")
            continue
        client, samples[side] = read_client(first, start, end, side, names[other], warnings)
        events.extend(client)
    if not trajectory and samples.get("a"):
        trajectory = samples["a"]
    mode = result.get("mode")
    events.append(event("finish", "server", None, {"time": end}, {"file": str(result_file), "line": 1}, start,
                        {"outcome": result.get("winner") or result.get("reason"), "reason": result.get("reason")}))
    return {"id": identifier, "source": "practice", "mode": mode, "names": names, "start_ms": start, "finish_ms": end,
            "outcome": result.get("winner") or result.get("reason"), "score": [result.get("hits_a"), result.get("hits_b")],
            "geometry": {"center": [4096 * (result["arena"] + 1), 0], **result["sumo_arena"]}
            if mode == "sumo" and result.get("sumo_arena") and isinstance(result.get("arena"), int) else None,
            "trajectory_scope": "Practice server positions" if trajectory and "observed_target" not in trajectory[0]
            else "Player A client view",
            "events": events, "trajectory": trajectory, "client_samples": samples}


def read_bookmark(path):
    return dict(line.split("=", 1) for line in path.read_text(encoding="utf-8").splitlines()
                if line and not line.startswith("#") and "=" in line)


def mmc(identifier, game, warnings):
    directory = game / "config/pvp-practice"
    path = directory / "sumo-replays" / f"{identifier}.properties"
    properties = read_bookmark(path)
    start, end = int(properties["start"]), int(properties["finish"])
    part, capture = int(properties["part"]), properties["capture"]
    if not CAPTURE_NAME.fullmatch(capture) or part < 1 or end <= start:
        raise ValueError("Invalid MMC replay bookmark")
    if part != 1:
        capture = capture.removesuffix(".jsonl.gz") + f"-part{part}.jsonl.gz"
    first = directory / "captures" / capture
    if not first.is_file():
        raise ValueError("The MMC bookmark's capture segment is missing")
    events, samples = read_client(first, start, end, "a", properties["opponent"], warnings)
    events.append(event("finish", "bookmark", None, {"time": end}, {"file": str(path), "line": 1}, start,
                        {"outcome": "Win" if properties["won"] == "true" else "Loss", "reason": "saved Sumo result"}))
    trajectory = [{"ms": row["ms"], "a": row["a"], "b": row["observed_target"], "ref": row["ref"]} for row in samples]
    return {"id": identifier, "source": "mmc", "mode": "sumo",
            "names": {"a": "You", "b": properties["opponent"]}, "start_ms": start, "finish_ms": end,
            "outcome": "Win" if properties["won"] == "true" else "Loss", "score": None, "geometry": None,
            "trajectory_scope": "Your client view of both players", "events": events, "trajectory": trajectory,
            "client_samples": {"a": samples}}


def anchors(ledger):
    if ledger["source"] == "practice":
        hits = [row for row in ledger["events"] if row["kind"] == "server_hit"]
        return [(row, row["side"]) for row in hits if row["details"]["result"] != "upgrade" and not row["details"]["rod"]]
    result = []
    last_cue = -1000
    for row in ledger["events"]:
        detail = row["details"]
        if row["kind"] == "velocity" and detail["entity_side"] == "a":
            vector = detail["velocity"]
            if math.hypot(vector[0], vector[2]) > .03:
                result.append((row, "b"))
        if row["kind"] == "hurt_cue" and detail["entity_side"] == "b" and row["ms"] - last_cue > 35:
            result.append((row, "a"))
            last_cue = row["ms"]
    return result


def group_anchors(points):
    groups = []
    index = 0
    while index < len(points):
        head, side = points[index]
        tail, next_side = points[index + 1] if index + 1 < len(points) else (None, None)
        same_tick = (tail is not None and head["source"] == tail["source"] == "server"
                     and head["tick"] is not None and head["tick"] == tail["tick"])
        if tail is not None and side != next_side and (same_tick or tail["ms"] - head["ms"] <= TRADE_MS):
            groups.append(("trade", [points[index], points[index + 1]]))
            index += 2
            continue
        if groups and groups[-1][0] == "sequence" and groups[-1][1][-1][1] == side and (
                head["ms"] - groups[-1][1][-1][0]["ms"] <= CHAIN_MS):
            groups[-1][1].append(points[index])
        else:
            groups.append(("sequence", [points[index]]))
        index += 1
    return groups


def describe(row, names):
    detail = row["details"]
    name = names.get(row["side"], row["source"])
    at = f"{row['ms'] / 1000:.3f}s"
    kind = row["kind"]
    if kind == "server_hit":
        vector = detail.get("velocity_packet")
        velocity = f" · server recorded velocity {', '.join(f'{v:+.5f}' for v in vector)}" if vector else ""
        sprint = detail.get("server_sprint")
        sprint_note = "" if sprint is None else f" · server sprint {'on' if sprint else 'off'}"
        return (f"{at} · {name} · {detail['result']} {'rod' if detail['rod'] else 'melee'} hit"
                f" · {detail['score'][0]}–{detail['score'][1]}{sprint_note}{velocity}")
    if kind == "velocity":
        timing = "applied" if detail["timing"] == "handler_return" else "received on wire"
        subject = names.get(detail["entity_side"], "unidentified entity")
        xyz = ", ".join(f"{value:+.5f}" for value in detail["velocity"])
        return f"{at} · {row['source']} · S12 for {subject} {timing} · ({xyz}) b/t"
    if kind == "hurt_cue":
        return f"{at} · {row['source']} · hurt cue for {names.get(detail['entity_side'], 'unidentified entity')}"
    if kind == "attack_packet":
        return f"{at} · {name} sent C02 attack · entity {detail['entity_id']}"
    if kind == "click":
        note = f" · horizontal motion ×{detail['horizontal_scale']}" if detail["horizontal_scale"] else ""
        cooldown = f" · miss counter {detail['miss_counter']}" if detail["miss_counter"] else ""
        sprint = detail.get("local_sprint")
        sprint_note = "" if sprint is None else f" · client sprint {'on' if sprint else 'off'}"
        near = detail.get("tick_start_box_distance")
        box = f" · nearby tick-start box {near:.3f} blocks away" if near is not None else ""
        return f"{at} · {name} clicked · ray {detail['ray'] or 'unavailable'}{cooldown}{sprint_note}{note}{box}"
    if kind == "input":
        key = {17:"W", 30:"A", 31:"S", 32:"D", 57:"Space"}.get(detail.get("key"))
        control = key or ("LMB" if detail.get("button") == 0 else "RMB")
        return f"{at} · {name} · {control} {'down' if detail.get('down') else 'up'}"
    if kind == "sprint_packet":
        return f"{at} · {name} sent {'start' if detail['sprint'] else 'stop'} sprint"
    if kind == "finish":
        return f"{at} · {row['source']} result · {detail['outcome']} · {detail['reason'] or 'reason unavailable'}"
    return f"{at} · {row['source']} · server position reset"


def summarize(ledger):
    names, events = ledger["names"], ledger["events"]
    ledger["trajectory"].sort(key=lambda row: row["ms"])
    events.sort(key=lambda row: (row["ms"], row["source"], event_order(row)))
    for index, row in enumerate(events, 1):
        row["id"] = index
        row["label"] = describe(row, names)
        row["evidence"] = "measured"
    cuts = [row for row in events if row["kind"] == "click" and row["details"]["horizontal_scale"]
            and row["details"].get("after_clock")]
    for row in events:
        travel = row["details"].get("first_travel")
        if travel is None:
            continue
        travel["preceding_local_cut_ids"] = [
            click["id"] for click in cuts if click["source"] == row["source"]
            and int(row["clock"]) < int(click["clock"]) <= int(click["details"]["after_clock"]) < int(travel["clock"])]
    groups = group_anchors(anchors(ledger))
    windows = []
    for index, (kind, points) in enumerate(groups, 1):
        first, last = points[0][0]["ms"], points[-1][0]["ms"]
        stop = groups[index][1][0][0]["ms"] if index < len(groups) else ledger["finish_ms"] - ledger["start_ms"]
        review_until = min(stop, last + 650) if index < len(groups) else stop
        evidence = [row for row in events if first - 250 <= row["ms"] <= review_until]
        same_tick = (kind == "trade" and ledger["source"] == "practice" and
                     points[0][0]["tick"] is not None and points[0][0]["tick"] == points[1][0]["tick"])
        if kind == "trade":
            title = "Same-tick trade" if same_tick else "Possible trade" if ledger["source"] == "mmc" else "Nearby reciprocal hits"
            association = "Both sides scored on the same recorded server tick." if same_tick else (
                "A hurt cue and incoming velocity arrived close together on your client. They are grouped for review."
                if ledger["source"] == "mmc" else "Confirmed hits occurred close together on different server ticks.")
        else:
            side = points[0][1]
            title = f"{names[side]} · {'pressure sequence' if len(points) > 1 else 'single observation'}"
            association = (f"{len(points)} consecutive {'server melee hits' if ledger['source'] == 'practice' else 'client cues'} "
                           f"are grouped over {(last-first)/1000:.3f}s without an opposite anchor between them."
                           if len(points) > 1 else "This window has one anchor observation.")
        unknown = []
        if ledger["source"] == "mmc":
            unknown.extend(["Exact server hit order and acceptance of individual clicks", "Opponent inputs and server sprint state"])
            if not any(row["kind"] == "velocity" and row["details"]["entity_side"] == "b" for row in evidence):
                unknown.append("Opponent's exact velocity packet")
        elif kind == "trade":
            unknown.append("Whether a different input would have changed the exchange")
        if not any(row["kind"] == "velocity" and row["details"].get("first_travel") for row in evidence):
            unknown.append("First movement step after the velocity packet")
        facts = [row["label"] for row, _ in points]
        for row in evidence:
            travel = row["details"].get("first_travel")
            if travel and travel["preceding_local_cut_ids"]:
                who = names.get(row["side"], row["source"])
                grounded = travel["state"].get("ground")
                stance = "grounded" if grounded is True else "airborne" if grounded is False else "unknown ground state"
                facts.append(f"{who} · local horizontal cut after S12 and before the first movement tick · {stance}")
        facts += [row["label"] for row in evidence if row["kind"] == "click"
                  and (row["details"]["horizontal_scale"] or row["details"]["miss_counter"])][:6]
        if index == len(groups):
            facts.extend(row["label"] for row in evidence if row["kind"] == "finish")
        windows.append({"id": index, "kind": kind, "title": title, "start_ms": first, "end_ms": last,
                        "seek_ms": max(0, first - 250), "known": facts, "interpretation": association,
                        "confidence": "measured" if same_tick else "inferred",
                        "unknown": unknown, "anchor_ids": [row["id"] for row, _ in points],
                        "event_ids": [row["id"] for row in evidence]})
    ledger["windows"] = windows
    ledger["coverage"] = {"events": dict(Counter(row["kind"] for row in events)),
                          "local_pose_samples": {side: len(rows) for side, rows in ledger["client_samples"].items()},
                          "server_pose_samples": len(ledger["trajectory"]) if ledger["trajectory_scope"].startswith("Practice server") else 0}
    return ledger


def analyze(identifier, runtime=RUNTIME, game=GAME):
    warnings = []
    if PRACTICE_ID.fullmatch(identifier):
        ledger = practice(identifier, runtime, warnings)
    elif SUMO_ID.fullmatch(identifier):
        ledger = mmc(identifier, game, warnings)
    else:
        raise ValueError("Use a practice match ID (m2636) or a full MMC Sumo bookmark ID")
    ledger["warnings"] = list(dict.fromkeys(warnings))
    return summarize(ledger)


def atomic_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, encoding="utf-8", mode="w", delete=False) as out:
        temporary = Path(out.name)
        json.dump(value, out, ensure_ascii=False, separators=(",", ":"))
        out.write("\n")
    try:
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def write_report(ledger, output, game, register=True):
    output.mkdir(parents=True, exist_ok=True)
    identifier = ledger["id"]
    atomic_json(output / f"{identifier}.json", ledger)
    if register:
        sidecar = {"id": identifier, "start_ms": ledger["start_ms"], "windows": [
            {key: window[key] for key in ("id", "title", "seek_ms", "start_ms", "confidence")} for window in ledger["windows"]]}
        atomic_json(game / "config/pvp-practice/match-ledgers" / f"{identifier}.json", sidecar)
    payload = json.dumps(ledger, ensure_ascii=False, separators=(",", ":")).replace("<", "\\u003c")
    template = ROOT / "match_ledger_view.html"
    path = output / f"{identifier}.html"
    path.write_text(template.read_text(encoding="utf-8").replace("__LEDGER_DATA__", payload), encoding="utf-8")
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("match", nargs="?", help="Practice match ID or full MMC Sumo bookmark ID")
    parser.add_argument("--game-dir", type=Path, default=GAME, help="Minecraft installation directory")
    parser.add_argument("--runtime", type=Path, default=RUNTIME, help="Practice archive directory")
    parser.add_argument("--output", type=Path, default=ROOT / "recordings/match-ledgers", help="Report directory")
    parser.add_argument("--no-register", action="store_true", help="Skip installing in-game bookmarks")
    parser.add_argument("--exchange", type=int, help="Print one exchange and its evidence as JSON")
    parser.add_argument("--list-mmc", action="store_true", help="List recent MMC Sumo bookmark IDs")
    args = parser.parse_args()
    try:
        if args.list_mmc:
            directory = args.game_dir / "config/pvp-practice/sumo-replays"
            bookmarks = []
            for path in directory.glob("*.properties"):
                if not SUMO_ID.fullmatch(path.stem):
                    continue
                try:
                    props = read_bookmark(path)
                    bookmarks.append((int(props["finish"]), path.stem, props["opponent"], props["won"]))
                except (OSError, ValueError, KeyError):
                    continue
            for _, match, opponent, won in sorted(bookmarks, reverse=True)[:15]:
                print(f"{match}  {'Win ' if won == 'true' else 'Loss'}  vs {opponent}")
            if not bookmarks:
                print("No MMC Sumo bookmarks found.")
            return
        if not args.match:
            parser.error("Choose a match ID or use --list-mmc")
        ledger = analyze(args.match, args.runtime.resolve(), args.game_dir.resolve())
        if args.exchange is not None:
            if not 1 <= args.exchange <= len(ledger["windows"]):
                raise ValueError(f"Exchange must be between 1 and {len(ledger['windows'])}")
            exchange = ledger["windows"][args.exchange - 1]
            keep = set(exchange["event_ids"])
            print(json.dumps({"exchange": exchange, "events": [row for row in ledger["events"] if row["id"] in keep]},
                             ensure_ascii=False, indent=2))
        else:
            print(write_report(ledger, args.output.resolve(), args.game_dir.resolve(), not args.no_register))
    except (OSError, ValueError, KeyError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
