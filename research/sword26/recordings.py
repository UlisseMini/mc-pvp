"""Keep finished Java videos in a user-visible folder, independent of temporary run files."""
import json
import os
from pathlib import Path
import re
import shutil
import tempfile

ARCHIVE=Path(__file__).resolve().parents[2]/"Sword League Recordings"


def archive_panel(tag,run,rows=None):
    if not re.fullmatch(r"(java-\d+-[ab]|scout-\d+|showcase-\d+)",tag):
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
    for name in ("metadata.json","results.json"):
        source=run/name;target=dest/name
        if target.exists() and target.stat().st_mtime_ns==source.stat().st_mtime_ns:continue
        with tempfile.NamedTemporaryFile(dir=dest,delete=False) as tmp:pending=Path(tmp.name)
        shutil.copy2(source,pending);pending.replace(target)
