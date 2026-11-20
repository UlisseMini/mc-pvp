"""Run actual stock clients and a private vanilla server with the input/referee agent."""
import argparse
import asyncio
import zipfile
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import random
import shutil
import socket
import signal
import subprocess
import tempfile
import time
import uuid

from replay import build as build_replay
from setup import BUILD, SRC, jdk, compile_agent, setup

PORT = int(os.environ.get("SWORD26_PORT",25663))
WORKER = Path(os.environ.get("SWORD26_WORKER_HOME", BUILD))
BOT_NAMES = ["SwordLabA", "SwordLabB"]


def save_json(path, value):
    pending = path.with_suffix(".tmp")
    pending.write_text(json.dumps(value,indent=2))
    pending.replace(path)


def load_openings(path, rtts, pairs):
    if path is None:
        return {}
    openings = {}
    for row in json.loads(path.read_text()):
        key = row["rtt"], row["pair"]
        pose = row["angle"], row["gap"]
        if not all(math.isfinite(v) for v in pose) or not 3.5 <= pose[1] <= 8:
            raise ValueError(f"Invalid opening geometry at {key}")
        if openings.setdefault(key, pose) != pose:
            raise ValueError(f"Inconsistent opening geometry at {key}")
    missing = {(rtt, pair) for rtt in rtts for pair in range(pairs)} - openings.keys()
    if missing:
        raise ValueError(f"Missing recorded openings {sorted(missing)}")
    return openings


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
    if args.showcase and (args.human or args.clock or args.speed!=1):
        raise ValueError("Showcase recording uses ordinary bot fights at normal speed")
    if args.clock and (args.human or args.speed!=1 or len(args.rtts)!=1 or args.rtts[0]%100):
        raise ValueError("Use two bots and one RTT in whole 100 ms increments for a fixed tick clock")
    if args.landmark and (args.human or args.landmark<1):
        raise ValueError("Landmark comparisons require two bots and a positive hit count")
    if args.human and (not re.fullmatch(r"[A-Za-z0-9_]{1,16}",args.human) or args.human in BOT_NAMES):
        raise ValueError("Enter your own Java player name")
    if args.pairs<1 or any(rtt<0 for rtt in args.rtts):raise ValueError("Provide positive pairs and nonnegative RTTs")
    openings = load_openings(args.openings_from, args.rtts, args.pairs)
    if platform.system()=="Darwin":
        from headless import run_in_guest
        params=["--weights",str(args.weights.resolve()),"--pairs",str(args.pairs),"--seed",str(args.seed),"--speed",str(args.speed),
                "--rtts",*map(str,args.rtts),"--opponents",*args.opponents,"--extra-weights",*[str(w.resolve()) for w in args.extra_weights]]
        if args.showcase:params+=["--showcase"]
        if args.blind:params+=["--blind"]
        if args.landmark:params+=["--landmark",str(args.landmark)]
        if args.clock:params+=["--clock",args.clock]
        if args.openings_from:
            path = args.openings_from.resolve()
            if not any(path.is_relative_to(root) for root in (SRC, BUILD)):
                # The headless guest only mounts the source and build folders.
                manifests = BUILD / "opening-manifests"
                manifests.mkdir(parents=True, exist_ok=True)
                content = json.dumps([dict(rtt=r, pair=i, angle=a, gap=g)
                                      for (r, i), (a, g) in sorted(openings.items())]).encode()
                path = manifests / (hashlib.sha256(content).hexdigest() + ".json")
                with tempfile.NamedTemporaryFile(dir=manifests, delete=False) as tmp:
                    tmp.write(content)
                Path(tmp.name).replace(path)
            params+=["--openings-from",str(path)]
        if args.resume:params+=["--resume",str(args.resume.resolve())]
        if args.human:params+=["--human",args.human]
        run_in_guest(params)
    if platform.system()!="Linux":raise RuntimeError("A displayless Linux guest is required for native bots.")
    if not (BUILD/"classpath").is_file():
        setup()
    agent_jar=compile_agent()
    if not args.weights.is_file():
        raise ValueError(f"Missing trained weights at {args.weights}")
    # Require this project's previously accepted server EULA or an explicit local acceptance.
    worker = WORKER/"showcase" if args.showcase else WORKER
    width,height,fps = (854,480,60) if args.showcase else (360,240,50)
    server = worker/"native-server"
    server.mkdir(parents=True,exist_ok=True)
    accepted = SRC.parents[1]/"runtime/server/eula.txt"
    if args.showcase and not accepted.is_file():accepted=WORKER/"native-server/eula.txt"
    if not (server/"eula.txt").exists() and accepted.is_file():
        shutil.copyfile(accepted,server/"eula.txt")
    if not (server/"eula.txt").exists() or "eula=true" not in (server/"eula.txt").read_text():
        raise SystemExit(f"Read https://aka.ms/MinecraftEULA and set eula=true in {server}/eula.txt if you agree.")
    if args.speed<1 or args.speed>5 or (args.human and args.speed!=1):raise ValueError("Invalid game speed")
    for port in (PORT, PORT+1, PORT+2):
        with socket.socket() as probe:
            probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
            probe.bind(("127.0.0.1",port))
    names=[BOT_NAMES[0],args.human or BOT_NAMES[1]]
    allowed=[{"name":name,"uuid":str(uuid.UUID(bytes=hashlib.md5(("OfflinePlayer:"+name).encode()).digest(),version=3))} for name in names]
    (server/"whitelist.json").write_text(json.dumps(allowed))
    (server/"server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={PORT}\nonline-mode=false\nenforce-secure-profile=false\n"
        "white-list=true\nenforce-whitelist=true\n"
        "level-name=court\nlevel-type=minecraft:flat\ndifficulty=normal\nspawn-protection=0\n"
        "view-distance=3\nsimulation-distance=3\nsync-chunk-writes=false\nmax-players=4\n")
    run_dir=Path(tempfile.mkdtemp(prefix="native-",dir=BUILD))
    spool=Path(tempfile.mkdtemp(prefix="sword26-controls-"))
    print(f"Round recordings: {run_dir}",flush=True)
    procs=[]
    handles=[]
    def launch(name, cmd, cwd):
        logfile=run_dir/(name+".log")
        handle=logfile.open("w")
        handles.append(handle)
        proc=subprocess.Popen(list(map(str,cmd)),cwd=cwd,start_new_session=True,stdin=subprocess.PIPE,stdout=handle,stderr=subprocess.STDOUT)
        procs.append((proc,logfile))
        return proc
    agent="-javaagent:"+str(agent_jar)
    java=jdk()/"java"
    clock_flags=["-Dsword.clock="+args.clock] if args.clock else []
    from video import Capture, windows
    capture=None
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
        server_cp=os.pathsep.join(str(p) for p in [agent_jar,*sorted((server/"versions").rglob("*.jar")),*sorted((server/"libraries").rglob("*.jar"))])
        launch("server",[java,"-Xmx1G",agent,*clock_flags,"-Dsword.showcase="+str(args.showcase).lower(),f"-Dsword.speed={args.speed}","-Dsword.run="+str(run_dir),"-Dsword.spool="+str(spool),"-cp",server_cp,"net.minecraft.server.Main","nogui"],server)
        await wait_for(lambda:(run_dir/"ready").exists(),procs,100,"Server startup timed out")
        for seat in range(1 if args.human else 2):
            name=BOT_NAMES[seat];folder=worker/name
            folder.mkdir(exist_ok=True)
            (folder/"options.txt").write_text(f"onboardAccessibility:false\npauseOnLostFocus:false\ntutorialStep:none\nrenderDistance:3\nsimulationDistance:5\nenableVsync:false\ninactivityFpsLimit:\"minimized\"\nmaxFps:{fps}\nmusic:0.0\nsoundCategory_master:0.0\n"+('ao:false\nbiomeBlendRadius:0\ncloudRange:32\nrenderClouds:"false"\nparticles:2\nentityShadows:false\nmipmapLevels:0\ngamma:1.0\nguiScale:2\nchatVisibility:2\n' if args.showcase else ""))
            launch(name,["xvfb-run","-a","-s",f"-screen 0 {width+120}x{height+80}x24 -nolisten tcp","env","LIBGL_ALWAYS_SOFTWARE=1","GALLIUM_DRIVER=llvmpipe","LP_NUM_THREADS=1","ALSOFT_DRIVERS=null","SDL_VIDEO_DRIVER=x11","SDL_VIDEODRIVER=x11",java,"-Xmx1000M",f"-Dorg.lwjgl.system.SharedLibraryExtractPath=/tmp/sword26-lwjgl-{PORT}-{seat}","-Djava.awt.headless=true","--enable-native-access=ALL-UNNAMED","--add-exports","java.base/jdk.internal.misc=ALL-UNNAMED",
                agent,*clock_flags,"-Dsword.spool="+str(spool),f"-Dsword.speed={args.speed}","-Dsword.control="+str(spool/("AB"[seat])),"-Dsword.logs="+str(run_dir),"-cp",(BUILD/"linux-classpath").read_text(),"net.minecraft.client.main.Main",
                "--username",name,"--version","26.3","--gameDir",folder,"--assetsDir",BUILD/"assets","--assetIndex","34",
                "--accessToken","0","--width",str(width),"--height",str(height),"--quickPlayMultiplayer",f"127.0.0.1:{PORT if args.clock else PORT+seat+1}"],folder)
        names=[BOT_NAMES[0],args.human or BOT_NAMES[1]]
        if args.human:
            print(f"Join Java 26.3 Multiplayer at 127.0.0.1:{PORT} as {args.human}.",flush=True)
        seed=args.seed;rng=random.Random(seed)
        def joined():
            log=(run_dir/"server.log").read_text(errors="replace")
            return all(n+" joined the game" in log for n in names)
        await wait_for(joined,procs,3600 if args.human else 120,"Client join timed out")
        await asyncio.sleep(45 if args.showcase else 2)
        metadata={"agent":agent_jar.name,"backend":"Linux virtual X11 framebuffer with Mesa software rendering","weights_sha256":hashlib.sha256(args.weights.read_bytes()).hexdigest(),"seed":seed,"names":names,"version":"26.3","pairs":args.pairs,"rtts":args.rtts,"speed":args.speed,"blind":args.blind,"opponents":args.opponents,"extra_weights":[{"file":str(w),"sha256":hashlib.sha256(w.read_bytes()).hexdigest()} for w in args.extra_weights]}
        metadata["primary_file"]=args.weights.name
        metadata["referee_score_rule"]="first_lethal_contact"
        metadata["client_frame_policy"]="minimized"
        metadata["landmark"]=args.landmark
        metadata["clock"]=args.clock
        if args.openings_from:
            metadata["opening_geometry"] = [
                dict(rtt=rtt, pair=pair, angle=openings[rtt,pair][0], gap=openings[rtt,pair][1])
                for rtt in args.rtts for pair in range(args.pairs)]
        metadata["opponent_hashes"]={p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
                                     for p in args.opponents if p not in ("wtap","hold")}
        save_json(run_dir/"metadata.json",metadata)
        results=[]
        if args.resume:
            previous=json.loads((args.resume/"metadata.json").read_text())
            if previous.get("client_frame_policy","afk")!=metadata["client_frame_policy"]:raise ValueError("Resume requires the same client frame policy")
            if previous.get("clock")!=args.clock:raise ValueError("Resume requires the same game clock")
            if previous.get("opening_geometry")!=metadata.get("opening_geometry"):raise ValueError("Resume requires the same opening geometry")
            if previous.get("landmark",0)!=args.landmark:raise ValueError("Resume requires the same acquisition protocol")
            if previous.get("blind",False)!=args.blind or previous.get("opponent_hashes",{})!=metadata["opponent_hashes"]:
                raise ValueError("Resume requires the same blind setting and verified opponent weights")
            for key in ("weights_sha256","seed","names","version","pairs","rtts","speed","opponents","extra_weights"):
                if previous[key]!=metadata[key]:raise ValueError(f"Resume mismatch on {key}")
            results=json.loads((args.resume/"results.json").read_text())
            metadata["resumed_from"]=str(args.resume)
            metadata["previous_completed"]=len(results)
            save_json(run_dir/"metadata.json",metadata)
            for row in results:
                for file in args.resume.glob(row["id"]+"*"):
                    shutil.copyfile(file,run_dir/file.name)
            video_dir=run_dir/"videos"
            for row in results:
                if row.get("video")==f"videos/{row['id']}.mp4":
                    original=args.resume/row["video"]
                    if original.is_file():
                        video_dir.mkdir(exist_ok=True)
                        try:os.link(original,run_dir/row["video"])
                        except OSError:shutil.copyfile(original,run_dir/row["video"])
                    else:row.pop("video")
            save_json(run_dir/"results.json",results)
        video_sources=windows(run_dir) if not args.human and not args.clock and args.speed==1 else {}
        if video_sources:
            metadata["video"]={"format":"H.264","fps":fps,"width":width,"height":height,"view":"evaluated bot","speed":1}
            save_json(run_dir/"metadata.json",metadata)
        skip=len(results);scheduled=0
        if args.clock:
            pending=spool/"clock-start.tmp"
            pending.write_text(str(args.rtts[0]//100));pending.replace(spool/"clock-start")
        for rtt in args.rtts:
            latency[0]=rtt/args.speed
            for pair in range(args.pairs):
                angle=rng.uniform(-math.pi,math.pi);gap=rng.uniform(3.5,8)
                if openings:
                    angle,gap=openings[rtt,pair]
                candidates=[args.weights,*args.extra_weights]
                policies=[("learned" if i==0 else f"learned-{i}",str(w.resolve())) for i,w in enumerate(candidates)]
                policies.append(("wtap","wtap"))
                arms=[(0,"learned",str(args.weights.resolve()),"human")] if args.human else [
                    (seat,label,path,rival) for rival in args.opponents for seat in (0,1) for label,path in policies]
                if args.human and args.blind:arms.append((0,"wtap","wtap","human"))
                rng.shuffle(arms)
                for seat,policy,path,rival in arms:
                    scheduled+=1
                    if scheduled<=skip:
                        old=results[scheduled-1]
                        if (old["rtt"],old["pair"],old["seat"],old["policy"],old["rival"],old["angle"],old["gap"])!=(rtt,pair,seat,policy,rival,angle,gap):
                            raise ValueError("Previous round differs from replayed schedule")
                        continue
                    idx=f"round-{len(results):03d}"
                    lineup=[rival,rival];lineup[seat]=path
                    fields=[idx,*names,str(angle),str(gap),*lineup]
                    if args.landmark:fields.extend([str(seat),str(args.landmark)])
                    if names[seat] in video_sources:
                        try:capture=Capture(run_dir/"videos",idx,video_sources[names[seat]],fps)
                        except OSError as error:print(f"Video unavailable for {idx}: {error}",flush=True)
                    requested_at=time.monotonic()
                    tmp=run_dir/"request.tmp";tmp.write_text("\t".join(fields));tmp.replace(run_dir/"request")
                    result=run_dir/(idx+".result")
                    await wait_for(result.exists,procs,70,"Native round failed to finish")
                    a,b,t=map(float,result.read_text().split())
                    row=dict(id=idx,wall_seconds=time.monotonic()-requested_at,rtt=rtt,pair=pair,seat=seat,policy=policy,rival=rival,hp=a if seat==0 else b,opponent_hp=b if seat==0 else a,ticks=t,angle=angle,gap=gap)
                    if capture:
                        if capture.finish():row["video"]=f"videos/{idx}.mp4"
                        capture=None
                    results.append(row)
                    save_json(run_dir/"results.json",results)
                    print(f"Round {len(results)} complete." if args.blind else row,flush=True)
                    if args.clock!="fast":await asyncio.sleep(1)
    finally:
        if capture:capture.finish()
        for listener in servers:listener.close()
        for proc,_ in reversed(procs):
            if proc.poll() is None:os.killpg(proc.pid,signal.SIGTERM)
        for proc,_ in procs:
            try:proc.wait(8)
            except subprocess.TimeoutExpired:os.killpg(proc.pid,signal.SIGKILL);proc.wait()
        shutil.rmtree(spool,ignore_errors=True)
        for h in handles:h.close()
        if list(run_dir.glob("*.result")):print("Replay:",build_replay(run_dir),flush=True)


if __name__=="__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--showcase",action="store_true",help="Record 854×480, 60 fps ordinary fights in a separate daylight world")
    parser.add_argument("--weights",type=Path,default=SRC/"champion.net")
    parser.add_argument("--human")
    parser.add_argument("--pairs",type=int,default=4)
    parser.add_argument("--seed",type=int,default=91621)
    parser.add_argument("--rtts",type=int,nargs="+",default=[0,100])
    parser.add_argument("--extra-weights",type=Path,nargs="*",default=[])
    parser.add_argument("--opponents",nargs="+",default=["wtap"])
    parser.add_argument("--speed",type=int,default=1)
    parser.add_argument("--resume",type=Path)
    parser.add_argument("--openings-from",type=Path,help="Replay angle and distance by RTT and pair from a recorded results JSON or opening list")
    parser.add_argument("--blind",action="store_true",help="Mix the trained bot and the control against the same human")
    parser.add_argument("--landmark",type=int,default=0,help="Use the common W-tap opening until this many unanswered fresh hits after the defender begins pressing")
    parser.add_argument("--clock",choices=["realtime","fast"],help="Run the native clients and server on an ordered network tick schedule")
    args=parser.parse_args()
    args.opponents=[p if p in ("wtap","hold") else str(Path(p).resolve()) for p in args.opponents]
    if args.human:args.rtts=[0]
    asyncio.run(run(args))
