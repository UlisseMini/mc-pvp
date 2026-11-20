"""Install the pinned Mojang runtime into the local build cache on macOS."""
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import subprocess
import platform
import tarfile
import tempfile
import urllib.request

SRC = Path(__file__).resolve().parent
BUILD = SRC.parents[1] / "build/sword26"
GAME = Path.home() / "Library/Application Support/minecraft"
VERSION = "26.3"
CLIENT_HASH = "e877b6a07acd633fb3bb475002175cec036e7b87"
SERVER_HASH = "33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c"


def fetch(spec, dest, installed=None):
    def valid(path):
        return path and path.is_file() and hashlib.sha1(path.read_bytes()).hexdigest() == spec["sha1"]

    if valid(dest):
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    if valid(installed):
        try:
            os.link(installed, dest)
            return dest
        except OSError:
            pass
    with tempfile.NamedTemporaryFile(dir=dest.parent, delete=False) as f:
        tmp = Path(f.name)
    try:
        subprocess.run(["curl", "-fsSL", "--retry", "3", spec["url"], "-o", tmp], check=True)
        if not valid(tmp):
            raise ValueError(f"Invalid download for {dest.name}")
        tmp.replace(dest)
    finally:
        tmp.unlink(missing_ok=True)
    return dest


def jdk():
    if platform.system()=="Linux":
        configured=os.environ.get("JAVA_HOME")
        if configured:return Path(configured)/"bin"
        installed=sorted(Path("/usr/lib/jvm").glob("java-25*/bin/javac"))
        if installed:return installed[0].parent
        raise SystemExit("Set JAVA_HOME to a JDK 25 installation.")
    installed = sorted(BUILD.glob("jdk-25*/Contents/Home/bin/javac"))
    if not installed and os.environ.get("JAVA_HOME"):
        installed = [Path(os.environ["JAVA_HOME"]) / "bin/javac"]
    if not installed:
        if platform.system()!="Darwin" or platform.machine()!="arm64":
            raise SystemExit("This launcher targets Apple Silicon macOS. Set JAVA_HOME to a JDK 25 installation.")
        BUILD.mkdir(parents=True,exist_ok=True)
        url="https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5.1%2B2/OpenJDK25U-jdk_aarch64_mac_hotspot_25.0.5.1_2.tar.gz"
        package=BUILD/"jdk.tar.gz"
        digest="248353ea41be39d5d2c1c67c7ce0ae4d1668966aacd0c5308464bc1be42f677b"
        if not package.exists() or hashlib.sha256(package.read_bytes()).hexdigest()!=digest:
            subprocess.run(["curl","-fsSL","--retry","3",url,"-o",package],check=True)
        if hashlib.sha256(package.read_bytes()).hexdigest()!=digest:
            raise ValueError("JDK archive hash mismatch")
        with tarfile.open(package) as archive:
            archive.extractall(BUILD,filter="data")
        installed=sorted(BUILD.glob("jdk-25*/Contents/Home/bin/javac"))
    return installed[0].parent


def setup():
    BUILD.mkdir(parents=True, exist_ok=True)
    manifest = json.load(urllib.request.urlopen("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
    item = next(v for v in manifest["versions"] if v["id"] == VERSION)
    meta = json.loads(fetch(item, BUILD / "version.json").read_text())
    assert meta["downloads"]["client"]["sha1"] == CLIENT_HASH and meta["downloads"]["server"]["sha1"] == SERVER_HASH
    client = fetch(meta["downloads"]["client"], BUILD / "client.jar", GAME / f"versions/{VERSION}/{VERSION}.jar")
    fetch(meta["downloads"]["server"], BUILD / "server.jar")
    jobs = []
    libs = [client]
    for lib in meta["libraries"]:
        if "rules" in lib and not any(r["action"] == "allow" and r.get("os", {}).get("name", "osx") == "osx" for r in lib["rules"]):
            continue
        entry = lib["downloads"]["artifact"]
        path = Path("libraries") / entry["path"]
        jobs.append((entry, BUILD/path, GAME/path))
        libs.append(BUILD/path)
    idx = meta["assetIndex"]
    path = Path("assets/indexes") / (idx["id"] + ".json")
    assets = json.loads(fetch(idx, BUILD/path, GAME/path).read_text())
    for h in {asset["hash"] for asset in assets["objects"].values()}:
        key = f"{h[:2]}/{h}"
        path = Path("assets/objects") / key
        jobs.append(({"sha1": h, "url": f"https://resources.download.minecraft.net/{key}"}, BUILD/path, GAME/path))
    with concurrent.futures.ThreadPoolExecutor(12) as pool:
        list(pool.map(lambda job: fetch(*job), jobs))
    (BUILD / "classpath").write_text(os.pathsep.join(map(str, libs)))
    print("Verified Minecraft", VERSION, flush=True)


def compile_agent():
    sources=sorted(SRC.glob("*.java"))
    digest=hashlib.sha256(CLIENT_HASH.encode())
    for source in sources:
        digest.update(source.name.encode());digest.update(source.read_bytes())
    target=BUILD/"agents"/(digest.hexdigest()+".jar")
    target.parent.mkdir(exist_ok=True)
    if target.exists():return target
    with tempfile.TemporaryDirectory(dir=target.parent) as tmp:
        folder=Path(tmp);classes=folder/"classes";classes.mkdir()
        subprocess.run([jdk()/"javac","-cp",(BUILD/"classpath").read_text(),"-d",classes,*sources],check=True)
        manifest=folder/"manifest"
        manifest.write_text("Manifest-Version: 1.0\nPremain-Class: sword.Agent\n\n")
        jar=folder/"agent.jar"
        subprocess.run([jdk()/"jar","cfm",jar,manifest,"-C",classes,"."],check=True)
        jar.replace(target)
    return target


def compile_sim(revision=None, expected=None):
    names = ("Fighter", "Model", "FastDuel", "FlatPhysics", "TreePolicy", "Train",
             "PolicyGradient", "League", "Refine", "Starts", "Cup", "Controls",
             "Grade", "Maintenance", "ForkAudit", "KernelReplay", "KernelBench", "JumpFork", "ContactParity", "CrossPlay", "BranchImprove", "Checks")
    if revision:
        root = SRC.parents[1]
        revision=subprocess.check_output(["git","rev-parse","--verify","--end-of-options",revision+"^{commit}"],
                                         cwd=root,text=True).strip()
        available = subprocess.check_output(
            ["git", "ls-tree", "--name-only", revision+":research/sword26"], cwd=root, text=True).splitlines()
        sources = {name+".java":subprocess.check_output(
            ["git","show",revision+":research/sword26/"+name+".java"],cwd=root)
            for name in names if name+".java" in available}
    else:
        sources = {name+".java":(SRC / (name+".java")).read_bytes() for name in names}
    for name, registered in (expected or {}).items():
        if hashlib.sha256(sources[name]).hexdigest()!=registered:
            raise ValueError(f"The registered {name} hash differs")
    digest = hashlib.sha256()
    for name, contents in sources.items():
        digest.update(name.encode())
        digest.update(contents)
    target = BUILD / "simulator" / (digest.hexdigest()+".jar")
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        return target
    with tempfile.TemporaryDirectory(dir=target.parent) as tmp:
        folder = Path(tmp)
        classes = folder / "classes"
        classes.mkdir()
        for name,contents in sources.items():
            (folder/name).write_bytes(contents)
        subprocess.run([jdk()/"javac", "-d", classes, *[folder/name for name in sources]], check=True)
        jar = folder / "simulator.jar"
        subprocess.run([jdk()/"jar", "cf", jar, "-C", classes, "."], check=True)
        jar.replace(target)
    return target


def registered_sim(plan):
    expected={"FastDuel.java":plan["training_source_hash"]}
    if "flat_physics_hash" in plan:
        expected["FlatPhysics.java"]=plan["flat_physics_hash"]
    return compile_sim(plan.get("training_revision"),expected)


if __name__ == "__main__":
    setup()
    compile_agent()
