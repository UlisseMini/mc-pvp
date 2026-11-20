"""Serve the live league and its recordings on this machine."""
import argparse
import csv
from collections import Counter
from functools import lru_cache
import json
import mimetypes
from pathlib import Path
import re
import statistics
import threading
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit
from recordings import ARCHIVE,archive_panel
from first_ko import correction

SRC = Path(__file__).resolve().parent
ROOT = SRC.parents[1] / "build/sword26"
WORK = ROOT / "ladder26"
EVIDENCE = SRC / "evidence/ladder26"
SITE = SRC / "watch"
TAG = re.compile(r"(?:java-[0-9]+-[abcd]|timing-[0-9]+|scout-[0-9]+|showcase-[0-9]+)")
ROUND = re.compile(r"round-[0-9]{3,7}")
lock = threading.Lock()
cached = None


def read(path, default=None):
    try:return json.loads(path.read_text())
    except (OSError, ValueError):return default


def short(path, labels=None):
    s = str(path)
    for tail, name in [("evidence/league26/fighter.net","Option 4"),("evidence/random26/fighter.net","Option 5"),
                       ("frozen/opponents/population.net","Retreating rival"),("1ed6988fc1d29b7d.net","Trade specialist"),
                       ("evidence/neural26/fighter.net","Recurrent fighter")]:
        if s.endswith(tail):return name
    if s=="wtap":return "W-tap"
    return (labels or {}).get(Path(s).stem,"Fighter "+Path(s).stem[:6])


def run_for(tag):
    if not TAG.fullmatch(tag):return
    complete = read(EVIDENCE / tag / "panel.json", {})
    name = complete.get("run")
    if not name:
        for log in sorted((WORK / tag).glob("native-*.log"),reverse=True):
            with log.open() as f:
                for line in f:
                    if line.startswith("Round recordings: "):
                        name = Path(line.strip().split(": ",1)[1]).name
                        break
            if name:break
    if name and re.fullmatch(r"native-[a-zA-Z0-9_-]+",name):
        return ROOT / name if (ROOT/name).is_dir() else ARCHIVE/tag


@lru_cache(maxsize=512)
def counted(path, size, modified):
    with path.open() as f:
        return sum(int(row["duels"]) for row in csv.DictReader(f,delimiter="\t") if row.get("duels","").isdigit())


@lru_cache(maxsize=4096)
def rendered_rate(path):
    if not path.is_file():return None
    with path.open() as f:
        source=csv.DictReader(f,delimiter="\t")
        if "renderFps" not in (source.fieldnames or []):return None
        values=[int(r["renderFps"]) for r in source]
    return statistics.median(values) if values else None


def snapshot():
    global cached
    with lock:
        if cached and time.monotonic()-cached[0]<3:return cached[1]
        state=read(EVIDENCE/"state.json",{})
        seasons=[];labels={}
        for path in sorted(EVIDENCE.glob("season-*/result.json")):
            s=read(path)
            if not s:continue
            for fighter in s["finalists"]:
                file=fighter["file"];stem=Path(file).stem
                role="main" if file==s["main"] else "generalist" if file==s["best"] else "counter" if file==s["native_counter"] else "response"
                labels.setdefault(stem,f"Season {s['index']} {role}")
            main=next(x for x in s["finalists"] if x["file"]==s["main"])
            seasons.append(dict(n=s["index"],main=main["breadth"],wt=s["wtap_breadth"],advanced=s["advanced"]))
        repeats=Counter(labels.values())
        labels={stem:name if repeats[name]==1 else f"{name} · {stem[:5]}" for stem,name in labels.items()}
        bouts=[];panels=[]
        for reg in EVIDENCE.glob("*/registration.json"):
            tag=reg.parent.name
            run=run_for(tag)
            if not run:continue
            roster=read(reg,{})
            results=read(run/"results.json",[])
            if results and run.parent==ROOT:archive_panel(tag,run,results)
            actors=roster["actors"];meta=read(run/"metadata.json",{})
            if tag.startswith("java-") and len(roster["opponents"])>=3:
                wave=read(EVIDENCE/tag.rsplit("-",1)[0]/"nomination.json",{})
                if wave.get("final_challenge"):
                    labels.setdefault(Path(roster["opponents"][-1]).stem,"Independent rival")
            ended=(reg.parent/"panel.json").exists(); total=2*len(roster["rtts"])*roster["pairs"]*len(roster["opponents"])*(len(actors)+1)
            panels.append(dict(tag=tag,done=len(results),total=total,finished=ended,when=reg.stat().st_mtime))
            archived_scores=read(ARCHIVE/tag/"first-ko-corrections.json",{})
            for row in results:
                row=row|archived_scores.get(row["id"],{})|correction(run,row["id"],row["seat"],row["hp"],row["opponent_hp"],row["ticks"])
                label=row["policy"]
                actor="wtap" if label=="wtap" else actors[0 if label=="learned" else int(label.split("-")[1])]
                rid=row["id"]
                if not ROUND.fullmatch(rid):continue
                clip=run/"videos"/(rid+".mp4")
                has_video=clip.is_file() or (ARCHIVE/tag/(rid+".mp4")).is_file()
                bouts.append(dict(id=tag+"/"+rid,tag=tag,number=int(rid[6:])+1,actor=short(actor,labels),rival=short(row["rival"],labels),
                                  rivalId=meta.get("opponent_hashes",{}).get(row["rival"],row["rival"]),
                                  rtt=row["rtt"],opening=row["pair"],seat="AB"[row["seat"]],hp=row["hp"],otherHP=row["opponent_hp"],
                                  ticks=row["ticks"],video=has_video,trace=(run/(rid+"-server.tsv")).is_file(),fps=meta.get("video",{}).get("fps",50),
                                  framePolicy=meta.get("client_frame_policy","afk"),frameExport=row.get("frame_export"),height=meta.get("video",{}).get("height"),
                                  rendered=rendered_rate(run/(rid+"-"+"AB"[row["seat"]]+".tsv")) if meta.get("client_frame_policy")=="minimized" else None,
                                  when=reg.stat().st_mtime))
        bouts.sort(key=lambda x:(x["when"],x["number"]),reverse=True)
        panels.sort(key=lambda x:x["when"],reverse=True)
        games=0
        for file in WORK.glob("*/*/training.tsv"):
            if "partial" in str(file):continue
            st=file.stat();games+=counted(file,st.st_size,st.st_mtime_ns)
        extra=WORK/"opening-counter/training.tsv"
        if extra.exists():
            st=extra.stat();games+=counted(extra,st.st_size,st.st_mtime_ns)
        decisions=[]
        for file in sorted(EVIDENCE.glob("java-*/decision.json")):
            d=read(file)
            if not d:continue
            decisions.append(dict(season=int(file.parent.name.split("-")[1]),champion=short(d["champion"],labels),
                promoted=d["promoted"],rival=short(d["old_champion"],labels),fighters=[
                    dict(name=short(k,labels),overall=v,direct=d["native_direct"][k]) for k,v in d["native_overall"].items()
                ]+[dict(name="W-tap",overall=d["wtap_overall"],direct=d["wtap_direct"])]))
        current=next((p for p in panels if not p["finished"] and p["done"]<p["total"]),None)
        latest=seasons[-1]["n"] if seasons else 0
        result=dict(status=state.get("status","running"),champion=short(state.get("incumbent","evidence/league26/fighter.net"),labels),
                    season=latest,trainingGames=games,history=seasons,decisions=decisions,matches=bouts,panel=current)
        cached=(time.monotonic(),result)
        return result


def trace(run,rid):
    with (run/(rid+"-server.tsv")).open() as f:
        raw=list(csv.DictReader(f,delimiter="\t"))
    epoch=int(raw[0]["epochMs"])
    frames=[[int(r["epochMs"])-epoch,*[float(r[c]) for c in ("ax","ay","az","bx","by","bz","hpA","hpB")]] for r in raw]
    with (run/(rid+"-contacts.tsv")).open() as f:
        hits=[[int(r["epochMs"])-epoch,r["attacker"][-1],r["critical"]=="true"] for r in csv.DictReader(f,delimiter="\t")]
    match=next(r for r in read(run/"results.json") if r["id"]==rid)
    fixed=correction(run,rid,match["seat"],match["hp"],match["opponent_hp"],match["ticks"])
    if fixed:
        end=fixed["first_ko_epoch_ms"]-epoch
        frames=[f for f in frames if f[0]<=end];hits=[e for e in hits if e[0]<=end]
        hp=[fixed["hp"],fixed["opponent_hp"]]
        if match["seat"]==1:hp.reverse()
        frames.append([end,*frames[-1][1:7],*hp])
    return {"frames":frames,"hits":hits}


class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def blob(self,content,mime):
        self.send_response(200);self.send_header("Content-Type",mime);self.send_header("Content-Length",str(len(content)))
        self.send_header("Cache-Control","no-cache");self.end_headers()
        if self.command!="HEAD":self.wfile.write(content)
    def clip(self,path):
        if not path.is_file():return self.send_error(404)
        size=path.stat().st_size;start=0;end=size-1;r=self.headers.get("Range")
        if r:
            m=re.fullmatch(r"bytes=(\d*)-(\d*)",r)
            if m and m[1]:start=int(m[1]);end=min(end,int(m[2])) if m[2] else end
            elif m and m[2]:start=max(0,size-int(m[2]))
            else:start=size
            if start>end or start>=size:
                self.send_response(416);self.send_header("Content-Range",f"bytes */{size}");self.end_headers();return
        self.send_response(206 if r else 200);self.send_header("Content-Type","video/mp4");self.send_header("Accept-Ranges","bytes")
        if r:self.send_header("Content-Range",f"bytes {start}-{end}/{size}")
        self.send_header("Content-Length",str(end-start+1));self.end_headers()
        if self.command=="HEAD":return
        with path.open("rb") as f:
            f.seek(start);remaining=end-start+1
            while remaining:
                chunk=f.read(min(128*1024,remaining))
                if not chunk:break
                self.wfile.write(chunk);remaining-=len(chunk)
    def do_HEAD(self):self.do_GET()
    def do_GET(self):
        route=urlsplit(self.path).path
        try:
            if route in ("/","/style.css","/watch.js"):
                p=SITE/("index.html" if route=="/" else route[1:])
                return self.blob(p.read_bytes(),mimetypes.guess_type(p)[0]+"; charset=utf-8")
            if route=="/api/league":
                return self.blob(json.dumps(snapshot(),separators=(",",":")).encode(),"application/json")
            bits=route.strip("/").split("/")
            if len(bits)==3 and bits[0] in ("video","trace") and TAG.fullmatch(bits[1]) and ROUND.fullmatch(bits[2]):
                run=run_for(bits[1]);rid=bits[2]
                if not run:return self.send_error(404)
                if bits[0]=="video":
                    saved=ARCHIVE/bits[1]/(rid+".mp4")
                    return self.clip(saved if saved.exists() else run/"videos"/(rid+".mp4"))
                if not (run/(rid+"-server.tsv")).is_file():return self.send_error(404)
                return self.blob(json.dumps(trace(run,rid),separators=(",",":")).encode(),"application/json")
            self.send_error(404)
        except (BrokenPipeError,ConnectionResetError):pass


if __name__=="__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--port",type=int,default=25708)
    parser.add_argument("--open",action="store_true")
    args=parser.parse_args()
    address=("127.0.0.1",args.port)
    url=f"http://{address[0]}:{address[1]}/"
    try:server=ThreadingHTTPServer(address,Handler)
    except OSError:
        if not args.open:raise
        import urllib.request
        with urllib.request.urlopen(url+"api/league",timeout=2) as response:
            if "trainingGames" not in json.load(response):raise
        webbrowser.open(url)
    else:
        print(url,flush=True)
        if args.open:webbrowser.open(url)
        server.serve_forever()
