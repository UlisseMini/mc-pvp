"""Make an offline replay from the logged local and server observations."""
import csv
import json
from pathlib import Path
import sys

TEMPLATE = r"""<!doctype html><html lang="en"><meta charset="utf-8"><title>Sword duel replay</title>
<style>*{box-sizing:border-box}body{background:#172327;color:#f5f2e7;font:16px system-ui;max-width:920px;margin:24px auto;padding:0 16px}h1{font-size:24px}canvas{width:100%;background:#10191b;border-radius:8px}button,select{font:inherit;padding:.35em}input{width:100%}output{font-variant-numeric:tabular-nums;display:block;margin:.5em 0}</style>
<h1>Sword duel replay</h1><label>Round <select id="round"></select></label> <label>View <select id="view"><option>Server</option><option>Client A</option><option>Client B</option></select></label>
<canvas width="860" height="440"></canvas><input id="seek" type="range" value="0" min="0" step="1" aria-label="Replay tick">
<button id="play">Play</button> <label>Speed <select id="speed"><option>.25</option><option>.5</option><option selected>1</option><option>2</option></select></label><output id="status"></output>
<script>
const data=DATA, matches=MATCHES, ctx=document.querySelector('canvas').getContext('2d'), el=id=>document.getElementById(id);
const colors=['#77d1e2','#ffb478'];let timer=0;
for(const id of Object.keys(data)){const m=matches[id];el('round').add(new Option(m?m.title:id,id))}
function text(s,x,y,color){ctx.fillStyle=color;ctx.font='16px system-ui';ctx.fillText(s,x,y)}
function frame(){let id=el('round').value,view=el('view').selectedIndex,rows=data[id][view],idx=Math.min(+el('seek').value,rows.length-1);
el('seek').max=Math.max(0,rows.length-1);ctx.fillStyle='#10191b';ctx.fillRect(0,0,860,440);
const m=matches[id],names=m?m.names:['A','B'];el('view').options[1].text='A · '+names[0];el('view').options[2].text='B · '+names[1];
if(!rows.length){text('No client recording for this seat',20,40,'white');el('status').textContent='';return}
const r=rows[idx];let line='';
if(!view){ctx.strokeStyle='#647a79';ctx.strokeRect(250,40,360,360);
for(let j=0;j<2;j++){let offset=3+j*3;ctx.fillStyle=colors[j];ctx.beginPath();ctx.arc(430+r[offset]*15,220+r[offset+2]*15,6,0,2*Math.PI);ctx.fill();
text((j?'B  ':'A  ')+r[1+j].toFixed(1)+' HP',j?630:40,100,colors[j]);text(names[j],j?630:40,125,colors[j])}}
else{let dx=r[4]-r[1],dz=r[6]-r[3],yaw=r[21]*Math.PI/180,pitch=r[22]*Math.PI/180;
let horizontal=-dx*Math.sin(yaw)+dz*Math.cos(yaw),side=dx*Math.cos(yaw)+dz*Math.sin(yaw),height=r[5]-r[2]+.9-1.62;
let depth=horizontal*Math.cos(pitch)-height*Math.sin(pitch);
ctx.strokeStyle='#536865';ctx.beginPath();ctx.moveTo(0,220-360*Math.tan(pitch));ctx.lineTo(860,220-360*Math.tan(pitch));ctx.stroke();
if(depth>.05){let scale=360/depth,cx=430-side*scale,cy=220-(height*Math.cos(pitch)+horizontal*Math.sin(pitch))*scale;
ctx.strokeStyle=colors[view===1?1:0];ctx.strokeRect(cx-.3*scale,cy-.9*scale,.6*scale,1.8*scale)}
ctx.fillStyle='white';ctx.fillRect(425,219,10,2);ctx.fillRect(429,215,2,10);
line=(r[16]>0?' W':r[16]<0?' S':'')+(r[17]>0?' A':r[17]<0?' D':'')+(r[18]?' Sprint':'')+(r[19]?' Jump':'')+(r[20]?' Attack':'');
text(r[13].toFixed(1)+' HP    '+Math.round(100*r[12])+'% charge',20,32,colors[view-1]);}
const stamp=r[view?23:15],hit=m?m.events.filter(e=>e[0]<=stamp&&e[0]>stamp-220).pop():null;
el('status').textContent='Tick '+r[0]+'   '+(r[0]/20).toFixed(2)+' s   '+line+(hit?'   ·   '+hit[1]:'');}
function stop(){clearTimeout(timer);timer=0;el('play').textContent='Play'}
function advance(){if(+el('seek').value>=+el('seek').max){stop();return}el('seek').stepUp();frame();timer=setTimeout(advance,50/+el('speed').value)}
el('play').onclick=()=>{if(timer){stop();return}if(+el('seek').value>=+el('seek').max)el('seek').value=0;el('play').textContent='Pause';timer=setTimeout(advance,0)};
for(const id of ['round','view'])el(id).onchange=()=>{stop();el('seek').value=0;frame()};
el('seek').oninput=frame;frame();</script></html>"""


def match_labels(folder):
    if not (folder/"results.json").exists():
        return {}
    meta=json.loads((folder/"metadata.json").read_text())
    titles={"wtap":"W-tap","champion":"Trained bot","population":"Retreating rival","gradient":"Recurrent rival"}
    saved={}
    for group,label in [("flat-training","Earlier fast-trained fighter"),("league26","League fighter"),
                        ("neural26","Neural challenger"),("random26","From-scratch fighter")]:
        record=Path(__file__).resolve().parent/"evidence"/group/"selection.json"
        if record.exists():
            saved[json.loads(record.read_text())["sha256"]]=label
    def title(file,digest=None):
        stem=Path(file).stem
        return saved.get(digest,titles.get(stem,stem.replace("-"," ").capitalize()))
    labels={}
    for row in json.loads((folder/"results.json").read_text()):
        seat=row["seat"]
        who=row["policy"]
        digest=None
        if who=="learned":
            who=meta.get("primary_file","champion")
            digest=meta.get("weights_sha256")
        elif who.startswith("learned-"):
            item=meta["extra_weights"][int(who.split("-")[1])-1]
            who,digest=item["file"],item.get("sha256")
        rival_file=row.get("rival","wtap")
        rival=meta["names"][1-seat] if rival_file=="human" else title(rival_file,meta.get("opponent_hashes",{}).get(rival_file))
        names=[rival,rival]
        names[seat]=title(who,digest)
        events=[]
        contacts=folder/(row["id"]+"-contacts.tsv")
        if contacts.exists():
            with contacts.open() as f:
                for event in csv.DictReader(f,delimiter="\t"):
                    if "epochMs" not in event:break
                    actor=meta["names"].index(event["attacker"])
                    kind="critical hit" if event["critical"]=="true" else "sprint hit" if event["boosted"]=="true" else "hit"
                    events.append([int(event["epochMs"]),names[actor]+" · "+kind])
        number=int(row["id"].split("-")[1])+1
        labels[row["id"]]=dict(names=names,title=f"{number} · {row['rtt']} ms · {names[0]} vs. {names[1]}",events=events)
    return labels


def build(folder):
    data={}
    for file in sorted(folder.glob("round-*-server.tsv")):
        key=file.name.removesuffix("-server.tsv")
        views=[]
        for suffix in ("server","A","B"):
            p=folder/f"{key}-{suffix}.tsv"
            if not p.exists():
                views.append([])
                continue
            with p.open() as f:
                rows=csv.reader(f,delimiter="\t");next(rows,None)
                views.append([[v=="true" if v in ("true","false") else float(v) for v in r] for r in rows])
        data[key]=views
    out=folder/"Replay.html"
    names=json.dumps(match_labels(folder),separators=(",",":")).replace("<","\\u003c")
    out.write_text(TEMPLATE.replace("DATA",json.dumps(data,separators=(",",":")),1).replace("MATCHES",names,1))
    return out


if __name__=="__main__":
    print(build(Path(sys.argv[1])))
