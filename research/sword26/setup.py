"""Install the pinned Mojang runtime into the local build cache on macOS."""
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import subprocess
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
    installed = sorted(BUILD.glob("jdk-25*/Contents/Home/bin/javac"))
    if not installed and os.environ.get("JAVA_HOME"):
        installed = [Path(os.environ["JAVA_HOME"]) / "bin/javac"]
    if not installed:
        raise SystemExit("Install JDK 25 and set JAVA_HOME, or place a JDK 25 macOS bundle in build/sword26.")
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
    classes = BUILD / "classes"
    classes.mkdir(exist_ok=True)
    subprocess.run([jdk()/"javac", "-cp", (BUILD/"classpath").read_text(), "-d", classes, *sorted(SRC.glob("*.java"))], check=True)
    manifest = BUILD/"agent-manifest"
    manifest.write_text("Manifest-Version: 1.0\nPremain-Class: sword.Agent\n\n")
    subprocess.run([jdk()/"jar", "cfm", BUILD/"agent.jar", manifest, "-C", classes, "."], check=True)


if __name__ == "__main__":
    setup()
    compile_agent()
