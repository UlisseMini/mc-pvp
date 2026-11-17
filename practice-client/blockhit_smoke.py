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


def serve_fixture(work, build, offset):
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


async def redact_equipment(client, downstream, port, counts):
    upstream, server = await asyncio.open_connection("127.0.0.1", port)

    async def upstream_packets():
        while chunk := await client.read(65536):
            server.write(chunk)
            await server.drain()

    async def downstream_packets():
        play = False
        while True:
            frame = await read_frame(upstream)
            packet, offset = read_varint(frame)
            if play and packet == 4:
                filtered = hide_sword_tag(frame, offset)
                counts["redacted"] += frame != filtered
                frame = filtered
            elif not play and packet == 2:
                play = True
            elif not play and packet == 3:
                raise ValueError("The test proxy expects the lab's uncompressed protocol")
            downstream.write(varint(len(frame)) + frame)
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


async def defender(work, build, port, hidden):
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
            lambda r, w: redact_equipment(r, w, port, counts), "127.0.0.1", 0)
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
    return log, counts["redacted"]


def verify(work, log, redacted, hidden):
    text = log.read_text()
    identifier = re.search(r"Starting (m\d+):.*Rod diamond", text)
    assert identifier and "[Cases] DONE errors=0" in text, log
    replay = work / "runtime/replays" / (identifier[1] + ".jsonl")
    with replay.open() as rows:
        events = [json.loads(row) for row in rows]
    hits = [e for e in events if e["type"] == "melee_damage" and e["target"] == PLAYER and e["health_loss"] > .015]
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
    matched = {}
    for t, label, loss in labels:
        eligible = [e for e in hits if e["hit_index"] not in matched and -20 <= t - e["time"] <= 450
                    and abs(e["health_loss"] - loss) < .015]
        if eligible:
            e = min(eligible, key=lambda e: abs(t - e["time"] - (110 if hidden else 50)))
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
            if source[0] == "server":
                assert sum(abs(stamp - t) <= 200 and abs(pitch - 100 / 63) < .001 for stamp, pitch in sounds) == 1
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
    print("PASS melee verdicts, critical/rod upgrades, chimes and current-health HUD", flush=True)


async def run_case(build, offset, hidden):
    parent = ROOT / "build/blockhit-smoke"
    parent.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="hidden-" if hidden else "visible-", dir=parent))
    prepare_fixture(work, build)
    with (work / "manager.log").open("w") as out:
        manager = subprocess.Popen([sys.executable, __file__, "--fixture", str(work),
                                    "--build-dir", str(build), "--port-offset", str(offset)],
                                   cwd=ROOT, stdout=out, stderr=subprocess.STDOUT)
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
        log, redacted = await defender(work, build, 25569 + offset, hidden)
        (work / "redacted-equipment.txt").write_text(str(redacted) + "\n")
        verify(work, log, redacted, hidden)
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
    parser.add_argument("--fixture", type=Path, help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.port_offset < 20 or args.port_offset > 39000:
        parser.error("Use an isolated port offset from 20 to 39000")
    build = args.build_dir.resolve()
    if args.fixture:
        serve_fixture(args.fixture, build, args.port_offset)
    else:
        for name in ("visible", "hidden") if args.case == "both" else (args.case,):
            asyncio.run(run_case(build, args.port_offset, name == "hidden"))


if __name__ == "__main__":
    main()
