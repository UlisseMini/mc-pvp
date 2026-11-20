"""Keep finished Java videos in a user-visible folder, independent of temporary run files."""
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
from first_ko import correction

ARCHIVE=Path(__file__).resolve().parents[2]/"Sword League Recordings"


def archive_panel(tag,run,rows=None):
    if not re.fullmatch(r"(java-\d+-[abcd]|timing-\d+|scout-\d+|showcase-\d+)",tag):
        raise ValueError("Unknown league panel")
    if not (run/"videos").is_dir():return
    rows=rows if rows is not None else json.loads((run/"results.json").read_text())
    dest=ARCHIVE/tag
    dest.mkdir(parents=True,exist_ok=True)
    for row in rows:
        name=row["id"]
        if not re.fullmatch(r"round-\d+",name):continue
        source=run/"videos"/(name+".mp4");target=dest/(name+".mp4")
        if target.exists() or not source.is_file():continue
        try:os.link(source,target)
        except FileExistsError:pass
        except OSError:
            with tempfile.NamedTemporaryFile(dir=dest,delete=False) as tmp:
                with source.open("rb") as infile:shutil.copyfileobj(infile,tmp)
            os.replace(tmp.name,target)
    changes={}
    for r in rows:
        result=correction(run,r["id"],r["seat"],r["hp"],r["opponent_hp"],r["ticks"])
        if result:changes[r["id"]]=result
    if changes:
        revisions=dest/"first-ko-corrections.json"
        content=json.dumps(changes,indent=2)+"\n"
        if not revisions.exists() or revisions.read_text()!=content:
            with tempfile.NamedTemporaryFile(mode="w",dir=dest,delete=False) as tmp:
                tmp.write(content)
            os.replace(tmp.name,revisions)
    for name in ("metadata.json","results.json"):
        source=run/name;target=dest/name
        if target.exists() and target.stat().st_mtime_ns==source.stat().st_mtime_ns:continue
        with tempfile.NamedTemporaryFile(dir=dest,delete=False) as tmp:pending=Path(tmp.name)
        shutil.copy2(source,pending);pending.replace(target)


def trim_after_knockout(tag,run):
    """Make a viewing copy ending at the first kill. Keep the original game pixels in the run."""
    import subprocess
    dest=ARCHIVE/tag
    revisions=dest/"first-ko-corrections.json"
    if not revisions.is_file():return
    manifest=dest/"video-cuts.json"
    cuts=json.loads(manifest.read_text()) if manifest.is_file() else {}
    fps=json.loads((run/"metadata.json").read_text()).get("video",{}).get("fps",50)
    for rid,row in json.loads(revisions.read_text()).items():
        raw=run/"videos"/(rid+".mp4")
        if not raw.is_file() or rid in cuts or row["post_ko_seconds"]<=.25:continue
        duration=float(subprocess.check_output(["ffprobe","-v","error","-show_entries","format=duration",
                    "-of","default=noprint_wrappers=1:nokey=1",raw],text=True))
        end=max(.1,duration-row["post_ko_seconds"]+.15)
        with tempfile.NamedTemporaryFile(dir=dest,suffix=".mp4",delete=False) as tmp:
            cut=Path(tmp.name)
        try:
            subprocess.run(["nice","-n","14","ffmpeg","-v","error","-y","-i",raw,"-t",str(end),
                       "-vf",f"fps={fps}","-c:v","h264_videotoolbox","-b:v","3M","-g",str(fps),
                       "-pix_fmt","yuv420p","-movflags","+faststart",cut],check=True)
            cut.replace(dest/(rid+".mp4"))
        finally:cut.unlink(missing_ok=True)
        cuts[rid]={"source_seconds":duration,"cut_seconds":end,"first_knockout_epoch_ms":row["first_ko_epoch_ms"]}
    pending=manifest.with_suffix(".pending");pending.write_text(json.dumps(cuts,indent=2)+"\n");pending.replace(manifest)
