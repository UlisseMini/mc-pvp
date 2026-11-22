#!/usr/bin/env python3
"""Verify the Forge blockhit coach against an isolated native 1.8.9 server."""
import argparse
import asyncio
import collections
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from build import compile_to, jar
import forge_runtime as forge
import lab_runtime as lab

SWORD_IDS = {267, 268, 272, 276, 283}
PLAYER = "LabObserver"


def serve_fixture(work, build, offset, arrows):
    lab.ROOT = work
    lab.RUNTIME = work / "runtime"
    forge.RUNTIME = lab.RUNTIME
    forge.BRIDGE = build / "practice-bridge.jar"
    forge.MODULE = build / "practice-module.jar"
    sandbox_profile = lab.sandbox_profile
    original_bot_command = forge.bot_command

    def sandbox():
        lab.ROOT = ROOT
        try:
            return sandbox_profile()
        finally:
            lab.ROOT = work

    def attacker(slot, port, frame_sleep, classpath):
        probe = lab.RUNTIME / "bots" / str(slot) / "mods/combat-cases.jar"
        probe.unlink(missing_ok=True)
        command = original_bot_command(slot, port, frame_sleep, classpath)
        shutil.copyfile(work / "combat-cases.jar", probe)
        command[command.index("-Dlab.root=" + str(ROOT))] = "-Dlab.root=" + str(work)
        command.insert(command.index("-cp"), "-Dprobe.attacker=true")
        if arrows:
            command.insert(command.index("-cp"), "-Dprobe.arrows=true")
        return command

    lab.sandbox_profile = sandbox
    forge.bot_command = attacker
    asyncio.run(lab.serve(10, bot_backend="forge", bot_slots=1, port_offset=offset))


def varint(value):
    data = bytearray()
    while value >= 128:
        data.append((value & 127) | 128)
        value >>= 7
    data.append(value)
    return bytes(data)


def read_varint(data, start=0):
    number = 0
    for shift in range(0, 35, 7):
        byte = data[start]
        start += 1
        number |= (byte & 127) << shift
        if byte < 128:
            return number, start
    raise ValueError("Invalid Minecraft VarInt")


async def read_frame(reader):
    size = 0
    for shift in range(0, 35, 7):
        byte = (await reader.readexactly(1))[0]
        size |= (byte & 127) << shift
        if byte < 128:
            return await reader.readexactly(size)
    raise ValueError("Invalid Minecraft frame size")


def hide_sword_tag(payload, offset):
    _, offset = read_varint(payload, offset)  # entity ID
    slot, item = struct.unpack_from(">hh", payload, offset)
    if slot == 0 and item in SWORD_IDS and payload[offset + 7] != 0:
        # A 1.8 ItemStack ends in its NBT tag. Leave the item and durability visible.
        return payload[:offset + 7] + b"\0"
    return payload


async def redact_equipment(client, downstream, port, counts, perturb):
    upstream, server = await asyncio.open_connection("127.0.0.1", port)

    async def upstream_packets():
        while chunk := await client.read(65536):
            server.write(chunk)
            await server.drain()

    async def downstream_packets():
        play = False
        own_id = None
        sustained = False
        pending_hurt = None

        def send(frame):
            downstream.write(varint(len(frame)) + frame)

        while True:
            frame = await read_frame(upstream)
            packet, offset = read_varint(frame)
            if play:
                if packet == 1:
                    own_id = struct.unpack_from(">i", frame, offset)[0]
                elif packet == 2 and b"probe-sustained" in frame:
                    sustained = perturb
                elif packet == 4:
                    filtered = hide_sword_tag(frame, offset)
                    counts["redacted"] += frame != filtered
                    frame = filtered
                if sustained and packet == 0x12 and read_varint(frame, offset)[0] == own_id:
                    counts["velocity_skipped"] += 1
                    continue
                if sustained and packet == 0x1a and struct.unpack_from(">iB", frame, offset) == (own_id, 2):
                    if pending_hurt is not None:
                        send(pending_hurt)
                    pending_hurt = frame
                    continue
                own_metadata = packet == 0x1c and read_varint(frame, offset)[0] == own_id
                if pending_hurt is not None and (packet == 0x06 or own_metadata):
                    send(frame)
                    await downstream.drain()
                    await asyncio.sleep(.055)
                    send(pending_hurt)
                    pending_hurt = None
                    counts["hurt_reordered"] += 1
                    await downstream.drain()
                    continue
            elif packet == 2:
                play = True
            elif packet == 3:
                raise ValueError("The test proxy expects the lab's uncompressed protocol")
            send(frame)
            await downstream.drain()

    tasks = [asyncio.create_task(upstream_packets()), asyncio.create_task(downstream_packets())]
    try:
        await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
    finally:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        server.close()
        downstream.close()


def prepare_fixture(work, build):
    for folder in ("runtime", "policies"):
        (work / folder).mkdir()
    (work / "practice-client").symlink_to(ROOT / "practice-client")
    (work / "policies/probe-stand.properties").write_text(
        "kind=scripted\ninput_script=0:0:0:0\nclick_start_ms=10000000\n")
    libraries = json.loads((ROOT / ".cache/classpath.json").read_text())
    cp = os.pathsep.join(map(str, [build / "compile-api.jar", build / "api", *libraries]))
    compile_to(work / "classes", [ROOT / "practice-client/smoke/CombatCasesProbe.java"], cp)
    jar(work / "combat-cases.jar", work / "classes")


async def defender(work, build, port, hidden, perturb):
    game = work / "runtime/defender"
    for folder in ("mods", "config/pvp-practice", "tmp", "home"):
        (game / folder).mkdir(parents=True, exist_ok=True)
    for source, target in [
        (work / "combat-cases.jar", game / "mods/combat-cases.jar"),
        (build / "practice-bridge.jar", game / "mods/practice-bridge.jar"),
        (build / "practice-module.jar", game / "config/pvp-practice/practice-module.jar"),
    ]:
        shutil.copyfile(source, target)
    (game / "config/pvp-practice/settings.properties").write_text(
        "blockhit.enabled=true\ncapture.enabled=false\nlink.enabled=false\n")
    (game / "config/splash.properties").write_text("enabled=false\n")
    (game / "options.txt").write_text("renderDistance:4\nmaxFps:35\nsoundCategory_master:0.01\n")
    counts = collections.Counter()
    proxy = None
    if hidden:
        proxy = await asyncio.start_server(
            lambda r, w: redact_equipment(r, w, port, counts, perturb), "127.0.0.1", 0)
        target = proxy.sockets[0].getsockname()[1]
    else:
        target = port
    args = [
        "-Xmx640M", "-Dhmc.lwjgl.update_sleep=1", "-Dpractice.test.forceRemote=true",
        "-Dpractice.coach.verify=true", f"-Dprobe.hidden={str(hidden).lower()}",
        f"-Dprobe.rtt={120 if hidden else 0}",
        "-Dlog4j.configurationFile=" + str(ROOT / ".cache/server-logging.xml"),
        "-javaagent:" + str(forge.HEADLESS), "-cp", os.pathsep.join(map(str, forge.stage_dependencies())),
        "net.minecraft.launchwrapper.Launch", "--username", PLAYER, "--version", "1.8.9",
        "--gameDir", str(game), "--assetsDir", str(forge.ASSETS), "--assetIndex", "1.8",
        "--uuid", "00000000-0000-0000-0000-000000000008", "--accessToken", "0",
        "--userProperties", "{}", "--userType", "legacy", "--tweakClass",
        "net.minecraftforge.fml.common.launcher.FMLTweaker", "--server", "127.0.0.1", "--port", str(target),
    ]
    log = work / "defender.log"
    previous_runtime, previous_forge_runtime = lab.RUNTIME, forge.RUNTIME
    lab.RUNTIME = forge.RUNTIME = work / "runtime"
    try:
        with log.open("w") as out:
            game_env = {"PATH": "/usr/bin:/bin", "LANG": "en_US.UTF-8", "TMPDIR": str(game / "tmp") + "/"}
            process = await asyncio.create_subprocess_exec(*forge.command(args, game), cwd=game,
                stdout=out, stderr=subprocess.STDOUT, env=game_env)
            try:
                if await asyncio.wait_for(process.wait(), timeout=160) != 0:
                    raise RuntimeError("Native defender failed. See " + str(log))
            finally:
                if process.returncode is None:
                    process.terminate()
                    await process.wait()
    finally:
        lab.RUNTIME, forge.RUNTIME = previous_runtime, previous_forge_runtime
        if proxy:
            proxy.close()
            await proxy.wait_closed()
    return log, counts


def verify(work, log, redacted, hidden, arrows=False):
    text = log.read_text()
    identifier = re.search(r"Starting (m\d+):.*Rod diamond", text)
    assert identifier and "[Cases] DONE errors=0" in text, log
    replay = work / "runtime/replays" / (identifier[1] + ".jsonl")
    with replay.open() as rows:
        events = [json.loads(row) for row in rows]
    melee = [e for e in events if e["type"] == "melee_damage" and e["target"] == PLAYER]
    # The last six strikes hold one continuous guard against a Sharpness II sword.
    # The hidden-equipment proxy conceals the enchantment from the defender.
    sustained = melee[-6:]
    assert len(sustained) == 6 and all(e["blocked"] and e["noncrit_raw_damage"] == 10.5 for e in sustained), sustained
    assert sum(e["health_loss"] <= .015 for e in sustained) == 3, sustained
    assert 0 < sustained[3]["health_loss"] < 1.15 and abs(sustained[4]["health_loss"] - 1.15) < .015, sustained
    hits = [e for e in melee[:-6] if e["health_loss"] > .015]
    records = list(re.finditer(r"\[CombatCoach\] block (\d+) (\w+) (\w+) ([\d.E-]+)", text))
    labels = [(int(r[1]), r[2], float(r[4])) for r in records]
    presentations = {}
    for index, record in enumerate(records):
        end = records[index + 1].start() if index + 1 < len(records) else len(text)
        detail = text[record.end():end]
        presentations[int(record[1])] = (
            re.findall(r"\[CombatCoach\] block chime \d+ (server|local)", detail),
            re.findall(r"\[Cases\] sound \d+ 1\.6\s", detail),
        )
    chimes = [(int(t), source) for t, source in re.findall(r"\[CombatCoach\] block chime (\d+) (server|local)", text)]
    sounds = [(int(t), float(pitch)) for t, pitch in re.findall(r"\[Cases\] sound (\d+) ([\d.]+)", text)]
    sent = [e["time"] for e in events if e["type"] == "blocked_damage" and e["target"] == PLAYER and e["sound_sent"]]
    heard = [t for t, pitch in sounds if abs(pitch - 100 / 63) < .001]
    assert len(sent) == len(heard) and all(-20 <= t - s <= 250 for s, t in zip(sent, heard)), (sent, heard)
    sustained_start = sustained[0]["time"] - 20
    sustained_end = sustained[-1]["time"] + 450
    sustained_notes = [(t, label, loss) for t, label, loss in labels
                       if sustained_start <= t <= sustained_end and label == "BLOCKED"]
    sustained_chimes = [t for t, _ in chimes if sustained_start <= t <= sustained_end]
    sustained_sounds = [t for t, _ in sounds if sustained_start <= t <= sustained_end]
    assert len(sustained_notes) == len(sustained_chimes) == len(sustained_sounds) == len(sustained), (
        sustained_notes, sustained_chimes, sustained_sounds)
    for hit, (t, _, loss), chime, sound in zip(sustained, sustained_notes, sustained_chimes, sustained_sounds):
        assert -20 <= t - hit["time"] <= 450 and abs(loss - 1.15) < .015 and abs(t - chime) < 20, (hit, t, loss, chime)
        assert -20 <= sound - hit["time"] <= 450, (hit, sound)
    if hidden:
        assert any(int(r[1]) >= sustained_start and r.group(2, 3) == ("BLOCKED", "true") for r in records)
    late_chimes = [t for t, _ in chimes if t > sustained_end]
    if arrows:
        arrows_seen = [(int(t), label) for t, label in re.findall(r"\[CombatCoach\] arrow (\d+) (\w+)", text)]
        assert [label for _, label in arrows_seen] == ["BLOCKED", "UNBLOCKED"], arrows_seen
        assert len(late_chimes) == 1 and abs(late_chimes[0] - arrows_seen[0][0]) < 20, late_chimes
        arrow_damage = [e for e in events if e["type"] == "health_damage" and e["source"] == "arrow"
                        and e["target"] == PLAYER and e["time"] > sustained_end]
        assert len(arrow_damage) == 2, arrow_damage
        for (verdict_time, _), event in zip(arrows_seen, arrow_damage):
            assert -20 <= verdict_time - event["time"] <= 450, (verdict_time, event)
        # Identical projectile launches and armor. The server reports HP (not
        # pixels); applying the diamond armor factor to (raw + 1) / 2 gives this.
        assert abs(arrow_damage[0]["amount"] - (arrow_damage[1]["amount"] + .2) / 2) < .015, arrow_damage
    else:
        assert not late_chimes, late_chimes
    matched = {}
    for t, label, loss in labels:
        eligible = [e for e in hits if e["hit_index"] not in matched and -20 <= t - e["time"] <= 450
                    and abs(e["health_loss"] - loss) < .015]
        if eligible:
            # Updates grade in impact order. Two rod follow-ups can have the same
            # loss and the first verdict can land after the second server hit.
            e = eligible[0]
            matched[e["hit_index"]] = (t, label)
    totals = collections.Counter()
    for e in hits:
        t, label = matched.get(e["hit_index"], (0, "UNPAIRED"))
        rod = e["immune_before"] and e["previous_damage"] == 0
        kind = ("rod crit" if e["critical"] else "rod sword") if rod else (
            "crit upgrade" if e["immune_before"] and e["critical"] else "fresh crit" if e["critical"] else "melee")
        truth = "BLOCKED" if e["blocked"] else "UNBLOCKED"
        totals[kind, label, truth] += 1
        if label == "BLOCKED" and truth == label:
            source, local_audio = presentations[t]
            assert len(source) == 1, (e, source)
            assert len(local_audio) == (1 if source[0] == "local" else 0), (e, source)
    print(f"{'Hidden' if hidden else 'Visible'} equipment, {identifier[1]}. Results in {work}", flush=True)
    for row, total in sorted(totals.items()):
        print(*row, total, flush=True)
    assert len(matched) >= len(hits) - 2 and len(hits) >= 25, (len(matched), len(hits))
    assert all(label in (truth, "UNCERTAIN", "UNPAIRED") for _, label, truth in totals), totals
    for kind in ("fresh crit", "crit upgrade", "rod crit", "rod sword"):
        for verdict in ("BLOCKED", "UNBLOCKED"):
            assert totals[kind, verdict, verdict] > 0, (kind, verdict)
    assert any(e["type"] == "rod_trick" for e in events), "missing complete rod trick"
    assert sum(abs(pitch - 1.6) < .001 for _, pitch in sounds) == sum(source == "local" for _, source in chimes)
    frames = re.search(r"\[Cases\] hearts (\d+) (\d+) (\d+)", text)
    assert frames and int(frames[1]) > 0 and int(frames[2]) == 0 and int(frames[3]) > 0, frames
    if hidden:
        assert redacted > 0
        assert re.search(r"\[CombatCoach\] weapon calibration \d+ \[[^\]]*\b5\.0\b", text)
        assert any(e["noncrit_raw_damage"] == 13 and e["blocked"] and
                   matched.get(e["hit_index"], (0, ""))[1] == "BLOCKED" for e in hits)
    else:
        assert any(source == "server" for _, source in chimes), "missing server chime deduplication"
    print("PASS melee verdicts, critical/rod upgrades, sustained-guard chimes, absorption and current-health HUD", flush=True)


async def run_case(build, offset, hidden, perturb, arrows):
    parent = ROOT / "build/blockhit-smoke"
    parent.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="hidden-" if hidden else "visible-", dir=parent))
    prepare_fixture(work, build)
    with (work / "manager.log").open("w") as out:
        command = [sys.executable, __file__, "--fixture", str(work),
                   "--build-dir", str(build), "--port-offset", str(offset)]
        if arrows:
            command.append("--arrows")
        manager = subprocess.Popen(command, cwd=ROOT, stdout=out, stderr=subprocess.STDOUT)
    old_runtime = lab.RUNTIME
    lab.RUNTIME = work / "runtime"
    try:
        deadline = time.monotonic() + 100
        while time.monotonic() < deadline and manager.poll() is None:
            status = lab.RUNTIME / "status.json"
            if status.exists() and "LabSlot0" in json.loads(status.read_text())["players"]:
                break
            await asyncio.sleep(.3)
        else:
            raise RuntimeError("Isolated bot did not start. See " + str(work / "manager.log"))
        lab.send("op LabSlot0")
        log, counts = await defender(work, build, 25569 + offset, hidden, perturb)
        (work / "redacted-equipment.txt").write_text(str(counts["redacted"]) + "\n")
        if perturb:
            assert counts["hurt_reordered"] >= 6 and counts["velocity_skipped"] >= 6, (work, counts)
            print(f"Perturbed packet delivery in {work}: {dict(counts)}", flush=True)
        verify(work, log, counts["redacted"], hidden, arrows)
    finally:
        lab.RUNTIME = old_runtime
        if manager.poll() is None:
            manager.terminate()
            await asyncio.to_thread(manager.wait, 20)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-dir", type=Path, default=ROOT / "build/practice-client")
    parser.add_argument("--port-offset", type=int, default=420)
    parser.add_argument("--case", choices=("visible", "hidden", "both"), default="both")
    parser.add_argument("--perturb", action="store_true",
                        help="Reorder hurt and bar updates and drop own velocity during the hidden-gear sustained hold")
    parser.add_argument("--arrows", action="store_true", help="Also verify two native arrow hits, one blocked and one open")
    parser.add_argument("--fixture", type=Path, help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.port_offset < 20 or args.port_offset > 39000:
        parser.error("Use an isolated port offset from 20 to 39000")
    build = args.build_dir.resolve()
    if args.fixture:
        serve_fixture(args.fixture, build, args.port_offset, args.arrows)
    else:
        for name in ("visible", "hidden") if args.case == "both" else (args.case,):
            asyncio.run(run_case(build, args.port_offset, name == "hidden", args.perturb and name == "hidden", args.arrows))


if __name__ == "__main__":
    main()
