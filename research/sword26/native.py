"""Run actual stock clients and a private vanilla server with the input/referee agent."""
import argparse
import asyncio
import zipfile
import hashlib
import json
import math
import os
from pathlib import Path
import random
import shutil
import socket
import subprocess
import tempfile
import time
import uuid

from setup import BUILD, SRC, jdk, compile_agent, setup

PORT = 25663
BOT_NAMES = ["SwordLabA", "SwordLabB"]


async def wait_for(check, procs, seconds, reason):
    deadline = time.monotonic() + seconds
    while not check():
        for proc, logfile in procs:
            if proc.poll() is not None:
                raise RuntimeError(f"Process stopped. See {logfile}")
        if time.monotonic() > deadline:
            raise TimeoutError(reason)
        await asyncio.sleep(.15)


async def proxy(reader, writer, latency):
    try:
        remote, out = await asyncio.open_connection("127.0.0.1", PORT)
    except OSError:
        writer.close()
        return

    async def pipe(src, dst):
        queue = asyncio.Queue(maxsize=512)
        async def read():
            while True:
                data = await src.read(65536)
                await queue.put((time.monotonic() + latency[0]/2000, data))
                if not data:
                    return
        async def send():
            while True:
                due, data = await queue.get()
                await asyncio.sleep(max(0, due-time.monotonic()))
                if not data:
                    return
                dst.write(data)
                await dst.drain()
        await asyncio.gather(read(), send())

    tasks = [asyncio.create_task(pipe(reader, out)), asyncio.create_task(pipe(remote, writer))]
    try:
        await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
    finally:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        out.close()
        writer.close()


async def run(args):
    if not (BUILD/"classpath").is_file():
        setup()
    compile_agent()
    if not args.weights.is_file():
        raise ValueError(f"Missing trained weights at {args.weights}")
    # Require this project's previously accepted server EULA or an explicit local acceptance.
    server = BUILD/"native-server"
    server.mkdir(exist_ok=True)
    accepted = SRC.parents[1]/"runtime/server/eula.txt"
    if not (server/"eula.txt").exists() and accepted.is_file():
        shutil.copyfile(accepted,server/"eula.txt")
    if not (server/"eula.txt").exists() or "eula=true" not in (server/"eula.txt").read_text():
        raise SystemExit(f"Read https://aka.ms/MinecraftEULA and set eula=true in {server}/eula.txt if you agree.")
    if not args.human:
        for port in (PORT, PORT+1, PORT+2):
            with socket.socket() as probe:
                probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
                probe.bind(("127.0.0.1",port))
    names=[BOT_NAMES[0],args.human or BOT_NAMES[1]]
    allowed=[{"name":name,"uuid":str(uuid.UUID(bytes=hashlib.md5(("OfflinePlayer:"+name).encode()).digest(),version=3))} for name in names]
    (server/"whitelist.json").write_text(json.dumps(allowed))
    (server/"server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={PORT}\nonline-mode=false\nenforce-secure-profile=false\n"
        "level-name=court\nlevel-type=minecraft:flat\ndifficulty=normal\nspawn-protection=0\n"
        "view-distance=3\nsimulation-distance=3\nsync-chunk-writes=false\nmax-players=4\n")
    run_dir=Path(tempfile.mkdtemp(prefix="native-",dir=BUILD))
    print(f"Round recordings: {run_dir}",flush=True)
    procs=[]
    handles=[]
    def launch(name, cmd, cwd):
        logfile=run_dir/(name+".log")
        handle=logfile.open("w")
        handles.append(handle)
        proc=subprocess.Popen(list(map(str,cmd)),cwd=cwd,stdin=subprocess.PIPE,stdout=handle,stderr=subprocess.STDOUT)
        procs.append((proc,logfile))
        return proc
    agent="-javaagent:"+str(BUILD/"agent.jar")
    java=jdk()/"java"
    latency=[0]
    servers=[]
    try:
        for port in (PORT+1, PORT+2):
            servers.append(await asyncio.start_server(lambda r,w:proxy(r,w,latency),"127.0.0.1",port))
        with zipfile.ZipFile(BUILD/"server.jar") as bundle:
            for entry in bundle.infolist():
                if entry.filename.startswith(("META-INF/versions/","META-INF/libraries/")) and entry.filename.endswith(".jar"):
                    dest=server/entry.filename.removeprefix("META-INF/")
                    if not dest.is_file() or dest.stat().st_size!=entry.file_size:
                        dest.parent.mkdir(parents=True,exist_ok=True)
                        dest.write_bytes(bundle.read(entry))
        server_cp=os.pathsep.join(str(p) for p in [BUILD/"agent.jar",*sorted((server/"versions").rglob("*.jar")),*sorted((server/"libraries").rglob("*.jar"))])
        launch("server",[java,"-Xmx1G",agent,"-Dsword.run="+str(run_dir),"-cp",server_cp,"net.minecraft.server.Main","nogui"],server)
        await wait_for(lambda:(run_dir/"ready").exists(),procs,100,"Server startup timed out")
        for seat in range(1 if args.human else 2):
            name=BOT_NAMES[seat];folder=BUILD/name
            folder.mkdir(exist_ok=True)
            (folder/"options.txt").write_text("onboardAccessibility:false\npauseOnLostFocus:false\ntutorialStep:none\nrenderDistance:3\nsimulationDistance:5\nenableVsync:false\nmaxFps:50\nmusic:0.0\nsoundCategory_master:0.0\n")
            launch(name,[java,"-Xmx1200M","-XstartOnFirstThread","--enable-native-access=ALL-UNNAMED","--add-exports","java.base/jdk.internal.misc=ALL-UNNAMED",
                agent,"-Dsword.control="+str(run_dir/("AB"[seat])),"-cp",(BUILD/"classpath").read_text(),"net.minecraft.client.main.Main",
                "--username",name,"--version","26.3","--gameDir",folder,"--assetsDir",BUILD/"assets","--assetIndex","34",
                "--accessToken","0","--width","360","--height","240","--quickPlayMultiplayer",f"127.0.0.1:{PORT+seat+1}"],folder)
        names=[BOT_NAMES[0],args.human or BOT_NAMES[1]]
        if args.human:
            import re
            if not re.fullmatch(r"[A-Za-z0-9_]{1,16}",args.human):raise ValueError("Enter your Java player name")
            print(f"Join Java 26.3 Multiplayer at 127.0.0.1:{PORT} as {args.human}.",flush=True)
        seed=args.seed;rng=random.Random(seed)
        def joined():
            log=(run_dir/"server.log").read_text(errors="replace")
            return all(n+" joined the game" in log for n in names)
        await wait_for(joined,procs,3600 if args.human else 120,"Client join timed out")
        await asyncio.sleep(2)
        metadata={"weights_sha256":hashlib.sha256(args.weights.read_bytes()).hexdigest(),"seed":seed,"names":names,"version":"26.3","pairs":args.pairs,"rtts":args.rtts}
        (run_dir/"metadata.json").write_text(json.dumps(metadata,indent=2))
        results=[]
        for rtt in args.rtts:
            latency[0]=rtt
            for pair in range(args.pairs):
                angle=rng.uniform(-math.pi,math.pi);gap=rng.uniform(3.5,8)
                arms=[(0,"learned")] if args.human else [(seat,policy) for seat in (0,1) for policy in ("learned","wtap")]
                rng.shuffle(arms)
                for seat,policy in arms:
                    idx=f"round-{len(results):03d}"
                    lineup=["wtap","wtap"]
                    if policy=="learned":lineup[seat]=str(args.weights.resolve())
                    fields=[idx,*names,str(angle),str(gap),*lineup]
                    tmp=run_dir/"request.tmp";tmp.write_text("\t".join(fields));tmp.replace(run_dir/"request")
                    result=run_dir/(idx+".result")
                    await wait_for(result.exists,procs,70,"Native round failed to finish")
                    a,b,t=map(float,result.read_text().split())
                    row=dict(id=idx,rtt=rtt,pair=pair,seat=seat,policy=policy,hp=a if seat==0 else b,opponent_hp=b if seat==0 else a,ticks=t,angle=angle,gap=gap)
                    results.append(row);(run_dir/"results.json").write_text(json.dumps(results,indent=2))
                    print(row,flush=True)
                    await asyncio.sleep(1)
    finally:
        for listener in servers:listener.close()
        for proc,_ in reversed(procs):
            if proc.poll() is None:proc.terminate()
        for proc,_ in procs:
            try:proc.wait(8)
            except subprocess.TimeoutExpired:proc.kill();proc.wait()
        for h in handles:h.close()


if __name__=="__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--weights",type=Path,default=SRC/"champion.net")
    parser.add_argument("--human")
    parser.add_argument("--pairs",type=int,default=4)
    parser.add_argument("--seed",type=int,default=91621)
    parser.add_argument("--rtts",type=int,nargs="+",default=[0,100])
    args=parser.parse_args()
    if args.human:args.rtts=[0]
    asyncio.run(run(args))
