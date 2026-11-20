"""Keep real bot clients inside a displayless Linux VM on Apple Silicon."""
import concurrent.futures
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
from setup import BUILD, SRC, fetch, setup

VM = "sword26"


def linux_libs():
    meta=json.loads((BUILD/"version.json").read_text())
    libs=[BUILD/"client.jar"]
    jobs=[]
    for lib in meta["libraries"]:
        rules=lib.get("rules",[])
        if rules and not any(r["action"]=="allow" and r.get("os",{}).get("name","linux")=="linux" for r in rules):
            continue
        spec=lib["downloads"]["artifact"]
        if lib["name"].startswith("org.lwjgl:") and lib["name"].endswith(":natives-linux"):
            rel=spec["path"].replace("natives-linux.jar","natives-linux-arm64.jar")
            url="https://repo.maven.apache.org/maven2/"+rel
            path=BUILD/"libraries"/rel
            jobs.append(({"url":url,"sha1":None},path))
        else:
            path=BUILD/"libraries"/spec["path"]
            jobs.append((spec,path))
        libs.append(path)
    def download(job):
        spec,path=job
        if spec["sha1"] is None:
            spec["sha1"]=urllib.request.urlopen(spec["url"]+".sha1",timeout=20).read().decode().strip()
        fetch(spec,path)
    with concurrent.futures.ThreadPoolExecutor(8) as pool:list(pool.map(download,jobs))
    (BUILD/"linux-classpath").write_text(":".join(map(str,libs)))


def run_in_guest(args):
    if not shutil.which("limactl"):raise SystemExit("The headless launcher needs Lima (brew install lima).")
    if not (BUILD/"classpath").is_file():setup()
    if not (BUILD/"linux-classpath").exists():linux_libs()
    accepted=SRC.parents[1]/"runtime/server/eula.txt"
    dest=BUILD/"native-server/eula.txt"
    if not dest.exists() and accepted.is_file():
        dest.parent.mkdir(exist_ok=True)
        shutil.copyfile(accepted,dest)
    names=subprocess.check_output(["limactl","list","--format","{{.Name}}"],text=True).splitlines()
    if VM in names:
        subprocess.run(["limactl","start","--tty=false",VM],check=True)
    else:
        subprocess.run(["limactl","start","--tty=false","--name="+VM,"--vm-type=vz","--cpus=4","--memory=5","--disk=14","--containerd=none",
                        "--mount-only="+str(SRC),"--mount-only="+str(BUILD)+":w","template:ubuntu-lts"],check=True)
    subprocess.run(["limactl","shell","--workdir","/tmp",VM,"bash",str(SRC/"guest-setup.sh")],check=True)
    # Resolve relative paths while still in the user's working directory.
    os.execvp("limactl",["limactl","shell","--workdir",str(BUILD),VM,"python3",str(SRC/"native.py"),*args])
