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
    if platform.system()=="Linux":return Path("/usr/lib/jvm/java-25-openjdk-arm64/bin")
    installed = sorted(BUILD.glob("jdk-25*/Contents/Home/bin/javac"))
    if not installed and os.environ.get("JAVA_HOME"):
        installed = [Path(os.environ["JAVA_HOME"]) / "bin/javac"]
    if not installed:
        if platform.system()!="Darwin" or platform.machine()!="arm64":
            raise SystemExit("This launcher targets Apple Silicon macOS. Set JAVA_HOME to a JDK 25 installation.")
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


if __name__ == "__main__":
    setup()
    compile_agent()
