"""Record one physical bot window at the native client's frame cap inside the headless guest."""
import os
from pathlib import Path
import re
import shutil
import subprocess


def windows(run):
    if not shutil.which("ffmpeg") or not shutil.which("xwininfo"):
        return {}
    sources = {}
    for proc in Path("/proc").glob("[0-9]*"):
        try:
            if (proc / "comm").read_text().strip() != "java":
                continue
            args = (proc / "cmdline").read_bytes().split(b"\0")
            if b"net.minecraft.client.main.Main" not in args or os.fsencode("-Dsword.logs="+str(run)) not in args:
                continue
            name = args[args.index(b"--username") + 1].decode()
            if name not in ("SwordLabA", "SwordLabB"):
                continue
            env = os.environ.copy()
            for item in (proc / "environ").read_bytes().split(b"\0"):
                key, _, value = item.partition(b"=")
                if key in (b"DISPLAY", b"XAUTHORITY"):
                    env[key.decode()] = os.fsdecode(value)
            info = subprocess.check_output(["xwininfo","-root","-tree"],env=env,text=True,timeout=3)
            window = re.search(r'^\s*(0x[0-9a-fA-F]+) "Minecraft [^"]*".*?"com\.mojang\.minecraft"',info,re.M)
            if window:
                sources[name] = (env, window[1])
        except (OSError, ValueError, subprocess.SubprocessError):
            continue
    return sources


class Capture:
    def __init__(self, directory, stem, source, fps=50):
        directory.mkdir(exist_ok=True)
        self.target = directory / (stem + ".mp4")
        self.partial = directory / (stem + ".partial.mp4")
        env, window = source
        self.log = (directory / (stem + ".log")).open("wb")
        try:
            self.proc = subprocess.Popen(["nice","-n","12","ffmpeg","-hide_banner","-loglevel","warning","-y",
                "-f","x11grab","-framerate",str(fps),"-draw_mouse","0","-window_id",window,"-i",env["DISPLAY"],
                "-an","-r",str(fps),"-fps_mode","cfr","-c:v","libx264","-threads","1","-preset","veryfast","-crf","20","-pix_fmt","yuv420p",
                "-g",str(fps),"-movflags","+faststart",str(self.partial)],env=env,stdin=subprocess.PIPE,stdout=subprocess.DEVNULL,stderr=self.log)
        except BaseException:
            self.log.close()
            raise

    def finish(self):
        try:
            self.proc.communicate(input=b"q\n",timeout=5)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            self.proc.wait()
        finally:
            self.log.close()
        if self.proc.returncode==0 and self.partial.is_file() and self.partial.stat().st_size>1024:
            self.partial.replace(self.target)
            return True
        return False


def encode_frames(folder,target,fps):
    """Encode one screenshot for every requested render pass and verify the container frame count."""
    count,first,last=map(int,(folder/"finished").read_text().split())
    if count<=0 or count!=last-first+1 or first%3 or count%3 or len(list(folder.glob("*.png")))!=count:
        raise ValueError("Missing rendered Minecraft frames")
    target.parent.mkdir(exist_ok=True)
    partial=target.with_suffix(".partial.mp4")
    subprocess.run(["nice","-n","12","ffmpeg","-v","error","-y","-framerate",str(fps),"-i",str(folder/"%06d.png"),
                    "-frames:v",str(count),"-c:v","libx264","-threads","1","-preset","veryfast","-crf","18",
                    "-pix_fmt","yuv420p","-g",str(fps),"-movflags","+faststart",partial],check=True)
    encoded=subprocess.check_output(["ffprobe","-v","error","-select_streams","v:0","-show_entries","stream=nb_frames",
                                     "-of","default=noprint_wrappers=1:nokey=1",partial],text=True)
    if int(encoded.strip())!=count:raise ValueError("The encoded film frame count changed")
    partial.replace(target)
    shutil.rmtree(folder)
    return dict(count=count,first=first,last=last,frames_per_tick=3)
